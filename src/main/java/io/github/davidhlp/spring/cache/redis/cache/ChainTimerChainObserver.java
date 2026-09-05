package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 责任链节点级 Micrometer Timer。
 *
 * <p>每次 {@code handler.handle(context)} 调用由 Engine 的 token 化
 * {@code onNodeStart/onNodeEnd} 钩子配对计时，并以 {@code handler}、
 * {@code decision}、{@code cacheName} 三个有界维度注册
 * {@code resicache.chain.execute}。故障节点没有 {@link HandlerResult}，不伪造
 * decision，也不记录样本；Engine 仍在 finally 中回收其 scope token。
 *
 * <p>禁止把 redisKey、异常消息或用户动态 ID 用作 tag。Timer 缓存的 key 只包含
 * handler 类型、三值 decision 与应用配置的 cacheName。
 *
 * <p>线程安全：Timer map 支持并发注册；{@link TimerScope} 是单次节点调用的不可变
 * token，不在 observer 内保存共享的 per-call 状态。registry 缺失时全程 no-op。
 */
final class ChainTimerChainObserver implements ChainObserver {

    static final String METRIC_NAME = "resicache.chain.execute";

    private final MeterRegistry registry;
    private final ConcurrentMap<TimerKey, Timer> timers = new ConcurrentHashMap<>();

    public ChainTimerChainObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object onNodeStart(CacheHandler handler, CacheContext context) {
        return registry == null ? null : new TimerScope(System.nanoTime());
    }

    @Override
    public void onNodeEnd(CacheHandler handler, CacheContext context,
                          Object scopeToken, HandlerResult result) {
        if (registry == null || result == null || !(scopeToken instanceof TimerScope scope)) {
            return;
        }
        TimerKey key = new TimerKey(
                handler.getClass().getSimpleName(),
                result.decision().name(),
                context.getCacheName());
        Timer timer = timers.computeIfAbsent(key, this::registerTimer);
        timer.record(System.nanoTime() - scope.startNanos(), TimeUnit.NANOSECONDS);
    }

    private Timer registerTimer(TimerKey key) {
        return Timer.builder(METRIC_NAME)
                .description("Time spent invoking one cache protection handler")
                .tag("handler", key.handler())
                .tag("decision", key.decision())
                .tag("cacheName", key.cacheName())
                .register(registry);
    }

    private record TimerScope(long startNanos) {
    }

    private record TimerKey(String handler, String decision, String cacheName) {
    }
}
