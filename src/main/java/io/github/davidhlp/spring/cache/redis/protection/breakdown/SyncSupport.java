package io.github.davidhlp.spring.cache.redis.protection.breakdown;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 通过首先协调 JVM 内的线程，然后在可用时升级到分布式锁来处理同步执行。
 *
 * 使用引用计数的 Monitor 来避免过早移除导致的同步问题。
 *
 * <p><b>永不静默降级 (WS-1.2a)</b>：当无分布式锁后端（无 RedissonClient → 无 LockManager bean）
 * 时，任何 {@code sync=true} 操作<b>绝不</b>静默退化为单 JVM synchronized（多实例下击穿照旧，
 * 是最坏失败模式）。默认行为是<b>运行期 fail-fast</b>（首次未命中即抛
 * {@link IllegalStateException}）。仅当用户显式声明 {@code resi-cache.sync-lock.local-only=true}
 * 时，才接受单 JVM 同步作为合法降级（单实例/测试场景），并发出
 * {@code protection.degraded=local-only} 告警使安全属性可观测（WS-1.4 升级为 Observation 事件）。
 *
 * <p>注意：{@code sync=true} 是 per-method 注解属性，启动期不可穷举，故 fail-fast 的精确触发点
 * 在运行期 {@link #executeSync}（即用户确实声明了 sync 且缓存未命中）；启动期仅在检测到空后端时
 * 发出告警（见 {@link #warnIfNoDistributedBackend()}），仍允许启动（用户可能根本不用 sync）。
 */
@Slf4j
@Component
public class SyncSupport {

    private final List<LockManager> distributedManagers;
    private final RedisProCacheProperties properties;
    private final ConcurrentMap<String, MonitorHolder> localMonitors = new ConcurrentHashMap<>();

    /**
     * 构造函数.
     *
     * @param lockManagers 锁管理器列表（可能为空，表示无分布式锁后端）
     * @param properties   ResiCache 配置（读取 {@code sync-lock.local-only} 降级开关）
     */
    public SyncSupport(final List<LockManager> lockManagers, final RedisProCacheProperties properties) {
        lockManagers.sort((o1, o2) -> o2.getOrder() - o1.getOrder());
        this.distributedManagers = List.copyOf(lockManagers);
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
     * 执行同步操作.
     *
     * @param key            缓存键
     * @param loader         数据加载器
     * @param timeoutSeconds 超时时间（秒）
     * @param <T>            返回值类型
     * @return 执行结果
     */
    public <T> T executeSync(final String key, final Supplier<T> loader, final long timeoutSeconds) {
        MonitorHolder holder = acquireMonitor(key);
        synchronized (holder.monitor) {
            try {
                if (distributedManagers.isEmpty()) {
                    return executeWithoutDistributedBackend(key, loader);
                }

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
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while acquiring distributed lock for key: {}", key, e);
                    throw new IllegalStateException(
                            "Thread interrupted while acquiring distributed lock for key: " + key, e);
                }
            } finally {
                releaseMonitor(key, holder);
            }
        }
    }

    /**
     * 无分布式锁后端时的处理：fail-fast 或显式 local-only 降级.
     *
     * <p>调用时已在 {@code synchronized(holder.monitor)} 内，JVM 内线程互斥已由调用方保证，
     * 故 local-only 降级路径直接执行 loader 即享有单 JVM 串行语义。
     *
     * @param key    缓存键（用于错误/告警定位）
     * @param loader 数据加载器
     * @param <T>    返回值类型
     * @return local-only 降级时 loader 的结果
     * @throws IllegalStateException 当未声明 local-only 且无分布式锁后端时（fail-fast）
     */
    private <T> T executeWithoutDistributedBackend(final String key, final Supplier<T> loader) {
        if (properties.getSyncLock().isLocalOnly()) {
            // 显式合法降级：单 JVM synchronized（已由外层 holder.monitor 保证）。
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
     * 获取 Monitor，增加引用计数.
     */
    private MonitorHolder acquireMonitor(final String key) {
        MonitorHolder holder = localMonitors.computeIfAbsent(key, k -> new MonitorHolder());
        holder.refCount.incrementAndGet();
        return holder;
    }

    /**
     * 释放 Monitor，减少引用计数，当计数为 0 时移除。
     * 使用 compute 原子性地完成检查和删除，避免 TOCTOU 竞态条件。
     */
    private void releaseMonitor(final String key, final MonitorHolder holder) {
        localMonitors.compute(key, (k, existingHolder) -> {
            if (existingHolder != holder) {
                // 键已被其他 MonitorHolder 替换，保持现有引用
                return existingHolder;
            }
            if (holder.refCount.decrementAndGet() <= 0) {
                // 引用计数归零，移除该条目
                return null;
            }
            // 仍有引用，保持该条目
            return holder;
        });
    }

    /**
     * 持有 Monitor 对象和引用计数.
     */
    private static final class MonitorHolder {
        final Object monitor = new Object();
        final AtomicInteger refCount = new AtomicInteger(0);
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
