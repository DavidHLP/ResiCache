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
 *   <li>链链接字段：{@code next} / {@link #getNext()} / {@link #setNext(CacheHandler)}</li>
 *   <li>语义 counter 装配钩子：{@link #attachMeterRegistry(MeterRegistry)} →
 *       {@link #onAttachMetrics(MeterRegistry)}（子类 override 注册自身命名 counter）</li>
 *   <li>语义 counter helper：{@link #registerCounter} / {@link #safeIncrement}</li>
 *   <li>handler 钩子：{@link #shouldHandle(CacheContext)} / {@link #doHandle(CacheContext)}</li>
 * </ul>
 *
 * <p>本类不再持有 {@code firedCounter} 字段（迁出至
 * {@code FiredCounterChainObserver}）— 原本由 fired counter 衍生的
 * {@code attachMeterRegistry(...)} 装配协议也精简为只调子类
 * {@code onAttachMetrics} 钩子；uniform fired counter 的注册与自增由
 * Engine 在节点前后统一调 observer 完成，对子类透明。
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

    /** 下一个处理器（链推进由 ChainEngine 统一驱动，本类仅持链接关系）。 */
    private CacheHandler next;

    /**
     * 工厂建链阶段注入 MeterRegistry（{@code ChainHandlerChainFactory} 在
     * {@code createChain} 中遍历进链 handler 时调用）。registry 非空时：
     * <ol>
     *   <li>子类 override {@link #onAttachMetrics(MeterRegistry)} 注册自身命名
     *       语义 counter（如 {@code ttl.jittered} / {@code null.hit}）</li>
     *   <li>本基类不再注册 uniform fired counter — 改由
     *       {@code FiredCounterChainObserver} 按进链 handler 类统一注册</li>
     * </ol>
     * registry 缺失时本方法为 no-op（不调 onAttachMetrics）。幂等：同名同 tag
     * 重复 register 返回既有实例。
     */
    public void attachMeterRegistry(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        onAttachMetrics(registry);
    }

    /**
     * 子类语义 counter 装配钩子。基类 {@link #attachMeterRegistry} 在 registry
     * 非空时调用，子类 override 以注册自身命名的语义 counter（如
     * {@code resicache.handler.ttl.jittered}）。默认 no-op。
     *
     * <p>本钩子不再承担 uniform fired counter 的注册 — 该职责由
     * {@code FiredCounterChainObserver} 接管，handler 自身零配置。
     */
    protected void onAttachMetrics(MeterRegistry registry) {
        // 默认 no-op；有语义 counter 的子类 override
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
     * null-safe 自增：registry 缺失时 counter 为 null，no-op。集中消除各 handler
     * 的 {@code if (c != null)} 自增样板（与 {@code RedisProCache#safeIncrement} 同模式）。
     */
    protected void safeIncrement(Counter counter) {
        if (counter != null) {
            counter.increment();
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
     * 包装。子类不应再调 {@code getNext().handle(ctx)} 推进链。
     */
    @Override
    public HandlerResult handle(CacheContext context) {
        return shouldHandle(context) ? doHandle(context) : HandlerResult.continueChain();
    }

    @Override
    public CacheHandler getNext() {
        return next;
    }

    @Override
    public void setNext(CacheHandler next) {
        this.next = next;
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
     * <p>子类 doHandle <strong>不应</strong>再调 {@code getNext().handle(ctx)}
     * 推进链；链推进已由 {@link ChainEngine} 统一驱动。例外场景（如
     * {@code SyncLockHandler} 锁内推进）请改用
     * {@link ChainEngine#executeChainFragment(CacheContext, CacheHandler)}。
     *
     * @param context 缓存上下文
     * @return HandlerResult 包含决策和结果
     */
    protected abstract HandlerResult doHandle(CacheContext context);
}
