package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.observer.ChainDebugLogChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainTimerChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.observer.FiredCounterChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.observer.MDCStampChainObserver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 责任链标准 observer 注册器 — ADR-0047 / C5 收敛.
 *
 * <p>从 {@link CacheHandlerChainFactory#createChain()} 抽取的「注册 4 个标准 observer 到
 * {@link ChainEngine}」逻辑。本类作为 seam:
 * <ul>
 *   <li>统一入口:外部仅需 {@link #registerStandardObservers(ChainEngine, ObjectProvider)}</li>
 *   <li>idempotent 由调用方(单例缓存 miss)保证,本类不持有 flag(无副作用)</li>
 *   <li>新增标准 observer(如未来的 {@code SpanObserver})只改本类 + ChainEngine observer 接口,
 *       {@link CacheHandlerChainFactory} 不感知</li>
 * </ul>
 *
 * <p><b>不可实例化</b>:纯静态工具。
 */
final class ChainObserverRegistration {

    private ChainObserverRegistration() {
        // 工具类,不可实例化
    }

    /**
     * 注册 4 个标准 observer 到 {@link ChainEngine}.
     *
     * <p>注册顺序固定(MDC → DebugLog → Timer → FiredCounter),与原
     * {@link CacheHandlerChainFactory} 内联实现字节级一致。idempotent 性由
     * 调用方(单例缓存 miss pattern)保证,本方法每次调用都会向
     * {@link ChainEngine#addObserver} 追加 — 故调用方必须确保本方法只调一次。
     *
     * <p>关于 registry 缺失:
     * <ul>
     *   <li>MDCStampChainObserver / ChainDebugLogChainObserver — 无 registry 依赖,直接 new</li>
     *   <li>ChainTimerChainObserver / FiredCounterChainObserver — 接受 nullable registry,
     *       内部 lazy 检测,registry 缺失时全 no-op</li>
     * </ul>
     *
     * @param engine                链推进引擎(observer 列表宿主)
     * @param meterRegistryProvider Micrometer registry 提供者(可为 null,内部 null-safe)
     */
    static void registerStandardObservers(
            ChainEngine engine,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // 1. MDC stamp — 必注册(无 registry 依赖)
        engine.addObserver(new MDCStampChainObserver());

        // 2. DEBUG log — 必注册(无 registry 依赖)
        engine.addObserver(new ChainDebugLogChainObserver());

        // 3. Timer — registry 缺失时也注册(observer 内部 lazy 检测);保证 observer 列表
        //    在 registry 可用前后一致,Engine 调度逻辑无需区分
        MeterRegistry registry =
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        engine.addObserver(new ChainTimerChainObserver(registry));

        // 4. Fired counter — 同上,registry 缺失时内部 no-op
        engine.addObserver(new FiredCounterChainObserver(registry));
    }
}