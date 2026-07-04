package io.github.davidhlp.spring.cache.redis.protection.breakdown;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 通过 in-flight {@link CompletableFuture} 实现 single-flight 同步加载:
 * 同一 key 的并发请求中,只有 leader 线程获取分布式锁并执行 loader,
 * follower 线程共享 leader 的结果(不重复获取分布式锁、不重复回源)。
 *
 * <p><b>ADR-0042 演进</b>:原 per-key {@code synchronized(monitor)} + 引用计数(MonitorHolder)
 * 模型已替换为 CompletableFuture single-flight。动机与权衡:
 * <ul>
 *   <li><b>吞吐</b>:follower 不再串行 acquire JVM monitor + 分布式锁 + double-check GET,
 *       而是直接 {@code join} leader 的 future。同 key 高并发读 miss 时,N 个 follower 的
 *       O(N × (锁往返 + GET)) 串行开销降为 O(ε)。leader 仍独占分布式锁,击穿语义
 *       (1 个回源)反而更硬。</li>
 *   <li><b>可重入(future 不可重入陷阱)</b>:chain 内 {@code SyncLockHandler} 会嵌套重入
 *       {@code executeSync}(同 key —— {@code RedisProCache.executeSyncLoad} 的 loader 内
 *       {@code super.get} → chain GET → SyncLockHandler 再次进入)。{@code synchronized}
 *       原本天然可重入;{@link CompletableFuture} 不可重入(leader 重入会 join 自己 → 死锁)。
 *       故用 {@link ThreadLocal} 标记当前线程已持有的 key,重入时走 fast-path 直接跑 loader
 *       —— 语义等价,且省去二次分布式锁往返。</li>
 *   <li><b>失败传播(语义改变)</b>:leader loader 抛异常 → future
 *       {@code completeExceptionally},所有 follower 一起失败(不再独立 double-check 自救)。
 *       这更符合击穿保护精神(避免 N 个 follower 在 leader 失败后继续打 DB);
 *       调用方可自行重试。详见 ADR-0042。</li>
 * </ul>
 *
 * <p><b>永不静默降级 (WS-1.2a)</b>:当无分布式锁后端(无 RedissonClient → 无 LockManager bean)
 * 时,任何 {@code sync=true} 操作<b>绝不</b>静默退化为单 JVM synchronized(多实例下击穿照旧,
 * 是最坏失败模式)。默认行为是<b>运行期 fail-fast</b>(首次未命中即抛
 * {@link IllegalStateException})。仅当用户显式声明 {@code resi-cache.sync-lock.local-only=true}
 * 时,才接受单 JVM 同步作为合法降级(单实例/测试场景),并发出
 * {@code protection.degraded=local-only} 告警使安全属性可观测(WS-1.4 升级为 Observation 事件)。
 *
 * <p>注意:{@code sync=true} 是 per-method 注解属性,启动期不可穷举,故 fail-fast 的精确触发点
 * 在运行期 {@link #executeSync}(即用户确实声明了 sync 且缓存未命中);启动期仅在检测到空后端时
 * 发出告警(见 {@link #warnIfNoDistributedBackend()}),仍允许启动(用户可能根本不用 sync)。
 */
@Slf4j
@Component
public class SyncSupport {

    private final List<LockManager> distributedManagers;
    private final RedisProCacheProperties properties;

    /**
     * in-flight single-flight futures:同 key 并发请求共享 leader 的结果。
     * leader 完成后(无论成功/失败)在 finally 中 remove 自身条目。
     */
    private final ConcurrentMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    /**
     * 当前线程已持有 leader 身份的 key 集合 — 用于 future 不可重入场景下的重入检测。
     * chain 内 {@code SyncLockHandler} 嵌套重入 {@code executeSync}(同 key)时,
     * fast-path 直接跑 loader(等价 {@code synchronized} 可重入,且省去二次分布式锁往返)。
     *
     * <p>线程局部,leader finally 中 {@code remove} 以避免泄漏。
     */
    private final ThreadLocal<Set<String>> reentrantKeys = ThreadLocal.withInitial(HashSet::new);

    /**
     * 构造函数.
     *
     * @param lockManagers 锁管理器列表（可能为空，表示无分布式锁后端）
     * @param properties   ResiCache 配置（读取 {@code sync-lock.local-only} 降级开关）
     */
    public SyncSupport(final List<LockManager> lockManagers, final RedisProCacheProperties properties) {
        // 按 getOrder() 降序排序(数值越小优先级越高),构造不可变快照。
        // 用 stream 不改入参 list —— 防御性:调用方可传任意 List(含 List.of 不可变 list),
        // 原实现 {@code lockManagers.sort(...)} 直接排序入参,传入不可变 list 会抛
        // UnsupportedOperationException(顺带修复:原 {@code o1-o2} 减法替换为
        // {@link Integer#compare} 避免理论溢出)。
        this.distributedManagers = lockManagers.stream()
                .sorted((o1, o2) -> Integer.compare(o2.getOrder(), o1.getOrder()))
                .toList();
        this.properties = properties;
        warnIfNoDistributedBackend();
    }

    /**
     * 启动期检测：无分布式锁后端且未显式 local-only 时，发出显眼告警.
     *
     * <p>此时仍允许启动（用户可能不用 sync）；真正的 fail-fast 在运行期
     * {@link #executeSync(String, Supplier, long)}。
     */
    private void warnIfNoDistributedBackend() {
        if (distributedManagers.isEmpty() && !properties.getSyncLock().isLocalOnly()) {
            log.warn("====================================================================\n"
                    + " ResiCache 警告: 未检测到分布式锁后端 (无 RedissonClient → 无 LockManager bean)!\n"
                    + " 任何 sync=true 的缓存操作将在首次未命中时 FAIL-FAST (拒绝静默退化为单 JVM)。\n"
                    + " \n"
                    + " 多实例部署下, 单 JVM synchronized 无法防击穿 —— 这是最坏失败模式。\n"
                    + " \n"
                    + " 选项:\n"
                    + "   1. 引入 Redisson 以获得真正的分布式锁;\n"
                    + "   2. 若确为单实例/测试场景, 显式声明合法降级:\n"
                    + "        resi-cache.sync-lock.local-only: true\n"
                    + "====================================================================");
        }
    }

    /**
     * Path C 后续(WS-1.4) — 健康查询:同步锁是否降级到 local-only。
     * <p>{@code true} = 未显式声明 {@code localOnly=true} 且无分布式锁后端(Redisson 缺失),
     * 任何 sync=true 操作会实际降级为单 JVM {@code synchronized}。多实例部署下不防击穿 —
     * 暴露此信号供 {@code RedisCacheHealthIndicator} 级联到 /actuator/health。
     *
     * @return 是否处于 protection.degraded=local-only 状态
     */
    public boolean isDegraded() {
        return !properties.getSyncLock().isLocalOnly()
                && distributedManagers.isEmpty();
    }

    /**
     * 执行同步操作(single-flight).
     *
     * <p>同 key 并发:leader 持分布式锁跑 loader,follower {@code join} leader 的 future
     * (零重复持锁/零重复回源)。
     * 同线程同 key 重入:fast-path 直接跑 loader(等价 {@code synchronized} 可重入)。
     *
     * @param key            缓存键
     * @param loader         数据加载器(leader 在分布式锁内执行)
     * @param timeoutSeconds 超时时间（秒）—— leader 透传给 {@link LockManager#tryAcquire};
     *                       follower 用作 {@code future.get} 等待上限
     * @param <T>            返回值类型
     * @return leader loader 的结果(follower 共享同一份)
     */
    @SuppressWarnings("unchecked")
    public <T> T executeSync(final String key, final Supplier<T> loader, final long timeoutSeconds) {
        // 重入 fast-path:当前线程已是此 key 的 leader(chain 内 SyncLockHandler 嵌套重入场景)。
        // future 不可重入(否则 leader join 自己 → 死锁);synchronized 原模型天然可重入,此处等价。
        if (reentrantKeys.get().contains(key)) {
            return loader.get();
        }

        // single-flight 选举:putIfAbsent CAS,首个线程成为 leader。
        // 后续线程拿到 leader 已发布的 future,走 follower 路径 join。
        final CompletableFuture<Object> mine = new CompletableFuture<>();
        final CompletableFuture<Object> existing = inFlight.putIfAbsent(key, mine);

        if (existing == null) {
            return runAsLeader(key, loader, timeoutSeconds, mine);
        }
        return runAsFollower(key, existing, timeoutSeconds);
    }

    /**
     * Leader 路径:标记重入 → 持锁跑 loader → complete future.
     *
     * <p>无论成功/失败,leader 都在 finally 中 {@code complete/completeExceptionally} future
     * (避免 follower 永久阻塞)并清理 reentrant / inFlight。
     */
    private <T> T runAsLeader(final String key, final Supplier<T> loader,
                              final long timeoutSeconds, final CompletableFuture<Object> mine) {
        reentrantKeys.get().add(key);
        T value = null;
        RuntimeException failure = null;
        boolean success = false;
        try {
            value = doLeaderWork(key, loader, timeoutSeconds);
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
     * Leader 实际工作:无后端时走 fail-fast/local-only,有后端时持锁跑 loader.
     *
     * @throws InterruptedException 当 {@link LockManager#tryAcquire} 被中断时
     */
    private <T> T doLeaderWork(final String key, final Supplier<T> loader, final long timeoutSeconds)
            throws InterruptedException {
        if (distributedManagers.isEmpty()) {
            // 无分布式锁后端:WS-1.2a fail-fast 或 local-only 显式降级(均不进 LockStack)
            return executeWithoutDistributedBackend(key, loader);
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
     * Follower 路径:join leader 的 future,共享其结果(零重复持锁/零重复回源).
     *
     * <p>失败传播(ADR-0042):leader 异常经 {@code completeExceptionally} 透传给 follower,
     * follower 收到的是 leader 的原始异常(RuntimeException 原样抛;checked 包成 RuntimeException)。
     *
     * <p>超时:{@code timeoutSeconds > 0} 时,follower 最多等待该秒数;{@code <= 0} 且 leader 未完成,
     * 立即失败(对齐原模型 {@code Redisson tryLock(0)} 立即返回语义,follower 不无限等待)。
     */
    @SuppressWarnings("unchecked")
    private <T> T runAsFollower(final String key, final CompletableFuture<Object> leader,
                                final long timeoutSeconds) {
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

    /**
     * 无分布式锁后端时的处理：fail-fast 或显式 local-only 降级.
     *
     * <p>leader 调用此方法时已在 single-flight 选举中胜出,follower 由其 future 兜底,
     * 故 local-only 降级路径直接执行 loader 即享有"leader 串行、follower 共享"的单 JVM 串行语义。
     *
     * @param key    缓存键（用于错误/告警定位）
     * @param loader 数据加载器
     * @param <T>    返回值类型
     * @return local-only 降级时 loader 的结果
     * @throws IllegalStateException 当未声明 local-only 且无分布式锁后端时（fail-fast）
     */
    private <T> T executeWithoutDistributedBackend(final String key, final Supplier<T> loader) {
        if (properties.getSyncLock().isLocalOnly()) {
            // 显式合法降级：单 JVM single-flight(leader 串行跑 loader,follower join future)。
            // WS-1.4 将此告警升级为链级 Observation 事件 protection.degraded=local-only。
            log.warn("protection.degraded=local-only: sync=true 但无分布式锁后端, "
                    + "已按 local-only=true 降级为单 JVM 同步 (key={})", key);
            return loader.get();
        }
        // fail-fast：绝不静默退化为单 JVM。多实例下单 JVM synchronized 无法防击穿，
        // 标榜分布式却单机是最坏失败模式 —— 必须让用户立刻看见。
        throw new IllegalStateException(
                "sync=true 已声明但无分布式锁后端 (无 RedissonClient / LockManager bean)。"
                        + "拒绝静默退化为单 JVM synchronized (多实例下无法防击穿)。"
                        + "请引入 Redisson, 或显式设 resi-cache.sync-lock.local-only=true 接受单实例降级。"
                        + " [key=" + key + "]");
    }

    /**
     * 锁堆栈类，用于管理多个锁的自动关闭.
     */
    private static final class LockStack implements AutoCloseable {

        private final Deque<LockManager.LockHandle> handles = new ConcurrentLinkedDeque<>();

        /**
         * 将锁句柄压入堆栈.
         *
         * @param handle 锁句柄
         */
        void push(final LockManager.LockHandle handle) {
            handles.push(handle);
        }

        /**
         * 获取堆栈中锁的数量.
         *
         * @return 锁数量
         */
        int size() {
            return handles.size();
        }

        /**
         * 关闭所有锁句柄.
         */
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
