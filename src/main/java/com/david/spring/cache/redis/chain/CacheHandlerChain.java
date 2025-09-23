package com.david.spring.cache.redis.chain;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueWrapper;

/**
 * 缓存处理器链。
 * <p>
 * 真正的职责链实现，通过头节点启动链式处理。
 * 提供简洁的执行接口和链管理功能。
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class CacheHandlerChain {

    /** 链的头节点 */
    @Nullable
    private final CacheHandler head;

    /** 链中处理器的总数 */
    private final int size;

    /**
     * 执行处理器链。
     * <p>
     * 从头节点开始执行链式处理，直到某个处理器返回HANDLED或BLOCKED，
     * 或者到达链的末尾。
     * </p>
     *
     * @param context 处理上下文
     * @return 最终的缓存值，可能为null
     */
    @Nullable
    public ValueWrapper execute(@Nonnull CacheHandlerContext context) {
        if (head == null) {
            log.debug("Empty handler chain, returning original value");
            return context.valueWrapper();
        }

        try {
            logDebug("Starting handler chain execution with {} handlers", size);
            CacheHandler.HandlerResult result = head.handle(context);

            // 根据处理结果决定返回值
            return switch (result) {
                case HANDLED -> {
                    logDebug("Chain completed with HANDLED result");
                    yield context.getCurrentValue();
                }
                case BLOCKED -> {
                    logDebug("Chain blocked by handler");
                    yield null;
                }
                case CONTINUE -> {
                    logDebug("Chain completed with CONTINUE result");
                    yield context.getCurrentValue();
                }
            };

        } catch (Exception e) {
            log.error("Handler chain execution failed: cache={}, key={}, error={}",
                    context.cacheName(), context.key(), e.getMessage(), e);
            // 发生异常时返回原始值，保证缓存的可用性
            return context.valueWrapper();
        }
    }

    /**
     * 检查链是否为空。
     *
     * @return true表示空链
     */
    public boolean isEmpty() {
        return head == null;
    }

    /**
     * 获取链中处理器的数量。
     *
     * @return 处理器数量
     */
    public int size() {
        return size;
    }

    /**
     * 获取链的头节点。
     *
     * @return 头节点，可能为null
     */
    @Nullable
    public CacheHandler getHead() {
        return head;
    }

    /**
     * 记录调试日志。
     */
    private void logDebug(@Nonnull String message, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug("[HandlerChain] " + message, args);
        }
    }

    /**
     * 创建空链。
     *
     * @return 空的处理器链
     */
    @Nonnull
    public static CacheHandlerChain empty() {
        return new CacheHandlerChain(null, 0);
    }

    /**
     * 创建单个处理器的链。
     *
     * @param handler 单个处理器
     * @return 包含单个处理器的链
     */
    @Nonnull
    public static CacheHandlerChain single(@Nonnull CacheHandler handler) {
        return new CacheHandlerChain(handler, 1);
    }
}