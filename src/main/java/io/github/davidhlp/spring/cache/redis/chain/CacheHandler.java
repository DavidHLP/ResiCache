package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.*;



/**
 * 缓存处理器接口（责任链模式）
 *
 * 职责：
 * 1. 处理缓存操作，返回 {@link HandlerResult}（含链控制决策）
 * 2. 管理下一个处理器的链接
 *
 * <p>链推进逻辑由 {@link AbstractCacheHandler#handle(CacheContext)} 单一引擎统一实现；
 * 接口本身只定义契约，不再自带并行的推进 default 方法
 * （历史的 {@code invokeNext} / {@code executeRestOfChain} 已作为 dead code 移除）。
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
     * 设置下一个处理器
     *
     * @param next 下一个处理器
     */
    void setNext(CacheHandler next);

    /**
     * 获取下一个处理器
     *
     * @return 下一个处理器
     */
    CacheHandler getNext();
}
