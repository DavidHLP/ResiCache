package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.*;



/**
 * 缓存处理器接口（责任链模式）。
 *
 * <p>职责：处理缓存操作，返回 {@link HandlerResult}（含链控制决策）。
 *
 * <p><b>链结构与推进归属（ADR-0022）</b>：节点顺序由 {@link CacheHandlerChain}
 * 维护为 {@code List<CacheHandler>}，推进完全由 {@link ChainEngine} 基于该列表快照
 * 按 index 驱动。handler 不再持有"下一个处理器"链接 —— 历史的
 * {@code setNext(CacheHandler)} / {@code getNext()} 已删除（消除 ADR-0009 抽 Engine
 * 后残留的 next 指针 × List 快照双轨表示）。接口本身只定义处理契约。
 */
public interface CacheHandler {

    /**
     * 处理缓存操作
     *
     * @param context 缓存上下文
     * @return HandlerResult 包含决策和结果
     */
    HandlerResult handle(CacheContext context);
}
