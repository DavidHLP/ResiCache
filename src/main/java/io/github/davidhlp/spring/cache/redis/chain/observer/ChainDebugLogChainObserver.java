package io.github.davidhlp.spring.cache.redis.chain.observer;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChain;
import io.github.davidhlp.spring.cache.redis.chain.ChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * ChainObserver 的 perNode 实现 — 每个被引擎求值的 handler 在 afterNode 阶段
 * 输出一行 {@code [chain]} DEBUG 日志，记录 handler / decision / key /
 * requestId，使一次 GET/PUT 的 DEBUG trace 可按 requestId 串联全部 handler 与决策。
 *
 * <p>替换原 {@code AbstractCacheHandler#handle} 的内联
 * {@code log.debug("[chain] handler=... decision=... key=... requestId=...", ...)}
 * 逻辑（约 7 SLOC）。Engine 自身不再持有 DEBUG log 模板，{@code skipRemaining}
 * 短路路径未到达 {@code afterNode}，故不记（与原行为一致）。
 *
 * <p>{@code requestId} 从 MDC 读取 — 该 MDC key 由 {@link MDCStampChainObserver}
 * 在 {@code onChainStart} 写入。本类不感知 MDC 写入逻辑，只读取现成 key。
 * 若 {@code MDCStampChainObserver} 未装配（如单元测试用空 observer 列表）
 * 则 requestId 为 null，日志降级为不含 id 形式（仅影响 DEBUG 可读性，不影响功能）。
 *
 * <p>线程安全：MDC 是 ThreadLocal，本类在调用方线程上读取（Engine 串行调用
 * beforeNode → handler → afterNode），无共享状态。
 */
@Slf4j
public final class ChainDebugLogChainObserver implements ChainObserver {

    @Override
    public void afterNode(CacheHandler handler, CacheContext context, HandlerResult result) {
        log.debug("[chain] handler={} decision={} key={} requestId={}",
                handler.getClass().getSimpleName(),
                result.decision(),
                context.getRedisKey(),
                MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY));
    }
}
