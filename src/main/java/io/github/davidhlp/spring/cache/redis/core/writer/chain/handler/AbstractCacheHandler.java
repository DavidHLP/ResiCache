package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;

import lombok.Getter;
import lombok.Setter;

/**
 * 抽象缓存处理器，提供责任链的基础实现
 *
 * <p>执行流程：
 * <ol>
 *   <li>检查是否已被标记跳过</li>
 *   <li>调用 shouldHandle() 判断是否需要处理</li>
 *   <li>如果需要处理，调用 doHandle() 并根据返回的 HandlerResult 决定后续流程</li>
 *   <li>如果不需要处理，继续执行下一个 Handler</li>
 * </ol>
 *
 * <p>修复说明：
 * <ul>
 *   <li>handle() 方法现在返回 HandlerResult 而非 CacheResult，保持接口一致性</li>
 *   <li>简化责任链控制逻辑，移除手动调用 executeRestOfChain() 的模式</li>
 *   <li>统一通过 HandlerResult.decision() 控制链的执行</li>
 * </ul>
 */
@Getter
@Setter
abstract class AbstractCacheHandler implements CacheHandler {

    /** 下一个处理器 */
    private CacheHandler next;

    @Override
    public CacheHandler getNext() {
        return next;
    }

    @Override
    public void setNext(CacheHandler next) {
        this.next = next;
    }

    /**
     * 处理缓存操作（返回 HandlerResult，与接口定义一致）
     *
     * <p>模板方法，定义处理流程：
     * <ol>
     *   <li>如果上下文标记为跳过剩余处理器，返回 continueWith(success)</li>
     *   <li>判断是否需要处理</li>
     *   <li>如果需要处理，执行处理逻辑并根据决策继续或终止</li>
     *   <li>如果不需要处理，继续责任链</li>
     * </ol>
     *
     * @param context 缓存上下文
     * @return HandlerResult 包含决策和结果
     */
    @Override
    public HandlerResult handle(CacheContext context) {
        // 检查是否已被标记跳过
        if (context.isSkipRemaining()) {
            return HandlerResult.continueWith(CacheResult.success());
        }

        // 判断是否需要处理
        if (shouldHandle(context)) {
            return doHandle(context);
        }

        // 当前 Handler 不处理，继续下一个
        if (getNext() != null) {
            return getNext().handle(context);
        }

        // 链尾，返回成功
        return HandlerResult.continueWith(CacheResult.success());
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
     * <p>返回 HandlerResult 包含：
     * <ul>
     *   <li>decision: 控制责任链后续执行（CONTINUE/TERMINATE/SKIP_ALL）</li>
     *   <li>result: 处理结果（可选，为 null 时继续链）</li>
     * </ul>
     *
     * @param context 缓存上下文
     * @return HandlerResult 包含决策和结果
     */
    protected abstract HandlerResult doHandle(CacheContext context);
}
