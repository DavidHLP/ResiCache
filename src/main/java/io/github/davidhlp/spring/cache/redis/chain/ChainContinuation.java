package io.github.davidhlp.spring.cache.redis.chain;

/**
 * 嵌套推进句柄 —— 引擎交给<b>正在执行</b>的 handler 的「推进本节点之后剩余链」能力。
 *
 * <p>典型用途:{@code sync=true} 的击穿保护需要在<b>分布式锁内</b>推进后续 handler
 * (早期刷新 / TTL / 空值 / 实际读写),即 handler 不返回 {@code CONTINUE} 让引擎接管,
 * 而是在自己的临界区内把剩余链跑完,再以 {@code TERMINATE} 结束。
 *
 * <p><b>协议</b>(引擎强制 machine-checkable 部分):
 * <ul>
 *   <li><b>仅当次调用有效</b>:句柄绑定当前 handler 的调用,handler 返回后失效。
 *       不得跨调用保存/传递。</li>
 *   <li><b>至多调用一次</b>:重复调用抛 {@link IllegalStateException} —— 重复推进会让同一批
 *       后继 handler 对同一请求执行两次。</li>
 *   <li><b>同线程</b>:推进后继节点沿用本线程;引擎的节点观测(DEBUG 日志 / fired counter)
 *       按节点触发。</li>
 *   <li><b>不含 around-chain 观测与 post-process</b>:外层 {@code ChainEngine.execute}
 *       唯一负责 MDC stamp / Timer record / 链后置处理,嵌套推进不重复打点。</li>
 *   <li><b>返回值</b>:剩余链的最终结果;当前 handler 已是链尾时返回
 *       {@link CacheResult#success()}。</li>
 * </ul>
 *
 * <p><b>不用默认实现的代价</b>:实现 {@link CacheHandler} 的 handler 若不 override
 * {@link CacheHandler#handle(io.github.davidhlp.spring.cache.redis.chain.model.CacheContext, ChainContinuation)},
 * 本能力对它是 no-op —— 引擎走 {@code handle(context)} 单参契约。
 */
@FunctionalInterface
public interface ChainContinuation {

    /**
     * 推进当前节点之后的剩余链。
     *
     * @return 剩余链的最终结果(链尾调用时返回 {@link CacheResult#success()})
     * @throws IllegalStateException 同一句柄被调用超过一次
     */
    CacheResult advance();
}
