package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

/**
 * SyncSupport single-flight 选举产出的运行时角色。
 *
 * <p>single-flight 协议下,一个 key 的并发请求在运行期会落到 4 个互斥角色之一:
 * <ul>
 *   <li>{@link Reentrant} — 同线程同 key 嵌套重入,直接跑 loader,避开 future 不可重入陷阱。</li>
 *   <li>{@link Leader} — 持分布式锁跑 loader,发布并完成 future,随后清理自己的注册。</li>
 *   <li>{@link Follower} — 等待 leader 已发布的 future,共享其结果。</li>
 *   <li>{@link Exclusive} — 写路径只做互斥锁和本地串行,不发布或清理 single-flight 注册。</li>
 * </ul>
 *
 * <p>本文件同时承载角色与它们变更的 per-key 状态: {@link SyncStateAccess} 生命周期契约、
 * 唯一实现 {@link SyncState}(key registry、重入标记、local-only 队列)、
 * {@link SyncRegistration} 发布凭据与 {@link SyncRoleLockExecutor} 分布式锁执行。角色只通过
 * {@link SyncStateAccess} 变更状态,不直接持有三个 registry —— 状态与其生命周期同处一个 owner。
 *
 * <p>包私有:仅 SyncSupport 和同包测试使用,不对外暴露。
 */
sealed interface SyncRole<T>
        permits SyncRole.Reentrant, SyncRole.Leader, SyncRole.Follower, SyncRole.Exclusive {

    /** 执行本角色对应的同步动作。 */
    T run();

    /**
     * 重入角色 — 同线程同 key 嵌套重入场景,直接跑 loader,等价 synchronized 可重入。
     */
    record Reentrant<T>(Supplier<T> loader) implements SyncRole<T> {

        @Override
        public T run() {
            return loader.get();
        }
    }

    /**
     * Leader 角色 — 持锁执行工作并通过 state contract 发布、完成和清理 single-flight future。
     */
    @Slf4j
    final class Leader<T> implements SyncRole<T> {

        private final String key;
        private final SyncLockTimeout.Resolved timeout;
        private final Supplier<T> loader;
        private final SyncRegistration registration;
        private final List<LockManager> distributedManagers;
        private final RedisProCacheProperties properties;
        private final SyncStateAccess state;

        Leader(String key,
               SyncLockTimeout.Resolved timeout,
               Supplier<T> loader,
               SyncRegistration registration,
               List<LockManager> distributedManagers,
               RedisProCacheProperties properties,
               SyncStateAccess state) {
            this.key = key;
            this.timeout = timeout;
            this.loader = loader;
            this.registration = registration;
            this.distributedManagers = distributedManagers;
            this.properties = properties;
            this.state = state;
        }

        @Override
        public T run() {
            state.enter(key);
            T value = null;
            RuntimeException failure = null;
            boolean success = false;
            try {
                value = state.executeRoleWork(log, key, timeout, loader, distributedManagers, properties);
                success = true;
            } catch (final RuntimeException e) {
                failure = e;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new IllegalStateException(
                        "Thread interrupted while acquiring distributed lock: keyFingerprint="
                                + FailureReport.fingerprint(key), e);
            } catch (final Throwable t) {
                // Error 仍按原语义重新抛出,但先完成 future 并清理 owner state。
                failure = new IllegalStateException(
                        "Distributed-lock work failed: keyFingerprint="
                                + FailureReport.fingerprint(key), t);
                if (t instanceof Error error) {
                    throw error;
                }
            } finally {
                finishPublication(value, failure, success);
            }
            if (success) {
                return value;
            }
            throw failure;
        }

        /**
         * Once-only publication lifecycle for a single key: {@code enter} (performed by
         * {@link #run} before the work) → {@code complete} → {@code exit} → {@code cleanup}.
         *
         * <p>Called exactly once from {@link #run}'s {@code finally}, including the {@link Error}
         * rethrow path. Ordering is load-bearing: completion of the shared future must be visible
         * before this thread's reentrancy mark is cleared, and the leader's own registration is
         * removed last so followers that joined can never observe a cleaned-up-but-incomplete
         * publication.
         */
        private void finishPublication(T value, RuntimeException failure, boolean success) {
            Throwable completionFailure = success
                    ? null
                    : failure != null
                            ? failure
                            : new IllegalStateException("Single-flight leader aborted before completing");
            state.complete(registration, value, completionFailure);
            state.exit(key);
            state.cleanup(registration);
        }
    }

    /**
     * Follower 角色 — 等待 leader 的 future,共享其结果,不重复持锁或回源。
     */
    final class Follower<T> implements SyncRole<T> {

        private final String key;
        private final CompletableFuture<Object> leader;
        private final SyncLockTimeout.Resolved timeout;

        Follower(String key, CompletableFuture<Object> leader, SyncLockTimeout.Resolved timeout) {
            this.key = key;
            this.leader = leader;
            this.timeout = timeout;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T run() {
            long timeoutSeconds = timeout.seconds();
            try {
                if (timeoutSeconds <= 0 && !leader.isDone()) {
                    throw new IllegalStateException(
                            "In-flight single-flight loader still running; waitTimeoutSeconds="
                                    + timeoutSeconds
                                    + " <= 0 — follower refuses to wait (keyFingerprint="
                                    + FailureReport.fingerprint(key) + ")");
                }
                final Object value = (timeoutSeconds > 0)
                        ? leader.get(timeoutSeconds, TimeUnit.SECONDS)
                        : leader.get();
                return (T) value;
            } catch (final TimeoutException e) {
                throw new IllegalStateException(
                        "Timed out after " + timeoutSeconds
                                + "s waiting for in-flight single-flight loader (keyFingerprint="
                                + FailureReport.fingerprint(key) + ")", e);
            } catch (final ExecutionException e) {
                // leader 的原始异常:RuntimeException 原样抛,保留调用方既有 catch 语义
                final Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException("In-flight single-flight loader failed (keyFingerprint="
                        + FailureReport.fingerprint(key) + ")", cause);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Thread interrupted while waiting for in-flight loader (keyFingerprint="
                                + FailureReport.fingerprint(key) + ")", e);
            }
        }
    }

    /**
     * Exclusive 角色 — 写路径只执行本次工作,不参与 single-flight publication。
     */
    @Slf4j
    final class Exclusive<T> implements SyncRole<T> {

        private final String key;
        private final SyncLockTimeout.Resolved timeout;
        private final Supplier<T> work;
        private final List<LockManager> distributedManagers;
        private final RedisProCacheProperties properties;
        private final SyncStateAccess state;

        Exclusive(String key,
                  SyncLockTimeout.Resolved timeout,
                  Supplier<T> work,
                  List<LockManager> distributedManagers,
                  RedisProCacheProperties properties,
                  SyncStateAccess state) {
            this.key = key;
            this.timeout = timeout;
            this.work = work;
            this.distributedManagers = distributedManagers;
            this.properties = properties;
            this.state = state;
        }

        @Override
        public T run() {
            state.enter(key);
            try {
                return state.executeRoleWork(log, key, timeout, work, distributedManagers, properties);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Thread interrupted while acquiring distributed lock: keyFingerprint="
                                + FailureReport.fingerprint(key), e);
            } finally {
                state.exit(key);
            }
        }
    }
}

