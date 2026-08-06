package io.github.davidhlp.spring.cache.redis.protection.refresh;

/**
 * 提前过期(early-expiration)模块对外暴露的"取消挂起刷新"seam —— 责任链终端处理器
 * {@code chain.handler.ActualCacheHandler} 在 PUT 写路径上需要取消该 key 可能挂起的异步刷新
 * (否则后台刷新会用旧 DB 读覆盖刚写入的新值),这是 refresh 模块唯一需要跨越包边界的能力。
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
 * 这并未引入新的 chain→protection 依赖方向 —— {@code ActualCacheHandler} 此前已直接 import
 * {@code protection.nullvalue.DefaultNullValuePolicy} 与 {@code protection.refresh} 的具体类;
 * 本变更只是把其中一处具体依赖替换为接口,严格更优,不新增反向依赖边。
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
