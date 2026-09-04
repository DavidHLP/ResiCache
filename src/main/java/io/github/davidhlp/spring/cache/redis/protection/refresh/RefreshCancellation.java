package io.github.davidhlp.spring.cache.redis.protection.refresh;

/**
 * Refresh 模块向 chain 暴露的最小跨包 cancel seam。
 * {@code ActualCacheHandler} 在 PUT 写路径上取消挂起刷新，避免旧刷新覆盖新值。
 * 该接口不是完整 executor SPI，也不是用户 Bean 替换契约。
 *
 * <p><b>为什么单独建接口(深模块 seam)</b>:
 * <ul>
 *   <li>refresh 模块的全部其余能力({@code submit} / 重试 / 指标 / 清理调度 / 生命周期)只在本模块内
 *       ({@link EarlyExpirationHandler} 同包调用),不跨越包边界,无需出现在 seam 上。</li>
 *   <li>原设计让 {@code ActualCacheHandler} 直接依赖 305 行的 {@link ThreadPoolEarlyExpirationExecutor}
 *       具体类(连带其线程池/重试/指标的全部传递依赖),仅为了调用 1 个 {@code cancel} 方法 ——
 *       浅耦合:把 refresh 的内部实现形状泄漏进 chain。本接口把"取消"这一跨包能力收口为 1 方法,
 *       implementation 全部隐藏在 refresh 模块内 = 小接口 + 大实现 = 深。</li>
 *   <li>两个 adapter 证明这是真 seam 而非假设性 seam:生产 {@link ThreadPoolEarlyExpirationExecutor}
 *       + 测试侧可直接 mock 本接口(无需再 mock 305 行具体类、无需拉起线程池)。</li>
 * </ul>
 *
 * <p><b>deletion test</b>:删掉本接口、让 {@code ActualCacheHandler} 重新依赖具体类 →
 * refresh 实现形状再次泄漏进 chain,测试需重新拖入整个线程池 → 复杂度上升。本 seam 浓缩复杂度。
 *
 * <p><b>依赖方向纪律</b>:本接口由 refresh 模块持有(protection.refresh),被 chain 消费。
 * {@code ActualCacheHandler} 只依赖本接口;提交、重试、清理调度和 shutdown
 * 仍属于 refresh internal executor,不被伪装成 public submit SPI。
 * 该类型是包边界实现细节而非用户 Bean 替换契约。
 */
public interface RefreshCancellation {

    /**
     * 取消与给定键关联的挂起异步刷新任务(若有)。
     *
     * <p>典型调用点:{@code ActualCacheHandler} 在 PUT/PUT_IF_ABSENT 写入新值前调用,
     * 确保一个将要被新值覆盖的 key 不会被一个挂起的、读取旧值的后台刷新任务覆盖。
     * 实现应幂等:键无挂起任务时为空操作。
     *
     * @param redisKey 要取消的挂起刷新任务关联的 Redis 键
     */
    void cancel(String redisKey);
}
