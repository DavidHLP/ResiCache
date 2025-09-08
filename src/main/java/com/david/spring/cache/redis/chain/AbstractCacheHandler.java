package com.david.spring.cache.redis.chain;

import com.david.spring.cache.redis.chain.context.CacheContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 抽象缓存处理器基类
 *
 * @author david
 */
@Slf4j
public abstract class AbstractCacheHandler implements CacheHandler {

    /** 下一个处理器 */
    protected CacheHandler nextHandler;

    @Override
    public void setNext(CacheHandler nextHandler) {
        this.nextHandler = nextHandler;
    }

    @Override
    public CacheHandler getNext() {
        return this.nextHandler;
    }

    @Override
    public Object handle(CacheContext context) throws Throwable {
        log.debug("处理器 {} 开始处理缓存操作", getName());

        try {
            // 执行具体地处理逻辑
            Object result = doHandle(context);

            // 如果处理完成（context标记为已处理）则直接返回，否则继续下一个处理器
            if (context.isProcessed()) {
                log.debug("处理器 {} 处理完成", getName());
                return result;
            }

            // 继续下一个处理器
            if (nextHandler != null) {
                log.debug("处理器 {} 将处理权交给下一个处理器 {}", getName(), nextHandler.getName());
                return nextHandler.handle(context);
            }

            return null;

        } catch (Exception e) {
            log.error("处理器 {} 处理缓存操作时发生异常", getName(), e);
            context.setException(e);
            throw e;
        }
    }

    /**
     * 具体地处理逻辑，由子类实现
     *
     * @param context 缓存上下文
     * @return 处理结果
     * @throws Throwable 处理过程中的异常
     */
    protected abstract Object doHandle(CacheContext context) throws Throwable;
}
