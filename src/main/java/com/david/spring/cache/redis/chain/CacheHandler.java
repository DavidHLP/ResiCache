package com.david.spring.cache.redis.chain;

import com.david.spring.cache.redis.chain.context.CacheContext;

/**
 * 缓存处理器接口，用于实现责任链模式
 *
 * @author david
 */
public interface CacheHandler {

    /**
     * 处理缓存操作
     *
     * @param context 缓存上下文
     * @return 处理结果，如果需要继续下一个处理器则返回null
     * @throws Throwable 处理过程中的异常
     */
    Object handle(CacheContext context) throws Throwable;

    /**
     * 设置下一个处理器
     *
     * @param nextHandler 下一个处理器
     */
    void setNext(CacheHandler nextHandler);

    /**
     * 获取下一个处理器
     *
     * @return 下一个处理器
     */
    CacheHandler getNext();

    /**
     * 获取处理器名称
     *
     * @return 处理器名称
     */
    String getName();
}
