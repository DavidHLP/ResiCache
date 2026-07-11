package io.github.davidhlp.spring.cache.redis.chain.observer;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ChainObserver 的 perNode 实现 — per-handler uniform {@code resicache.handler.fired}
 * counter（handler tag = 运行时子类 SimpleName；cardinality bounded = handler 类数）。
 *
 * <p>替换原 {@code AbstractCacheHandler#attachMeterRegistry} 的 uniform fired counter
 * 装配逻辑（约 15 SLOC）+ handle 模板里的 {@code if (firedCounter != null) firedCounter.increment()}
 * 样板代码（约 4 SLOC × 5+ handler）。本类把 fired counter 的"装配 + 自增"收口
 * 到单一 observer，{@code AbstractCacheHandler} 退化为只保留子类的语义 counter
 * 注册钩子（{@code onAttachMetrics}）。
 *
 * <p>行为变化：disabled handler 语义 counter 不再注册（修正现存双轨不一致：
 * 原 fired 按进链注册、语义按 bean 存在注册；统一到本 observer 后，fired 与语义
 * counter 都在 handler 进链时统一注册）。registry 缺失时本 observer 全 no-op，
 * 与原 {@code AbstractCacheHandler} 一致。
 *
 * <p>WS-1.4 Span：per-handler span child 可在本类的 {@code afterNode} 内挂载，
 * 零修改 Engine 即可与本 counter 同步打点。
 *
 * <p>线程安全：counter map 用 {@link ConcurrentHashMap}（lazy register 时多个
 * handler 类型竞争同 observer）；{@link Counter#increment()} 自身线程安全。
 */
@Slf4j
public final class FiredCounterChainObserver implements ChainObserver {

    private final MeterRegistry registry;
    /** handler 类 → fired counter；同名同 tag 重复 register 幂等，故 map 仅按 type 持有。 */
    private final ConcurrentMap<Class<? extends CacheHandler>, Counter> firedCounters = new ConcurrentHashMap<>();

    public FiredCounterChainObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object onChainStart(CacheContext context) {
        // Counter 延迟到 afterNode 首次见到 handler 类时再注册（lazy），不在 start 时预热
        // —— onChainStart 拿不到 handler 列表（Engine 设计上不让 observer 接触 handlers 列表）
        // 本 observer 无 per-call 状态(per-handler counter 走 firedCounters map),
        // 返回 null token,onChainEnd 也走默认 no-op。
        return null;
    }

    @Override
    public void afterNode(CacheHandler handler, CacheContext context,
                          io.github.davidhlp.spring.cache.redis.chain.HandlerResult result) {
        if (registry == null) {
            return;
        }
        Counter counter = firedCounters.computeIfAbsent(handler.getClass(), klass ->
                Counter.builder("resicache.handler.fired")
                        .description("Cache protection chain: number of times each handler was evaluated by the engine "
                                + "(per-handler observability; tag handler = runtime subclass simple name)")
                        .tag("handler", klass.getSimpleName())
                        .register(registry));
        counter.increment();
    }

    /** 测试用：暴露当前已注册的 counter 数。 */
    int registeredCounterCount() {
        return firedCounters.size();
    }
}
