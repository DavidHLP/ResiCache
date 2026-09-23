package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通过 in-flight {@code CompletableFuture} 实现 single-flight 同步加载:
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
 *       {@code super.get} → chain GET → SyncLockHandler 再次进入)。{@code CompletableFuture}
 *       不可重入(leader 重入会 join 自己 → 死锁),故用 {@link ThreadLocal} 标记当前线程
 *       已持有的 key,重入时走 fast-path 直接跑 loader —— 语义等价,且省去二次分布式锁往返。</li>
 *   <li><b>失败传播</b>:leader loader 抛异常 → future {@code completeExceptionally},
 *       所有 follower 一起失败(不独立 double-check 自救)。这符合击穿保护精神(避免 N 个
 *       follower 在 leader 失败后继续打 DB);调用方可自行重试。</li>
 * </ul>
 *
 * <p>本类持有一个 {@link SyncStateAccess} owner 管理 in-flight、重入标记和 local-only 队列。
 * 角色只接收 owner 的窄 publication contract,不直接持有这些 registry。
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
 *
 * <p>本类的 {@link #protectionMode()} 同时是健康侧( {@code RedisCacheHealthIndicator} )
 * 报告 sync 保护状态的唯一推导点。
 */
@Slf4j
@Component
class SyncSupport {

    private final List<LockManager> distributedManagers;
    private final RedisProCacheProperties properties;
    private final SyncStateAccess state = new SyncState();

    /**
     * 构造函数.
     *
     * @param lockManagers 锁管理器列表(可能为空,表示无分布式锁后端)
     * @param properties   ResiCache 配置(读取 {@code sync-lock.local-only} 降级开关)
     */
    public SyncSupport(final List<LockManager> lockManagers, final RedisProCacheProperties properties) {
        // 按 getOrder() 升序排序(数值越小优先级越高),构造不可变快照。
        // 用 stream 不改入参 list —— 防御性:调用方可传任意 List(含 List.of 不可变 list);
        // 用 {@link Integer#compare} 而非减法,避免理论溢出。
        this.distributedManagers = lockManagers.stream()
                .sorted((o1, o2) -> Integer.compare(o1.getOrder(), o2.getOrder()))
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

    /** sync 保护的后端实测模式(构造期即固定:后端列表 + local-only 属性)。 */
    enum ProtectionMode {
        /** 分布式锁后端存在,sync=true 按设计工作。 */
        DISTRIBUTED,
        /** 无后端且显式 {@code local-only=true}:sync=true 降级为单 JVM 同步。 */
        LOCAL_ONLY,
        /** 无后端且未启用 local-only:sync=true 首次未命中即 fail-fast。 */
        FAIL_FAST
    }

    /**
     * 健康查询:sync 保护的实测模式。后端存在性与 {@code local-only} 属性都归本类
     * 所有,消费方(如 {@code RedisCacheHealthIndicator})不重复推导。
     *
     * @return 当前保护模式
     */
    public ProtectionMode protectionMode() {
        if (!distributedManagers.isEmpty()) {
            return ProtectionMode.DISTRIBUTED;
        }
        return properties.getSyncLock().isLocalOnly()
                ? ProtectionMode.LOCAL_ONLY
                : ProtectionMode.FAIL_FAST;
    }

    /**
     * 执行同步操作(single-flight).
     *
     * <p>同 key 并发:leader 持分布式锁跑 loader,follower {@code join} leader 的 future
     * (零重复持锁/零重复回源)。同线程同 key 重入:fast-path 直接跑 loader。
     * 进入本方法的 timeout 已是本次请求的单一 resolved 值。
     *
     * @param key            缓存键
     * @param loader         数据加载器(leader 在分布式锁内执行)
     * @param timeoutSeconds 本次请求已解析的超时时间(秒)
     * @param <T>            返回值类型
     * @return leader loader 的结果(follower 共享同一份)
     */
    public <T> T executeSync(final String key, final Supplier<T> loader, final long timeoutSeconds) {
        return executeSync(key, loader, SyncLockTimeout.Resolved.fromSeconds(timeoutSeconds));
    }

    <T> T executeSync(final String key,
                      final Supplier<T> loader,
                      final SyncLockTimeout.Resolved timeout) {
        return electRole(key, loader, timeout).run();
    }

    /**
     * 执行<b>独占</b>工作 —— 写路径用:只用分布式锁做互斥,不做 single-flight 结果共享。
     *
     * <p>写请求是独立的 {@link SyncRole.Exclusive} case,不会发布 single-flight future。
     * 并发写只互斥、不互相吞并,每个调用都执行自己的工作。
     *
     * @param key            缓存键(锁键)
     * @param work           要执行的工作
     * @param timeoutSeconds 本次请求已解析的获锁超时(秒)
     * @param <T>            返回值类型
     * @return 本次调用自己的执行结果
     */
    public <T> T executeExclusive(final String key, final Supplier<T> work, final long timeoutSeconds) {
        SyncLockTimeout.Resolved timeout = SyncLockTimeout.Resolved.fromSeconds(timeoutSeconds);
        if (state.isReentrant(key)) {
            return work.get();
        }
        return new SyncRole.Exclusive<>(key, timeout, work,
                distributedManagers, properties, state).run();
    }

    /**
     * 选举:state owner 的 publication CAS 决定走哪个角色.
     *
     * <p>本方法不持锁;state owner 保证同一 key 的 publication identity,角色只处理自己的
     * execution path。leader/follower 共享同一已解析 timeout,不在角色内重新解释。
     */
    private <T> SyncRole<T> electRole(String key,
                                      Supplier<T> loader,
                                      SyncLockTimeout.Resolved timeout) {
        // 重入 fast-path:当前线程已是此 key 的 leader(chain 内嵌套重入场景)。
        if (state.isReentrant(key)) {
            return new SyncRole.Reentrant<>(loader);
        }
        SyncRegistration registration = state.publish(key);
        if (registration.leader()) {
            return new SyncRole.Leader<>(key, timeout, loader, registration,
                    distributedManagers, properties, state);
        }
        return new SyncRole.Follower<>(key, registration.future(), timeout);
    }
}
