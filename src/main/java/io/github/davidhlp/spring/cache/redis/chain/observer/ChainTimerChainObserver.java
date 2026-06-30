package io.github.davidhlp.spring.cache.redis.chain.observer;

import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.ChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * ChainObserver 的 aroundChain 实现 — 链级 Micrometer Timer，记录
 * 整条 chain.execute 的全生命周期（head handle + post-process + 所有节点）。
 *
 * <p>替换原 {@code CacheHandlerChain.execute} 的内联 Timer 装配 / 记录逻辑
 * （{@code chainExecuteTimer} 字段 + {@code Timer.record(...)} 调用）。Engine
 * 不再持有 Timer 状态。
 *
 * <p>当前无 per-cacheName / per-operation tags（与原实现一致 — 留 WS-1.4 测试套件
 * 扩展时按 cardinality 评估再加）。{@code registry} 缺失时全链 no-op 计时，
 * 行为与原 {@code CacheHandlerChain} 一致。
 *
 * <p>延迟注册策略：{@code onChainStart} 第一次被调用时检查并创建 Timer，
 * 后续复用（同名 register 幂等返回同一实例）。
 *
 * <p>per-call start nanos 存放在 {@link CacheContext} attribute 中（key 为
 * {@link #START_NANOS_ATTR}）— Engine 串行调用 onChainStart / onChainEnd，
 * 同一 context 跨两调用，attribute 作用域自然限定。
 *
 * <p>线程安全：{@code timer} 单写多读，double-checked locking 守护；attribute
 * 通过 context 自带的 ConcurrentHashMap 持锁。
 */
@Slf4j
public final class ChainTimerChainObserver implements ChainObserver {

    /** CacheContext attribute key：本次链执行的 start nanos（onChainStart 写入、onChainEnd 读出）。 */
    public static final String START_NANOS_ATTR = "__chainTimer.startNanos";

    private final MeterRegistry registry;
    /** 首次 onChainStart 时按需创建；registry 缺失时为 null。 */
    private volatile Timer timer;

    public ChainTimerChainObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onChainStart(CacheContext context) {
        if (registry == null) {
            return;
        }
        Timer t = ensureTimer();
        if (t != null) {
            context.setAttribute(START_NANOS_ATTR, System.nanoTime());
        }
    }

    @Override
    public void onChainEnd(CacheContext context, CacheResult result) {
        Timer t = this.timer;
        Object startAttr = context.getAttribute(START_NANOS_ATTR);
        if (t == null || !(startAttr instanceof Long startNanos)) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        t.record(elapsed, TimeUnit.NANOSECONDS);
        context.removeAttribute(START_NANOS_ATTR);
    }

    /**
     * 双重检查锁定初始化 Timer。Micrometer {@code Timer.builder(name).register(registry)}
     * 同名同 tag 重复 register 幂等返回同一实例，无需 DCL 也安全 —— DCL 是为了避免重复
     * builder 调用的微量开销。
     */
    private Timer ensureTimer() {
        Timer t = this.timer;
        if (t == null) {
            synchronized (this) {
                t = this.timer;
                if (t == null) {
                    t = Timer.builder("resicache.chain.execute")
                            .description("Time spent executing the cache protection chain "
                                    + "(full lifecycle: head + post-process + all nodes)")
                            .register(registry);
                    this.timer = t;
                }
            }
        }
        return t;
    }
}
