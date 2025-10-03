package com.david.spring.cache.redis.core.writer.handler;

import lombok.Getter;
import lombok.Setter;

/** 抽象缓存处理器，提供责任链的基础实现 */
@Getter
@Setter
public abstract class AbstractCacheHandler implements CacheHandler {
    /** 下一个处理器 */
    private CacheHandler next;

    @Override
    public void setNext(CacheHandler next) {
        this.next = next;
    }

    @Override
    public CacheHandler getNext() {
        return next;
    }

    /**
     * 判断当前处理器是否应该处理此操作
     *
     * @param context 缓存上下文
     * @return true 表示应该处理
     */
    protected abstract boolean shouldHandle(CacheContext context);

    /**
     * 执行实际的处理逻辑
     *
     * @param context 缓存上下文
     * @return 处理结果
     */
    protected abstract CacheResult doHandle(CacheContext context);

    @Override
    public CacheResult handle(CacheContext context) {
        // 如果上下文标记为跳过剩余处理器，直接返回
        if (context.isSkipRemaining()) {
            return CacheResult.success();
        }

        // 判断是否需要处理
        if (shouldHandle(context)) {
            CacheResult result = doHandle(context);
            // 如果处理失败或明确返回结果，不再继续责任链
            if (!result.isSuccess() || result.getResultBytes() != null || result.isRejectedByBloomFilter()) {
                return result;
            }
        }

        // 继续责任链
        return invokeNext(context);
    }
}
