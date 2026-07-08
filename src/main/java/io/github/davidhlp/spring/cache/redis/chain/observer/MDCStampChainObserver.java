package io.github.davidhlp.spring.cache.redis.chain.observer;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChain;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
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
 * <p><b>ADR-0061 scope token 收尾</b>(Round 46):onChainStart 把"调用方原 requestId"
 * 装入 {@link MdcScope} record 返回,onChainEnd 接收该 token 恢复 MDC。完全摆脱
 * 原 {@code CacheContext.attributes} 字符串键 map(原 {@code __mdc.previousRequestId}
 * magic string 已删除),observer 状态机完全自承,CacheContext 不再承担 stringly-typed
 * 通用 attributes 袋。Engine 不感知 token 内部协议 —— 跨 observer 不混淆,
 * 因为 Engine 按 observer 注册 index 配对回传。
 *
 * <p>线程安全：MDC 本身是 ThreadLocal，每次 execute 调用在调用方线程内做
 * snapshot/restore 配对，无共享状态。
 */
@Slf4j
public final class MDCStampChainObserver implements ChainObserver {

    @Override
    public Object onChainStart(CacheContext context) {
        // snapshot/restore：只动自己的 key，try/finally 在 onChainEnd 恢复调用方原值
        // （不调 MDC.clear() 误清宿主线程其它 MDC，如 traceId）。
        String previousRequestId = MDC.get(CacheHandlerChain.MDC_REQUEST_ID_KEY);
        MDC.put(CacheHandlerChain.MDC_REQUEST_ID_KEY, generateRequestId());
        // ADR-0061:把"原值"装入 MdcScope record 返回,Engine 在 onChainEnd 配对回传。
        return new MdcScope(previousRequestId);
    }

    @Override
    public void onChainEnd(CacheContext context, Object scopeToken, CacheResult result) {
        // ADR-0061:scopeToken 即 onChainStart 返回的 MdcScope 实例,无 cast 之 cast
        // —— instanceof 模式匹配恢复 previousRequestId 字段
        if (!(scopeToken instanceof MdcScope scope)) {
            // 防御性:Engine 协议保证 token 类型匹配,理论不可达;失败则不恢复(不污染调用方 MDC)
            return;
        }
        if (scope.previousRequestId() == null) {
            MDC.remove(CacheHandlerChain.MDC_REQUEST_ID_KEY);
        } else {
            MDC.put(CacheHandlerChain.MDC_REQUEST_ID_KEY, scope.previousRequestId());
        }
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

    /**
     * MDC scope token — ADR-0061 引入的 per-call 状态值对象.
     *
     * <p>本 record 由 {@link #onChainStart} 构造,持有调用方在
     * {@code onChainStart} 之前的 MDC requestId 值(可能为 null),由
     * {@link #onChainEnd} 读取并恢复。Engine 不感知 record 内容,仅按
     * observer 注册 index 配对回传。
     *
     * <p>本 record 是 observer 私有(本类嵌套):当前仅 MDCStampChainObserver 一个
     * 消费者,未达提升为顶层类型的必要性(YAGNI)。未来其他 observer 若需类似
     * scope token,各自定义私有 record 即可,跨 observer 不共享 token 协议。
     *
     * @param previousRequestId 调用方预设的 requestId;null 表示"无预设"
     */
    private record MdcScope(String previousRequestId) {
    }
}
