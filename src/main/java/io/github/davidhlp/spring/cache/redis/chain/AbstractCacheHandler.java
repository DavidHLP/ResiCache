package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 抽象缓存处理器 — ADR-0009 (Chain Engine extraction) D3 后的精简形态。
 *
 * <p>链推进（{@code skipRemaining} 短路、decision switch、节点级 DEBUG / fired
 * counter / Timer / MDC stamp、post-process phase）已全部迁出到
 * {@link ChainEngine}。本类只保留：
 *
 * <ul>
 *   <li>语义 counter 装配钩子：{@link #attachMeterRegistry(MeterRegistry)} →
 *       {@link #semanticCounter()}（子类 declare 自身 counter 元数据）</li>
 *   <li>语义 counter helper：{@link #registerCounter} / {@link #safeIncrementSemantic}</li>
 *   <li>handler 钩子：{@link #shouldHandle(CacheContext)} / {@link #doHandle(CacheContext)}</li>
 * </ul>
 *
 * <p>本类不再持有 {@code firedCounter} 字段（迁出至
 * {@code FiredCounterChainObserver}）— 原本由 fired counter 衍生的
 * {@code attachMeterRegistry(...)} 装配协议也精简为只调子类
 * {@code semanticCounter()} 元数据；uniform fired counter 的注册与自增由
 * Engine 在节点前后统一调 observer 完成，对子类透明。
 *
 * <p><b>ADR-0018 后续(WS-1.4) — 语义 counter 模板方法下沉</b>：子类不再
 * override {@code onAttachMetrics(MeterRegistry)} 写"取 registry 调
 * registerCounter 存到本类字段"的样板；改为 override {@link #semanticCounter()}
 * 返回 {@link CounterMetadata}（name + description 不可变记录），基类在
 * {@link #attachMeterRegistry} 阶段从元数据构建并持有唯一 counter 字段，调用
 * {@link #safeIncrementSemantic} null-safe 自增。每 handler 的 counter 名字仍
 * 唯一（语义不合并），仅注册样板收敛到基类。
 *
 * <p><b>handle(ctx) 模板方法默认实现</b>：保留作为基类的"do work"默认实现，
 * 委托给子类钩子 {@code shouldHandle} / {@code doHandle}：
 *
 * <pre>
 *   if (shouldHandle(ctx)) return doHandle(ctx);
 *   else return continueChain();
 * </pre>
 *
 * <p>Engine 不会调本类的 {@code handle(ctx)} 中的推进逻辑（推进已迁出），但会
 * 调用本方法拿 {@link HandlerResult}。Engine 自身的 {@code driveChain} 负责
 * decision switch + 节点间推进。
 *
 * <p><b>不再自行推进</b>：子类 doHandle <strong>不应</strong>再调用
 * {@code getNext().handle(ctx)} 推进链（之前 {@code SyncLockHandler.executeChainInLock}
 * 有此用法，已迁出至 {@code ChainEngine.executeChainFragment}）。本基类提供
 * 链推进完全交给 Engine 的纪律。
 */
@Getter
@Setter
@Slf4j
public abstract class AbstractCacheHandler implements CacheHandler {

    /**
     * 语义 counter 元数据（name + description 不可变记录）。子类通过
     * {@link #semanticCounter()} override 声明自身命名的 counter 元数据；
     * 返回 {@code null} 表示本 handler 不需要语义 counter（基类默认）。
     *
     * <p>深度理由：5 个 protection handler 之前各自 override
     * {@code onAttachMetrics(MeterRegistry)} 写"取 registry 调
     * registerCounter 存到本类字段"5 行样板，字段分散持有 + 5 个独立 null-safe
     * 自增站点。改为 declare 元数据后，基类唯一字段 + 唯一注册点 + 唯一自增
     * helper，5 处样板收敛为 1 处模板方法。
     *
     * @param name        counter 名（如 {@code resicache.handler.ttl.jittered}）
     * @param description counter 描述（Micrometer exposition 字段）
     */
    public record CounterMetadata(String name, String description) {
    }

    /**
     * 语义 counter 字段 — 由 {@link #attachMeterRegistry} 在子类声明
     * {@link #semanticCounter()} 非 null 时从元数据注册；registry 缺失时为 null。
     * 子类不再各自持有本字段（ADR-0018）。
     */
    private Counter semanticCounter;

    /**
     * 工厂建链阶段注入 MeterRegistry（{@code ChainHandlerChainFactory} 在
     * {@code createChain} 中遍历进链 handler 时调用）。registry 非空时：
     * <ol>
     *   <li>子类 override {@link #semanticCounter()} 声明自身语义 counter
     *       元数据（{@link CounterMetadata}）；基类从元数据构建并持有唯一 counter 字段</li>
     *   <li>本基类不再注册 uniform fired counter — 改由
     *       {@code FiredCounterChainObserver} 按进链 handler 类统一注册</li>
     * </ol>
     * registry 缺失或子类未声明元数据时本方法为 no-op。幂等：同名同 tag
     * 重复 register 返回既有实例。
     */
    public void attachMeterRegistry(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        CounterMetadata metadata = semanticCounter();
        if (metadata != null) {
            this.semanticCounter = registerCounter(registry, metadata.name(), metadata.description());
        }
    }

    /**
     * 子类语义 counter 元数据声明。基类 {@link #attachMeterRegistry} 在 registry
     * 非空时调用；返回 {@code null}（默认）表示本 handler 不需要语义 counter。
     * 有语义 counter 的子类 override 返回 {@link CounterMetadata} 即可，counter
     * 字段与 null-safe 自增 helper 由基类统一管理。
     *
     * <p>典型用法（5 个 protection handler 一致形态）：
     * <pre>
     * &#64;Override
     * protected CounterMetadata semanticCounter() {
     *     return new CounterMetadata(
     *         "resicache.handler.ttl.jittered",
     *         "TTL jitter applied (avalanche protection: randomTtl=true variance spread the TTL)");
     * }
     * </pre>
     *
     * <p>本钩子不再承担 uniform fired counter 的注册 — 该职责由
     * {@code FiredCounterChainObserver} 接管，handler 自身零配置。
     */
    protected CounterMetadata semanticCounter() {
        // 默认 no-op；有语义 counter 的子类 override 返回 CounterMetadata
        return null;
    }

    /**
     * 语义 counter 注册 helper（无 tag 版）。handler 的 per-handler 命名 counter
     * 名字已隐含 handler（如 {@code resicache.handler.ttl.jittered}），无需
     * handler tag；与共享名字、需 tag 的 {@code resicache.handler.fired} 区分
     * （后者由 FiredCounterChainObserver 统一注册）。
     */
    protected Counter registerCounter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    /**
     * null-safe 自增语义 counter：基类持有的 {@link #semanticCounter} 在
     * {@link #attachMeterRegistry} 未被调用或子类未声明元数据时为 null，本方法
     * 集中处理 null 情况（与 {@code RedisProCache#safeIncrement} 同模式）。
     *
     * <p>取代原 {@code safeIncrement(Counter)} 多参版本 — 5 个 protection handler
     * 之前各自传自身字段调用，字段被基类接管后本方法无需参数。
     */
    protected void safeIncrementSemantic() {
        if (semanticCounter != null) {
            semanticCounter.increment();
        }
    }

    /**
     * handle 默认实现 — 由 Engine 调用。
     *
     * <p>Engine 已在调用本方法前完成：
     * <ul>
     *   <li>{@code skipRemaining} 短路检测（isSkipRemaining 返 true 时根本不调本方法）</li>
     *   <li>observer.beforeNode（DEBUG / fired counter）</li>
     * </ul>
     * Engine 在本方法返回后做：
     * <ul>
     *   <li>observer.afterNode</li>
     *   <li>decision switch（CONTINUE / SKIP_ALL / TERMINATE）</li>
     *   <li>推进到下一个 handler（CONTINUE）</li>
     * </ul>
     *
     * <p>本方法只做"读 shouldHandle → 调 doHandle 或退化为 continueChain"的最薄
     * 包装。子类不应自行推进链（链推进已由 {@link ChainEngine} 统一驱动）。
     */
    @Override
    public HandlerResult handle(CacheContext context) {
        return shouldHandle(context) ? doHandle(context) : HandlerResult.continueChain();
    }

    /**
     * 判断当前处理器是否应该处理此操作。
     *
     * @param context 缓存上下文
     * @return true 表示应该处理
     */
    protected abstract boolean shouldHandle(CacheContext context);

    /**
     * 执行实际的处理逻辑。
     *
     * <p>返回 {@link HandlerResult} 包含：
     * <ul>
     *   <li>{@code decision}：控制责任链后续执行（CONTINUE / TERMINATE / SKIP_ALL）</li>
     *   <li>{@code result}：处理结果（可选，为 null 时 Engine 退化为 success）</li>
     * </ul>
     *
     * <p>子类 doHandle <strong>不应</strong>自行推进链；链推进已由 {@link ChainEngine}
     * 统一驱动。例外场景（如 {@code SyncLockHandler} 锁内推进剩余链）请改用
     * {@link ChainEngine#executeChainFragment(CacheContext, CacheHandler)}（传 {@code this}，
     * Engine 按 snapshot {@code indexOf} 定位其后继）。
     *
     * @param context 缓存上下文
     * @return HandlerResult 包含决策和结果
     */
    protected abstract HandlerResult doHandle(CacheContext context);
}
