package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>角色只接收 {@link SyncStateAccess} 的窄生命周期契约,不直接持有三个 registry。这样一个
 * per-key sync state 只有一个 owner,发布、完成和清理由同一处负责。
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
                value = SyncRoleLockExecutor.run(
                        log, key, timeout, loader, distributedManagers, properties, state);
                success = true;
            } catch (final RuntimeException e) {
                failure = e;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                // ADR-0001 §15:异常 message 不带 raw key,只带 keyFingerprint
                failure = new IllegalStateException(
                        "Thread interrupted while acquiring distributed lock: keyFingerprint="
                                + FailureDiagnostics.keyFingerprint(key), e);
            } catch (final Throwable t) {
                // Error 仍按原语义重新抛出,但先完成 future 并清理 owner state。
                failure = new IllegalStateException(
                        "Distributed-lock work failed: keyFingerprint="
                                + FailureDiagnostics.keyFingerprint(key), t);
                if (t instanceof Error error) {
                    throw error;
                }
            } finally {
                Throwable completionFailure = success
                        ? null
                        : failure != null
                                ? failure
                                : new IllegalStateException("Single-flight leader aborted before completing");
                state.complete(registration, value, completionFailure);
                state.exit(key);
                state.cleanup(registration);
            }
            if (success) {
                return value;
            }
            throw failure;
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
                    // ADR-0001 §15:异常 message 不带 raw key,只带 keyFingerprint
                    throw new IllegalStateException(
                            "In-flight single-flight loader still running; waitTimeoutSeconds="
                                    + timeoutSeconds
                                    + " <= 0 — follower refuses to wait (keyFingerprint="
                                    + FailureDiagnostics.keyFingerprint(key) + ")");
                }
                final Object value = (timeoutSeconds > 0)
                        ? leader.get(timeoutSeconds, TimeUnit.SECONDS)
                        : leader.get();
                return (T) value;
            } catch (final TimeoutException e) {
                throw new IllegalStateException(
                        "Timed out after " + timeoutSeconds
                                + "s waiting for in-flight single-flight loader (keyFingerprint="
                                + FailureDiagnostics.keyFingerprint(key) + ")", e);
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
                        + FailureDiagnostics.keyFingerprint(key) + ")", cause);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Thread interrupted while waiting for in-flight loader (keyFingerprint="
                                + FailureDiagnostics.keyFingerprint(key) + ")", e);
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
                return SyncRoleLockExecutor.run(
                        log, key, timeout, work, distributedManagers, properties, state);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Thread interrupted while acquiring distributed lock: keyFingerprint="
                                + FailureDiagnostics.keyFingerprint(key), e);
            } finally {
                state.exit(key);
            }
        }
    }
}

/** Narrow lifecycle contract owned by SyncSupport's per-key state holder. */
interface SyncStateAccess {

    boolean isReentrant(String key);

    void enter(String key);

    void exit(String key);

    SyncRegistration publish(String key);

    void complete(SyncRegistration registration, Object value, Throwable failure);

    void cleanup(SyncRegistration registration);

    <T> T executeLocalOnly(String key, SyncLockTimeout.Resolved timeout, Supplier<T> work);
}

/** A single-flight publication returned by the state owner. */
record SyncRegistration(String key, CompletableFuture<Object> future, boolean leader) {
}

/** Shared lock execution for the explicit Leader and Exclusive role cases. */
final class SyncRoleLockExecutor {

    private SyncRoleLockExecutor() {
    }

    static <T> T run(Logger log,
                     String key,
                     SyncLockTimeout.Resolved timeout,
                     Supplier<T> work,
                     List<LockManager> distributedManagers,
                     RedisProCacheProperties properties,
                     SyncStateAccess state) throws InterruptedException {
        if (distributedManagers.isEmpty()) {
            if (properties.getSyncLock().isLocalOnly()) {
                log.warn("protection.degraded=local-only: sync=true 但无分布式锁后端, "
                        + "已按 local-only=true 降级为单 JVM 同步 (keyFingerprint={})",
                        FailureDiagnostics.keyFingerprint(key));
                return state.executeLocalOnly(key, timeout, work);
            }
            // ADR-0001 §15:异常 message 不带 raw key。
            throw new IllegalStateException(
                    "sync=true 已声明但无分布式锁后端 (无 RedissonClient / LockManager bean)。"
                            + "拒绝静默退化为单 JVM synchronized (多实例下无法防击穿)。"
                            + "请引入 Redisson, 或显式设 resi-cache.sync-lock.local-only=true 接受单实例降级。"
                            + " [keyFingerprint=" + FailureDiagnostics.keyFingerprint(key) + "]");
        }

        try (LockStack lockStack = new LockStack(log)) {
            for (LockManager manager : distributedManagers) {
                manager.tryAcquire(key, timeout.seconds()).ifPresentOrElse(lockStack::push, () -> {
                    // ADR-0001 §15 key 隐私:WARN 只带 keyFingerprint
                    log.warn("Lock manager {} failed to acquire distributed lock: keyFingerprint={}",
                            manager.getClass().getSimpleName(),
                            FailureDiagnostics.keyFingerprint(key));
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
                    log.error("Failed to release distributed lock: failure={}",
                            FailureDiagnostics.sanitizedFailure(e));
                    log.debug("Distributed lock release failure detail", e);
                }
            }
        }
    }
}