/**
 * Narrow lifecycle and execution contract owned by the per-key state holder ({@link SyncState}).
 * Roles mutate the single-flight state only through this seam, so publication, completion, cleanup,
 * local-only serialisation and the distributed-lock dispatch decision share one owner.
 */
interface SyncStateAccess {

    boolean isReentrant(String key);

    void enter(String key);

    void exit(String key);

    SyncRegistration publish(String key);

    void complete(SyncRegistration registration, Object value, Throwable failure);

    void cleanup(SyncRegistration registration);

    <T> T executeLocalOnly(String key, SyncLockTimeout.Resolved timeout, Supplier<T> work);

    /**
     * 选择分布式锁、本地串行或 fail-fast 路径。local-only fallback 由 state owner 负责,
     * 锁执行器只处理真正的分布式锁。
     *
     * <p>这是角色实际执行工作(或降级/fail-fast)的唯一入口,由 {@link SyncRole.Leader} 与
     * {@link SyncRole.Exclusive} 通过自己持有的 {@code state} 调用,不再回指构造它们的 SyncSupport。
     */
    <T> T executeRoleWork(Logger log,
                          String key,
                          SyncLockTimeout.Resolved timeout,
                          Supplier<T> work,
                          List<LockManager> distributedManagers,
                          RedisProCacheProperties properties) throws InterruptedException;
}

/** A single-flight publication returned by the state owner. */
record SyncRegistration(String key, CompletableFuture<Object> future, boolean leader) {
}

/**
 * 一个 owner 统一管理 key registry 的 publication、completion、cleanup 和 local-only queue,
 * 并负责在分布式锁缺失时选择 local-only 降级或 fail-fast。
 */
final class SyncState implements SyncStateAccess {

    private final ConcurrentMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> localOnlyTails = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<String>> reentrantKeys = ThreadLocal.withInitial(HashSet::new);

    @Override
    public boolean isReentrant(String key) {
        return reentrantKeys.get().contains(key);
    }

    @Override
    public void enter(String key) {
        reentrantKeys.get().add(key);
    }

    @Override
    public void exit(String key) {
        reentrantKeys.get().remove(key);
    }

