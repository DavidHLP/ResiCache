package io.github.davidhlp.spring.cache.redis.chain.observer;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChain;
import io.github.davidhlp.spring.cache.redis.chain.ChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.concurrent.ThreadLocalRandom;

/**
 * ChainObserver 的 aroundChain 实现 — 在链入口为本次执行 stamp 唯一 requestId
 * 进 MDC，使一次 GET/PUT 内所有 handler 的 {@code [chain]} DEBUG 行可被同一
 * id 关联；链出口恢复调用方原值（不误清宿主线程其它 MDC key，如 traceId）。
 *
 * <p>替换原 {@code CacheHandlerChain.execute} 的内联 MDC 逻辑（约 30 SLOC），
 * 让 Engine 自身不再持有 MDC 状态。本类对 {@link CacheHandlerChain#MDC_REQUEST_ID_KEY}
 * 的引用保留（常量集中），未来若重命名 MDC key 只需改一处。
 *
 * <p>requestId 生成用 {@link ThreadLocalRandom}（非 SecureRandom）—
 * 缓存热路径每次 GET/PUT 必经，规避熵竞争 / 潜在阻塞；64-bit 随机数对
 * DEBUG 日志关联已足够（碰撞概率可忽略）。无符号十六进制输出避免负值符号扩展噪音。
 *
 * <p>线程安全：MDC 本身是 ThreadLocal，每次 execute 调用在调用方线程内做
 * snapshot/restore 配对，无共享状态。
 */
@Slf4j
public final class MDCStampChainObserver implements ChainObserver {

    @Override
    public void onChainStart(CacheContext context) {
        // snapshot/restore：只动自己的 key，try/finally 在 onChainEnd 恢复调用方原值
        // （不调 MDC.clear() 误清宿主线程其它 MDC，如 traceId）。
        String previousRequestId = MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY);
        // 把"原值"暂存到 context attribute 上 —— 这是 Engine 不知道的细节，
        // 但 observer 自身知道。Engine 在 onChainEnd 时通过 context.getAttribute 读回。
        context.setAttribute(MDC_PREVIOUS_KEY, previousRequestId);
        MDC.put(CacheHandlerChain.MDC_REQUEST_ID_KEY, generateRequestId());
    }

    @Override
    public void onChainEnd(CacheContext context, io.github.davidhlp.spring.cache.redis.chain.CacheResult result) {
        Object previous = context.getAttribute(MDC_PREVIOUS_KEY);
        if (previous == null) {
            MDC.remove(CacheHandlerChain.MDC_REQUEST_ID_KEY);
        } else {
            MDC.put(CacheHandlerChain.MDC_REQUEST_ID_KEY, previous.toString());
        }
        context.removeAttribute(MDC_PREVIOUS_KEY);
    }

    /**
     * 用 {@link ThreadLocalRandom} 而非 {@code UUID.randomUUID()}：
     * {@code execute} 是缓存热路径（每次 GET/PUT 必经），需规避
     * {@code SecureRandom} 的熵竞争 / 潜在阻塞；64-bit 随机数对 DEBUG
     * 日志关联已足够（碰撞概率可忽略）。无符号十六进制输出避免负值符号扩展噪音。
     */
    private static String generateRequestId() {
        return Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 16);
    }

    /** 把"调用方原 requestId"通过 CacheContext attribute 跨 onChainStart/onChainEnd 传递。 */
    private static final String MDC_PREVIOUS_KEY = "__mdc.previousRequestId";
}
