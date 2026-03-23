package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;

/**
 * 缓存责任链后置处理器接口
 *
 * <p>实现此接口的 Handler 将在责任链执行完成后收到回调，
 * 用于执行需要在主链完成后才能进行的操作。
 *
 * <p>典型使用场景：
 * <ul>
 *   <li>PUT 操作成功后更新辅助数据结构（布隆过滤器、索引等）</li>
 *   <li>记录审计日志或统计信息</li>
 *   <li>发布缓存事件通知</li>
 * </ul>
 *
 * <p>执行顺序：
 * <ul>
 *   <li>后置处理器按责任链中的顺序执行</li>
 *   <li>只有实现了此接口的 Handler 会收到回调</li>
 * </ul>
 *
 * @see CacheHandler
 * @see CacheContext
 * @see CacheResult
 */
public interface PostProcessHandler {

    /**
     * 后置处理回调
     *
     * <p>在责任链所有 Handler 执行完成后调用。
     *
     * @param context 缓存上下文
     * @param result 责任链执行结果
     */
    void afterChainExecution(CacheContext context, CacheResult result);

    /**
     * 判断是否需要执行后置处理
     *
     * <p>默认实现总是返回 true。子类可以重写以支持条件执行，
     * 例如只在特定操作类型或结果状态下执行后置处理。
     *
     * @param context 缓存上下文
     * @return true 表示需要执行后置处理
     */
    default boolean requiresPostProcess(CacheContext context) {
        return true;
    }
}