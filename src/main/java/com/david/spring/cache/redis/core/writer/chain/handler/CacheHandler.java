package com.david.spring.cache.redis.core.writer.chain.handler;

import com.david.spring.cache.redis.core.writer.chain.CacheResult;

/** 缓存处理器接口（责任链模式） */
public interface CacheHandler {
    /**
     * 处理缓存操作
     *
     * @param context 缓存上下文
     * @return 处理结果
     */
    CacheResult handle(CacheContext context);

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
     * 调用下一个处理器
     *
     * @param context 缓存上下文
     * @return 处理结果
     */
    default CacheResult invokeNext(CacheContext context) {
        if (getNext() != null) {
            return getNext().handle(context);
        }
        return CacheResult.success();
    }
}