    @Override
    public SyncRegistration publish(String key) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, mine);
        return existing == null
                ? new SyncRegistration(key, mine, true)
                : new SyncRegistration(key, existing, false);
    }

    @Override
    public void complete(SyncRegistration registration, Object value, Throwable failure) {
        if (failure == null) {
            registration.future().complete(value);
        } else {
            registration.future().completeExceptionally(failure);
        }
    }

    @Override
    public void cleanup(SyncRegistration registration) {
        if (registration.leader()) {
            // 只移除自己发布的 future,避免误删后一个 leader。
            inFlight.remove(registration.key(), registration.future());
        }
    }

    @Override
    public <T> T executeLocalOnly(String key,
                                  SyncLockTimeout.Resolved timeout,
                                  Supplier<T> work) {
        AtomicReference<CompletableFuture<Void>> predecessorRef = new AtomicReference<>();
        CompletableFuture<Void> current = new CompletableFuture<>();
        CompletableFuture<Void> tail = localOnlyTails.compute(key, (ignored, predecessor) -> {
            predecessorRef.set(predecessor);
            return predecessor == null ? current : CompletableFuture.allOf(predecessor, current);
        });
        tail.whenComplete((ignored, failure) -> localOnlyTails.remove(key, tail));
        CompletableFuture<Void> predecessor = predecessorRef.get();
        try {
            if (predecessor != null) {
                awaitPredecessor(key, predecessor, timeout);
            }
            return work.get();
        } finally {
            current.complete(null);
        }
    }

    @Override
    public <T> T executeRoleWork(Logger log,
                                 String key,
                                 SyncLockTimeout.Resolved timeout,
                                 Supplier<T> work,
                                 List<LockManager> distributedManagers,
                                 RedisProCacheProperties properties) throws InterruptedException {
        if (distributedManagers.isEmpty()) {
            if (properties.getSyncLock().isLocalOnly()) {
                FailureReport.warn(log,
                        "protection.degraded=local-only: sync=true 但无分布式锁后端, "
                                + "已按 local-only=true 降级为单 JVM 同步",
                        null, key);
                return executeLocalOnly(key, timeout, work);
            }
            // Key-privacy contract: exception message omits raw key.
            throw new IllegalStateException(
                    "sync=true 已声明但无分布式锁后端 (无 RedissonClient / LockManager bean)。"
                            + "拒绝静默退化为单 JVM synchronized (多实例下无法防击穿)。"
                            + "请引入 Redisson, 或显式设 resi-cache.sync-lock.local-only=true 接受单实例降级。"
                            + " [keyFingerprint=" + FailureReport.fingerprint(key) + "]");
        }
        return SyncRoleLockExecutor.run(log, key, timeout, work, distributedManagers);
    }

    private static void awaitPredecessor(String key,
                                         CompletableFuture<Void> predecessor,
                                         SyncLockTimeout.Resolved timeout) {
        long timeoutSeconds = timeout.seconds();
        try {
            predecessor.get(Math.max(timeoutSeconds, 0L), TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            throw new IllegalStateException(
                    "Timed out after " + timeoutSeconds
                            + "s waiting for the local-only predecessor (keyFingerprint="
                            + FailureReport.fingerprint(key) + ")", e);
        } catch (final ExecutionException e) {
            // 前驱 future 只会 complete(null);兜底避免把 checked 异常漏给调用方。
            throw new IllegalStateException(
                    "Local-only predecessor failed (keyFingerprint="
                            + FailureReport.fingerprint(key) + ")",
                    e.getCause() != null ? e.getCause() : e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Thread interrupted while waiting for the local-only predecessor (keyFingerprint="
                            + FailureReport.fingerprint(key) + ")", e);
        }
    }
}

/** Shared lock execution for the explicit Leader and Exclusive role cases. */
final class SyncRoleLockExecutor {

    private SyncRoleLockExecutor() {
    }

    static <T> T run(Logger log,
                     String key,
                     SyncLockTimeout.Resolved timeout,
                     Supplier<T> work,
                     List<LockManager> distributedManagers) throws InterruptedException {

        try (LockStack lockStack = new LockStack(log)) {
            for (LockManager manager : distributedManagers) {
                manager.tryAcquire(key, timeout.seconds()).ifPresentOrElse(lockStack::push, () -> {
                    FailureReport.warn(log,
                            "Lock manager " + manager.getClass().getSimpleName()
                                    + " failed to acquire distributed lock",
                            null, key);
                    throw new RuntimeException("Failed to acquire distributed lock");
                });
            }

            log.debug("Acquired distributed lock(s) for cache key: {} (count={})",
                    key, lockStack.size());
            return work.get();
        }
    }

    private static final class LockStack implements AutoCloseable {

        private final Deque<LockManager.LockHandle> handles = new java.util.concurrent.ConcurrentLinkedDeque<>();
        private final Logger log;

        private LockStack(Logger log) {
            this.log = log;
        }

        void push(final LockManager.LockHandle handle) {
            handles.push(handle);
        }

        int size() {
            return handles.size();
        }

        @Override
        public void close() {
            while (!handles.isEmpty()) {
                LockManager.LockHandle handle = handles.pop();
                try {
                    handle.close();
                } catch (Exception e) {
                    FailureReport.error(log, "Failed to release distributed lock", e);
                }
            }
        }
    }
}
