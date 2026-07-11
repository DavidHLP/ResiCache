package io.github.davidhlp.spring.cache.redis.chain.observer;

import io.github.davidhlp.spring.cache.redis.chain.AbstractCacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.ChainEngine;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;

/**
 * 责任链推进与观测的注入点 — ADR-0009 (Chain Engine extraction) D2.
 *
 * <p>本接口把责任链 advance / per-node / around-chain 各阶段的横切关注点
 * 收口到 4 个 default no-op 钩子，让 {@link ChainEngine} 与
 * {@link AbstractCacheHandler} 不再各自承担观测逻辑。Engine 驱动推进时
 * 按下列顺序调用各 observer：
 *
 * <ol>
 *   <li>{@link #onChainStart(CacheContext)} — 链入口 around-hook（MDC stamp / Timer start）;
 *       返回 {@code scopeToken} 用于 onChainEnd 恢复(如调用方原 MDC 值、Timer start nanos)</li>
 *   <li>每节点循环：
 *     <ul>
 *       <li>{@link #beforeNode(CacheHandler, CacheContext)} — per-node 前置</li>
 *       <li>handler.handle(ctx)</li>
 *       <li>{@link #afterNode(CacheHandler, CacheContext, HandlerResult)} — per-node 后置（DEBUG log / fired counter）</li>
 *     </ul>
 *   </li>
 *   <li>{@link #onChainEnd(CacheContext, Object, CacheResult)} — 链出口 around-hook(MDC restore / Timer record);
 *       接收 {@code scopeToken}(即本 observer 在 {@code onChainStart} 返回的值),
 *       Engine 保证 start/end 配对(即使主路径异常也调用 finally 守护)</li>
 * </ol>
 *
 * <p><b>ADR-0061 scope token 机制</b>:onChainStart 返回 {@code Object} 类型的
 * "scope token" 替代原 {@code CacheContext.attributes} 字符串键 map —— observer
 * 可在 token 内携带本调用专属的恢复状态(MDCStamp → previousRequestId,
 * ChainTimer → startNanos),Engine 在 onChainEnd 配对回传。CacheContext 不再
 * 提供 stringly-typed 通用 attributes 袋,observer 状态机完全自承,新 observer
 * 零字符串键漂移风险,Engine 不感知 observer 内部协议。
 *
 * <p>所有钩子默认 no-op;observability 实现(Mdc / Timer / Counter / DebugLog)
 * 各自只 override 关心的钩子,正交组合。{@code aroundChain} 关注点(MDC / Timer)
 * 必须在 {@code onChainStart} 配对,{@code perNode} 关注点(counter / log)只在
 * before/afterNode 触发。WS-1.4 引入 Observation Span 时只需新增
 * {@code SpanObserver implements ChainObserver},Engine 与所有 handler 零修改
 * — 这是本 seam 的核心 leverage 兑现。
 *
 * <p>线程模型:observer 实现必须线程安全(Engine 在多线程环境共享同一 observer 列表),
 * 不要在 observer 内持有 per-call 状态(scopeToken 是 per-call 携带通道)。
 */
public interface ChainObserver {

    /**
     * 链入口 hook。Engine 在 stamp MDC / 启动 Timer 之后、第一次
     * {@code beforeNode} 之前调用。典型实现:MDCStamp / Timer 启动。
     *
     * <p><b>返回值</b>(ADR-0061):本 observer 的 per-call 状态,Engine 在
     * {@link #onChainEnd} 配对回传。无状态 observer 返回 {@code null}。
     * Engine 内部按 observer 注册顺序收集 token,onChainEnd 按相同顺序
     * 回传(逐个 observer 配对,跨 observer 不混淆)。
     *
     * @param context 当前链的缓存上下文
     * @return 本 observer 的 per-call scope token(可为 null)
     */
    default Object onChainStart(CacheContext context) {
        // 默认无状态:返回 null
        return null;
    }

    /**
     * 链出口 hook。Engine 在 post-process 完成后、MDC restore / Timer record
     * 之前调用(与 {@link #onChainStart(CacheContext)} 配对)。典型实现:Timer 记录。
     *
     * <p><b>scopeToken</b>(ADR-0061):即本 observer 在 {@code onChainStart} 返回的
     * 同一引用;Engine 跨 start/end 配对透传(其他 observer 的 token 不会误传)。
     * 无状态 observer 的 token 恒为 {@code null}。
     *
     * @param context    当前链的缓存上下文
     * @param scopeToken 本 observer 在 onChainStart 返回的 per-call 状态(可为 null)
     * @param result     链执行最终结果(post-process 已执行)
     */
    default void onChainEnd(CacheContext context, Object scopeToken, CacheResult result) {
        // 默认 no-op
    }

    /**
     * 节点前置 hook。Engine 在调用 {@code handler.handle(ctx)} 之前调用，
     * 即：{@code beforeNode} → {@code handler.handle(ctx)} → {@code afterNode}。
     * 典型实现：DEBUG log / fired counter 自增。
     *
     * @param handler 即将被求值的 handler
     * @param context 链上下文
     */
    default void beforeNode(CacheHandler handler, CacheContext context) {
        // no-op
    }

    /**
     * 节点后置 hook。Engine 在 {@code handler.handle(ctx)} 返回之后调用，
     * 携带求值结果。Engine 不会在 handler 抛异常时调用本钩子 —— 异常冒泡
     * 由 Engine 的 try/catch 守护处理。
     *
     * @param handler 已被求值的 handler
     * @param context 链上下文
     * @param result  handler.handle(ctx) 的返回值
     */
    default void afterNode(CacheHandler handler, CacheContext context, HandlerResult result) {
        // no-op
    }
}
