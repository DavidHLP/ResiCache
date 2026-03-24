package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;

/** 
 * 缓存处理器接口（责任链模式）
 * 
 * 职责：
 * 1. 处理缓存操作
 * 2. 返回明确的处理结果和链控制决策
 * 3. 管理下一个处理器的链接
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

    /**
     * 根据决策执行后续处理
     * 
     * @param currentResult 当前处理结果
     * @param context 缓存上下文
     * @return 最终结果
     */
    default CacheResult invokeNext(HandlerResult currentResult, CacheContext context) {
        return switch (currentResult.decision()) {
            case CONTINUE -> {
                // 继续执行下一个 Handler
                if (getNext() != null) {
                    HandlerResult nextResult = getNext().handle(context);
                    // 传播 SKIP_ALL 决策
                    if (nextResult.shouldSkipAll()) {
                        context.markSkipRemaining();
                    }
                    yield nextResult.result() != null ? nextResult.result() : CacheResult.success();
                }
                // 没有下一个 Handler，返回当前结果或成功
                yield currentResult.result() != null ? currentResult.result() : CacheResult.success();
            }
            case TERMINATE -> {
                // 终止责任链
                yield currentResult.result() != null ? currentResult.result() : CacheResult.success();
            }
            case SKIP_ALL -> {
                // 标记跳过并返回
                context.markSkipRemaining();
                yield currentResult.result() != null ? currentResult.result() : CacheResult.success();
            }
        };
    }
    
    /**
     * 执行后续责任链（辅助方法，用于需要后处理的 Handler）
     *
     * @param context 缓存上下文
     * @return HandlerResult 后续链的处理结果
     */
    default HandlerResult executeRestOfChain(CacheContext context) {
        if (getNext() != null) {
            return getNext().handle(context);
        }
        return HandlerResult.continueWith(CacheResult.success());
    }
}
