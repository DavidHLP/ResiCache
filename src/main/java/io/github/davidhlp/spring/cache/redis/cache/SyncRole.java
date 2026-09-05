package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * SyncSupport single-flight 选举产出的运行时角色。
 *
 * <p>single-flight 协议下,一个 key 的并发请求在运行期会落到 3 个互斥角色之一:
 * <ul>
 *   <li>{@link Reentrant} — 同线程同 key 嵌套重入(chain 内 SyncLockHandler → super.get → chain GET
 *       → SyncLockHandler 再次进入)。{@code CompletableFuture} 不可重入(否则 leader
 *       join 自己 → 死锁),等价于 {@code synchronized} 模型可重入,直接跑 loader。</li>
 *   <li>{@link Leader} — single-flight 选举首个 putIfAbsent 胜出者:持分布式锁跑 loader,
 *       完成后 {@code complete} future 供 follower 共享;清理 reentrantKeys / inFlight。</li>
 *   <li>{@link Follower} — 选举时拿到 leader 已发布的 future,直接 {@code join} 共享结果,
 *       零重复持锁、零重复回源。</li>
 * </ul>
 *
 * <p>每个角色自承 state + cleanup,本 seam 让「角色是什么」与「选举函数」解耦 —
 * {@link SyncSupport#executeSync} 只做"选角色 → 调 run"两步,角色内部知道自己的全部生命周期。
 *
 * <p><b>deletion test</b>:把 3 个角色删掉、内联回 {@code SyncSupport.executeSync} →
 * 3 个分支变 3 段 inline 镜像样板,复杂度**上升**。本 seam 浓缩复杂度。
 *
 * <p><b>设计纪律</b>:
 * <ul>
 *   <li>{@link Reentrant} 是 record(纯值,无状态);{@link Leader} / {@link Follower} 是 final
 *       class(需接收外部 state,无法用 record 表达多字段)</li>
 *   <li>{@link Leader} / {@link Follower} 持有构造时传入的 state,**不**反向引用 SyncSupport
 *       实例(避免角色认识 orchestrator,违反 locality)</li>
 *   <li>{@code run(loader)} 的 {@code InterruptedException} 由 Leader 内部处理
 *       (转换为 IllegalStateException)</li>
 *   <li>包私有:仅 SyncSupport 调用,不对外暴露</li>
 * </ul>
 */
sealed interface SyncRole<T> permits SyncRole.Reentrant, SyncRole.Leader, SyncRole.Follower {

    /**
     * 执行本角色对应的 single-flight 动作(loader 已在角色构造时传入,
     * 保持签名无参以统一 3 个角色的调用契约)。
     *
     * @return 角色对应路径的返回值
     */
    T run();

    // ==================== Reentrant ====================

    /**
     * 重入角色 — 同线程同 key 嵌套重入场景,直接跑 loader(等价 synchronized 可重入,
     * 避开 future 不可重入陷阱)。
     *
     * <p>纯值类型,无 state 字段,构造后无副作用。
     */
    record Reentrant<T>(Supplier<T> loader) implements SyncRole<T> {

        @Override
        public T run() {
            return loader.get();
        }
    }

    // ==================== Leader ====================

    /**
     * Leader 角色 — 持锁跑 loader,complete future 供 follower 共享,清理 reentrantKeys / inFlight。
     *
     * <p>state 字段(全部 final,immutable role):
     * <ul>
     *   <li>{@code key} — 锁 + future key</li>
     *   <li>{@code timeoutSeconds} — 透传给 LockManager.tryAcquire + future.get</li>
     *   <li>{@code mine} — 本 leader 发布的 future(在 putIfAbsent 时已被 inFlight 持有)</li>
     *   <li>{@code distributedManagers} — 分布式锁后端列表(可空 → 走 fail-fast/local-only)</li>
     *   <li>{@code properties} — 读 sync-lock.local-only 降级开关</li>
     *   <li>{@code inFlight} — single-flight 注册表,finally 中按 value 匹配移除避免误删后一个 leader</li>
     *   <li>{@code reentrantKeys} — ThreadLocal 标记本线程已持有 leader 身份(防 future 不可重入陷阱)</li>
     * </ul>
     */
    @Slf4j
    final class Leader<T> implements SyncRole<T> {

        private final String key;
        private final long timeoutSeconds;
        private final Supplier<T> loader;
        private final CompletableFuture<Object> mine;
        private final List<LockManager> distributedManagers;
        private final RedisProCacheProperties properties;
        private final ConcurrentMap<String, CompletableFuture<Object>> inFlight;
        private final ThreadLocal<java.util.Set<String>> reentrantKeys;

        Leader(String key,
               long timeoutSeconds,
               Supplier<T> loader,
               CompletableFuture<Object> mine,
               List<LockManager> distributedManagers,
               RedisProCacheProperties properties,
               ConcurrentMap<String, CompletableFuture<Object>> inFlight,
               ThreadLocal<java.util.Set<String>> reentrantKeys) {
            this.key = key;
            this.timeoutSeconds = timeoutSeconds;
            this.loader = loader;
            this.mine = mine;
            this.distributedManagers = distributedManagers;
            this.properties = properties;
            this.inFlight = inFlight;
            this.reentrantKeys = reentrantKeys;
        }

        @Override
        public T run() {
            reentrantKeys.get().add(key);
            T value = null;
            RuntimeException failure = null;
            boolean success = false;
            try {
                value = doLeaderWork(loader);
                success = true;
            } catch (final RuntimeException e) {
                failure = e;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new IllegalStateException(
                        "Thread interrupted while acquiring distributed lock for key: " + key, e);
            } finally {
                if (success) {
                    mine.complete(value);
                } else {
                    mine.completeExceptionally(failure);
                }
                reentrantKeys.get().remove(key);
                // 只移除自己发布的 future,避免误删后一个 leader
                inFlight.remove(key, mine);
            }
            if (success) {
                return value;
            }
            throw failure;
        }

        /**
         * 实际工作:无后端时走 fail-fast/local-only,有后端时持锁跑 loader.
         *
         * @throws InterruptedException 当 {@link LockManager#tryAcquire} 被中断时
         */
        private T doLeaderWork(Supplier<T> loader)
                throws InterruptedException {
            if (distributedManagers.isEmpty()) {
                // 无分布式锁后端:fail-fast 或 local-only 显式降级(均不进 LockStack)
                return executeWithoutDistributedBackend(loader);
            }

            // 有分布式锁后端:持锁跑 loader
            try (LockStack lockStack = new LockStack()) {
                for (LockManager manager : distributedManagers) {
                    manager.tryAcquire(key, timeoutSeconds).ifPresentOrElse(lockStack::push, () -> {
                        log.warn("Lock manager {} failed to acquire distributed lock for key: {}",
                                manager.getClass().getSimpleName(), key);
                        throw new RuntimeException("Failed to acquire distributed lock");
                    });
                }

                log.debug("Acquired distributed lock(s) for cache key: {} (count={})", key, lockStack.size());
                return loader.get();
            }
        }

        /**
         * 无分布式锁后端时的处理:fail-fast 或显式 local-only 降级.
         *
         * <p>leader 调用此方法时已在 single-flight 选举中胜出,follower 由其 future 兜底,
         * 故 local-only 降级路径直接执行 loader 即享有"leader 串行、follower 共享"的单 JVM 串行语义。
         */
        private T executeWithoutDistributedBackend(Supplier<T> loader) {
            if (properties.getSyncLock().isLocalOnly()) {
            // 显式合法降级:单 JVM single-flight(leader 串行跑 loader,follower join future)。
                log.warn("protection.degraded=local-only: sync=true 但无分布式锁后端, "
                        + "已按 local-only=true 降级为单 JVM 同步 (key={})", key);
                return loader.get();
            }
            // fail-fast:绝不静默退化为单 JVM。多实例下单 JVM synchronized 无法防击穿,
            // 标榜分布式却单机是最坏失败模式 —— 必须让用户立刻看见。
            throw new IllegalStateException(
                    "sync=true 已声明但无分布式锁后端 (无 RedissonClient / LockManager bean)。"
                            + "拒绝静默退化为单 JVM synchronized (多实例下无法防击穿)。"
                            + "请引入 Redisson, 或显式设 resi-cache.sync-lock.local-only=true 接受单实例降级。"
                            + " [key=" + key + "]");
        }

        /**
         * 锁堆栈,用于管理多个锁的自动关闭 —— Leader 私有,因为它本质上是 leader
         * 持锁的载体,不应暴露给 SyncSupport 或 Follower。
         */
        private static final class LockStack implements AutoCloseable {

            private final Deque<LockManager.LockHandle> handles = new ConcurrentLinkedDeque<>();

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
                        log.error("Failed to release distributed lock", e);
                    }
                }
            }
        }
    }

    // ==================== Follower ====================

    /**
     * Follower 角色 — join leader 的 future,共享其结果(零重复持锁、零重复回源).
     *
     * <p>state 字段(全部 final,immutable role):
     * <ul>
     *   <li>{@code key} — 用于错误日志</li>
     *   <li>{@code leader} — leader 发布的 future(在选举时拿到)</li>
     *   <li>{@code timeoutSeconds} — follower 等待上限(对齐原行为:{@code <= 0} 时若 leader
     *       未完成则立即失败,模仿 Redisson tryLock(0) 立即返回语义)</li>
     * </ul>
     *
     * <p>失败传播:leader 异常经 {@code completeExceptionally} 透传给 follower,
     * follower 收到的是 leader 的原始异常(RuntimeException 原样抛;checked 包成 RuntimeException)。
     */
    final class Follower<T> implements SyncRole<T> {

        private final String key;
        private final CompletableFuture<Object> leader;
        private final long timeoutSeconds;

        Follower(String key, CompletableFuture<Object> leader, long timeoutSeconds) {
            this.key = key;
            this.leader = leader;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T run() {
            try {
                if (timeoutSeconds <= 0 && !leader.isDone()) {
                    throw new IllegalStateException(
                            "In-flight single-flight loader still running; waitTimeoutSeconds=" + timeoutSeconds
                                    + " <= 0 — follower refuses to wait (key=" + key + ")");
                }
                final Object value = (timeoutSeconds > 0)
                        ? leader.get(timeoutSeconds, TimeUnit.SECONDS)
                        : leader.get();
                return (T) value;
            } catch (final TimeoutException e) {
                throw new IllegalStateException(
                        "Timed out after " + timeoutSeconds
                                + "s waiting for in-flight single-flight loader (key=" + key + ")", e);
            } catch (final ExecutionException e) {
                // leader 的原始异常:RuntimeException 原样抛(保留调用方既有 catch 语义)
                final Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException("In-flight single-flight loader failed (key=" + key + ")", cause);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Thread interrupted while waiting for in-flight loader (key=" + key + ")", e);
            }
        }
    }
}
