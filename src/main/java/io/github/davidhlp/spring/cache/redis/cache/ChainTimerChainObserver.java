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
import org.springframework.core.annotation.Order;

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
 * token，不在 observer 内保存共享的 per-call 状态。registry 由 {@link ResolvedMetrics}
 * 单一决议、永不为 null；metrics 未启用时它是 no-op seam，关闭路径不读时钟、不分配
 * scope token、不注册、不分配、不保留任何 timer —— map 保持为空，节点起点返回 null。
 */
@Order(3) // 执行顺序单一真值源=类级 @Order,见 MDCStampChainObserver 注释
final class ChainTimerChainObserver implements ChainObserver {

    static final String METRIC_NAME = "resicache.chain.execute";

    private final MeterRegistry registry;
    /** 关闭路径唯一判据 —— 构造期从 seam 推导一次,热路径只分支 final 字段。 */
    private final boolean disabled;
    private final ConcurrentMap<TimerKey, Timer> timers = new ConcurrentHashMap<>();

    public ChainTimerChainObserver(MeterRegistry registry) {
        this.registry = registry;
        this.disabled = MetricsWriter.disabled(registry);
    }

    @Override
    public Object onNodeStart(CacheHandler handler, CacheContext context) {
        // 关闭路径不分配 token:Engine 按 index 配对回传 null,onNodeEnd 直接返回,
        // 与 metrics 未启用时的历史行为一致(既不计时,也不读时钟)。
        return disabled ? null : new TimerScope(System.nanoTime());
    }

    @Override
    public void onNodeEnd(CacheHandler handler, CacheContext context,
                          Object scopeToken, HandlerResult result) {
        if (disabled || result == null || scopeToken == null) {
            // 故障节点没有 HandlerResult,不伪造 decision;token 为 null 有两处来源:
            // 本 observer 在关闭 seam 时不分配 token,或本人 onNodeStart 抛异常
            // (Engine 不产生 token)。两种情形都无样本可记录。
            // disabled:关闭路径不构造 TimerKey、不写 map、不分配 NoopTimer。
            return;
        }
        // Engine 按 observer index 严格配对回传,故 token 必然是本人 onNodeStart 返回的
        // TimerScope(见 ChainObserver 的 scope token 机制说明)—— 协议保证的类型,
        // 不做防御性 instanceof 重检。
        TimerScope scope = (TimerScope) scopeToken;
        TimerKey key = new TimerKey(
                CacheHandlerChain.handlerTag(handler),
                result.decision().name(),
                context.getCacheName());
        Timer timer = timers.computeIfAbsent(key, this::registerTimer);
        MetricsWriter.record(timer, System.nanoTime() - scope.startNanos(), TimeUnit.NANOSECONDS);
    }

    private Timer registerTimer(TimerKey key) {
        return MetricsWriter.timer(registry, METRIC_NAME,
                "Time spent invoking one cache protection handler",
                "handler", key.handler(), "decision", key.decision(), "cacheName", key.cacheName());
    }

    private record TimerScope(long startNanos) {
    }

    private record TimerKey(String handler, String decision, String cacheName) {
    }
}
