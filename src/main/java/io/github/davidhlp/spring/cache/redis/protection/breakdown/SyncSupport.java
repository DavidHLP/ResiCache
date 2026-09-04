package io.github.davidhlp.spring.cache.redis.protection.breakdown;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 通过 in-flight {@link CompletableFuture} 实现 single-flight 同步加载:
 * 同一 key 的并发请求中,只有 leader 线程获取分布式锁并执行 loader,
 * follower 线程共享 leader 的结果(不重复获取分布式锁、不重复回源)。
 *
 * <p><b>single-flight 设计权衡</b>:
 * <ul>
 *   <li><b>吞吐</b>:follower 直接 {@code join} leader 的 future,不串行 acquire JVM
 *       monitor + 分布式锁 + double-check GET。同 key 高并发读 miss 时,N 个 follower 的
 *       O(N × (锁往返 + GET)) 串行开销降为 O(ε)。leader 仍独占分布式锁,击穿语义
 *       (1 个回源)反而更硬。</li>
 *   <li><b>可重入(future 不可重入陷阱)</b>:chain 内 {@code SyncLockHandler} 会嵌套重入
 *       {@code executeSync}(同 key —— {@code RedisProCache.executeSyncLoad} 的 loader 内
 *       {@code super.get} → chain GET → SyncLockHandler 再次进入)。{@link CompletableFuture}
 *       不可重入(leader 重入会 join 自己 → 死锁),故用 {@link ThreadLocal} 标记当前线程
 *       已持有的 key,重入时走 fast-path 直接跑 loader —— 语义等价,且省去二次分布式锁往返。</li>
 *   <li><b>失败传播</b>:leader loader 抛异常 → future {@code completeExceptionally},
 *       所有 follower 一起失败(不独立 double-check 自救)。这符合击穿保护精神(避免 N 个
 *       follower 在 leader 失败后继续打 DB);调用方可自行重试。</li>
 * </ul>
 *
 * <p>single-flight 选举产出的 3 个运行时角色(Reentrant / Leader / Follower)收口于
 * {@link SyncRole} sealed interface。本类只保留<b>选举函数 + orchestrator</b>职责,
 * 角色行为(state + cleanup)由角色自承。
 *
 * <p><b>本类剩余职责</b>:
 * <ol>
 *   <li>启动期 {@link #warnIfNoDistributedBackend()} —— 仅 warn,不 fail-fast</li>
 *   <li>健康查询 {@link #isDegraded()} —— 供 health indicator 消费</li>
 *   <li>{@link #executeSync} 选举 + 委派</li>
 * </ol>
 *
 * <p><b>永不静默降级</b>:当无分布式锁后端(无 RedissonClient → 无 LockManager bean)
 * 时,任何 {@code sync=true} 操作<b>绝不</b>静默退化为单 JVM synchronized(多实例下击穿照旧,
 * 是最坏失败模式)。默认行为是<b>运行期 fail-fast</b>(首次未命中即抛
 * {@link IllegalStateException})。仅当用户显式声明 {@code resi-cache.sync-lock.local-only=true}
 * 时,才接受单 JVM 同步作为合法降级(单实例/测试场景),并发出
 * {@code protection.degraded=local-only} 告警使安全属性可观测。
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
     * @param lockManagers 锁管理器列表(可能为空,表示无分布式锁后端)
     * @param properties   ResiCache 配置(读取 {@code sync-lock.local-only} 降级开关)
     */
    public SyncSupport(final List<LockManager> lockManagers, final RedisProCacheProperties properties) {
        // 按 getOrder() 降序排序(数值越小优先级越高),构造不可变快照。
        // 用 stream 不改入参 list —— 防御性:调用方可传任意 List(含 List.of 不可变 list);
        // 用 {@link Integer#compare} 而非减法,避免理论溢出。
        this.distributedManagers = lockManagers.stream()
                .sorted((o1, o2) -> Integer.compare(o2.getOrder(), o1.getOrder()))
                .toList();
        this.properties = properties;
        warnIfNoDistributedBackend();
    }

    /**
     * 启动期检测:无分布式锁后端且未显式 local-only 时,发出显眼告警.
     *
     * <p>此时仍允许启动(用户可能不用 sync);真正的 fail-fast 在运行期
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
     * 健康查询:同步锁后端是否缺失。{@code true} = 未显式
     * {@code localOnly=true} 且无分布式锁后端(Redisson 缺失);此时 sync=true
     * 首次未命中会 fail-fast。暴露此信号供
     * {@code RedisCacheHealthIndicator} 级联到 /actuator/health。
     *
     * @return 是否处于缺失分布式后端且未显式允许 local-only 的状态
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
     * <p>本方法只做「选举 + 委派」两步 —— 角色的 state + cleanup + run 全部由
     * {@link SyncRole.Reentrant} / {@link SyncRole.Leader} / {@link SyncRole.Follower} 自承。
     *
     * @param key            缓存键
     * @param loader         数据加载器(leader 在分布式锁内执行)
     * @param timeoutSeconds 超时时间(秒)—— leader 透传给 {@link LockManager#tryAcquire};
     *                       follower 用作 {@code future.get} 等待上限
     * @param <T>            返回值类型
     * @return leader loader 的结果(follower 共享同一份)
     */
    public <T> T executeSync(final String key, final Supplier<T> loader, final long timeoutSeconds) {
        return electRole(key, loader, timeoutSeconds).run();
    }

    /**
     * 选举:基于 reentrantKeys + inFlight CAS 决定走哪个角色.
     *
     * <p>本方法不持锁(CAS 无锁);race 条件下多个线程可能各自走 leader 路径(无 distributedManagers
     * 时为 local-only 路径),但 inFlight CAS 严格保证「同一 key 同一时间只有一个 leader 发布
     * future,其他全是 follower join」—— 这是 single-flight 协议的核心不变式。
     *
     * @param key            缓存键
     * @param loader         加载器,透传给 Reentrant / Leader 角色(Follower 不需要)
     * @param timeoutSeconds 透传给角色
     * @return 选出的角色(Reentrant / Leader / Follower 之一)
     */
    private <T> SyncRole<T> electRole(String key, Supplier<T> loader, long timeoutSeconds) {
        // 重入 fast-path:当前线程已是此 key 的 leader(chain 内 SyncLockHandler 嵌套重入场景)。
        if (reentrantKeys.get().contains(key)) {
            return new SyncRole.Reentrant<>(loader);
        }
        // single-flight 选举:putIfAbsent CAS,首个线程成为 leader。
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, mine);
        if (existing == null) {
            return new SyncRole.Leader<>(key, timeoutSeconds, loader, mine,
                    distributedManagers, properties, inFlight, reentrantKeys);
        }
        return new SyncRole.Follower<>(key, existing, timeoutSeconds);
    }
}
