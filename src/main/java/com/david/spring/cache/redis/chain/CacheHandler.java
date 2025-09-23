package com.david.spring.cache.redis.chain;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * 缓存处理器接口。
 * <p>
 * 职责链模式的核心接口，每个处理器负责特定的缓存处理逻辑。
 * 处理器可以选择处理请求、传递给下一个处理器，或阻止请求继续传递。
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
public interface CacheHandler {

    /**
     * 处理缓存请求。
     * <p>
     * 实现者应该根据具体逻辑决定如何处理请求：
     * - CONTINUE: 处理完成，继续传递给下一个处理器
     * - HANDLED: 请求已完全处理，停止链执行
     * - BLOCKED: 阻止请求继续传递（如安全检查失败）
     * </p>
     *
     * @param context 处理上下文，包含所有必要的缓存信息
     * @return 处理结果，指示链的下一步行为
     */
    @Nonnull
    HandlerResult handle(@Nonnull CacheHandlerContext context);

    /**
     * 设置下一个处理器。
     * <p>
     * 用于构建职责链。通常由链构建器调用。
     * </p>
     *
     * @param next 下一个处理器，null表示链的末尾
     */
    void setNext(@Nullable CacheHandler next);

    /**
     * 获取下一个处理器。
     *
     * @return 下一个处理器，null表示链的末尾
     */
    @Nullable
    CacheHandler getNext();

    /**
     * 获取处理器名称。
     * <p>
     * 用于日志记录和调试。应该是简洁的标识符。
     * </p>
     *
     * @return 处理器名称
     */
    @Nonnull
    String getName();

    /**
     * 获取处理器优先级。
     * <p>
     * 数字越小优先级越高。用于链构建时的排序。
     * </p>
     *
     * @return 优先级数值
     */
    default int getOrder() {
        return 100;
    }

    /**
     * 判断处理器是否支持给定的上下文。
     * <p>
     * 用于动态链构建时过滤不适用的处理器。
     * </p>
     *
     * @param context 缓存处理上下文
     * @return true表示支持
     */
    default boolean supports(@Nonnull CacheHandlerContext context) {
        return true;
    }

    /**
     * 处理器结果枚举。
     * <p>
     * 定义处理器处理请求后的下一步行为。
     * </p>
     */
    enum HandlerResult {
        /** 继续执行下一个处理器 */
        CONTINUE,
        /** 请求已处理完成，停止链执行 */
        HANDLED,
        /** 阻止请求继续传递 */
        BLOCKED
    }
}