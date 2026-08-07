package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.*;



/**
 * 缓存处理器接口（责任链模式）。
 *
 * <p>职责：处理缓存操作，返回 {@link HandlerResult}（含链控制决策）。
 *
 * <p><b>链结构与推进归属</b>：节点顺序由 {@link CacheHandlerChain}
 * 维护为 {@code List<CacheHandler>}，推进完全由 {@link ChainEngine} 基于该列表快照
 * 按 index 驱动。handler 不持有"下一个处理器"链接。接口本身只定义处理契约。
 *
 * <p><b>Post-process 钩子</b>：{@link #requiresPostProcess(CacheContext)} /
 * {@link #afterChainExecution(CacheContext, CacheResult)} 作 default no-op 折回本接口。
 * Handler 通过 override {@code requiresPostProcess} 返回 {@code true} 声明参与
 * post-process；不 override 则不参与。走类型化的 {@code requiresPostProcess} hook,
 * 无需 seam 边界 {@code instanceof} type check。
 */
public interface CacheHandler {

    /**
     * 处理缓存操作
     *
     * @param context 缓存上下文
     * @return HandlerResult 包含决策和结果
     */
    HandlerResult handle(CacheContext context);

    /**
     * 是否参与 post-process — 隐式 opt-in 由本方法的 override 表达。
     *
     * <p>默认 {@code false}：多数 handler 不参与 post-process。需要 post-process 的
     * handler override 此方法返回 {@code true}(通常按 {@link CacheContext#getOperation()}
     * 条件化决策)。
     *
     * @param context 缓存上下文
     * @return true 表示本 handler 在链主路径完成后需要回调 {@link #afterChainExecution}
     */
    default boolean requiresPostProcess(CacheContext context) {
        return false;
    }

    /**
     * 后置处理回调。
     *
     * <p>仅当 {@link #requiresPostProcess(CacheContext)} 返回 {@code true} 时
     * {@link ChainEngine} 在链主路径完成后调用本方法。失败由 Engine try/catch
     * 隔离,不污染主链。
     *
     * <p>典型使用：布隆过滤器后置回填、审计日志、缓存事件通知。
     *
     * @param context 缓存上下文
     * @param result 主链执行结果
     */
    default void afterChainExecution(CacheContext context, CacheResult result) {
        // no-op
    }
}
