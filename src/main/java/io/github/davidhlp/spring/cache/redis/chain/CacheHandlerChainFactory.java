package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.*;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 缓存处理器责任链工厂 — ADR-0009 后的精简形态。
 *
 * <p>原职责（自动发现 / 排序 / 过滤 / 构建 / 装配 metric）保持不变；本类的
 * 新增职责是按需注册 {@link ChainObserver} 到 {@link ChainEngine}：
 *
 * <ol>
 *   <li>{@link MDCStampChainObserver} — 无 registry 依赖，必注册</li>
 *   <li>{@link ChainDebugLogChainObserver} — 无 registry 依赖，必注册</li>
 *   <li>{@link ChainTimerChainObserver} — registry 缺失时全 no-op 计时，仍注册（懒初始化）</li>
 *   <li>{@link FiredCounterChainObserver} — registry 缺失时全 no-op，仍注册</li>
 * </ol>
 *
 * <p>observer 装配时机：首次 {@link #createChain} 调时。ChainHandlerChain
 * 自身不再持有 metric 状态，所有 per-handler / per-chain 观测收口到 Engine 的
 * observer 列表。
 *
 * <p>设计改进：
 * <ul>
 *   <li>原设计：CacheHandlerChain 内联 Timer + MDC，AbstractCacheHandler 内联
 *       fired counter 装配，观测逻辑散在 2 个类 4 处</li>
 *   <li>新设计：观测逻辑全部抽到 {@link ChainEngine} 单一 seam，由本工厂统一
 *       装配 4 个 observer，Engine / handler 子类零感知</li>
 *   <li>WS-1.4 OTel/Span 升级：新增 {@code SpanObserver} 即可，Engine / handler
 *       子类零修改 — 这是 ADR-0009 D1+D2 的 leverage 兑现</li>
 * </ul>
 */
@Slf4j
@Component
public class CacheHandlerChainFactory {

    /** 自动注入所有 CacheHandler 实现 */
    private final List<CacheHandler> handlers;

    /** 配置属性 */
    private final RedisProCacheProperties properties;

    /** MeterRegistry 注入（链级 Timer + per-handler fired counter 依赖） */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** 推进引擎 — 由 Spring 注入，工厂首次 createChain 时注册 observer 并装配链。 */
    private final ChainEngine engine;

    /**
     * 防护纵深 handler 的执行顺序枚举（用于 {@code protection.enabled=false} 时派生 disableName）。
     * 不含 TTL — TtlHandler 兼担基础 TTL 计算，属于不可禁用的基础缓存契约（禁用会导致永久缓存）。
     * 从枚举派生而非硬编码字符串，保证短路逻辑与 handler 自报家门同源。
     */
    private static final List<HandlerOrder> PROTECTION_HANDLER_ORDERS = List.of(
            HandlerOrder.BLOOM_FILTER,
            HandlerOrder.SYNC_LOCK,
            HandlerOrder.EARLY_EXPIRATION,
            HandlerOrder.NULL_VALUE);

    public CacheHandlerChainFactory(List<CacheHandler> handlers,
                                 RedisProCacheProperties properties,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider,
                                 ChainEngine engine) {
        this.handlers = handlers;
        this.properties = properties;
        this.meterRegistryProvider = meterRegistryProvider;
        this.engine = engine;
    }

    /** 缓存的责任链实例（单例，避免 handler next 指针被并发修改） */
    private volatile CacheHandlerChain cachedChain;

    /**
     * 创建或获取责任链（单例模式，确保 handler next 指针不被并发修改）。
     *
     * <p>首次调用时：
     * <ol>
     *   <li>注册 4 个 ChainObserver 到 Engine（MDC / DebugLog / Timer / FiredCounter）</li>
     *   <li>按 {@code @HandlerPriority} 排序 + 过滤禁用 + 构建链 + 注入 registry</li>
     *   <li>委派给 {@link CacheHandlerChain}，后者把链快照同步到 Engine</li>
     * </ol>
     *
     * @return 配置好的责任链
     */
    public CacheHandlerChain createChain() {
        if (cachedChain != null) {
            return cachedChain;
        }

        synchronized (this) {
            if (cachedChain != null) {
                return cachedChain;
            }

            // 1) 装配 observer — 委派 seam 类;用户可通过 @Bean ChainEngine 顶替默认
            //    ChainEngine 并加自定义 observer（未来 @Bean ObjectProvider<ChainObserver>）。
            ChainObserverRegistration.registerStandardObservers(engine, meterRegistryProvider);

            // 2) 构建链
            CacheHandlerChain chain = new CacheHandlerChain();
            chain.setEngine(engine);

            // guide §223b:为每个 enabled AbstractCacheHandler 注入 registry
            MeterRegistry registry =
                    meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();

            // 3) 收集禁用集合 — 用户自定义 disabled + 总开关 + per-mechanism 覆盖
            // null-safe:测试用 mock/stub 的 properties 可能不设 protection,默认视为开启
            Set<String> disabled = new HashSet<>(properties.getDisabledHandlers());
            ChainProtectionToggleResolver.resolveDisabled(properties, disabled);

            // 按 @HandlerPriority 注解排序
            List<CacheHandler> sortedHandlers = handlers.stream()
                .sorted(Comparator.comparingInt(this::getOrder))
                .toList();

            // 添加到链，过滤禁用的 Handler
            for (CacheHandler handler : sortedHandlers) {
                String handlerName = getHandlerDisableName(handler);

                if (disabled.contains(handlerName)) {
                    log.info("Handler disabled by configuration: {}", handler.getClass().getSimpleName());
                    continue;
                }

                chain.addHandler(handler);
                if (registry != null && handler instanceof AbstractCacheHandler ach) {
                    ach.attachMeterRegistry(registry);
                }
                log.debug("Added handler to chain: {} (order={})",
                          handler.getClass().getSimpleName(),
                          getOrder(handler));
            }

            log.info("Handler chain created with {} handlers: {}",
                     chain.size(), chain.getHandlerNames());

            cachedChain = chain;
            return cachedChain;
        }
    }

    /**
     * 注册 4 个标准 observer 到 Engine — 仅执行一次（{@code registered} flag 守护）。
     * 多次 createChain 不会重复注册。
     *
     * <p><b>ADR-0047 / C5 收敛</b>:本方法已迁出至 {@link ChainObserverRegistration}
     * package-private seam 类。本工厂仅委派:
     * <pre>
     *   ChainObserverRegistration.registerStandardObservers(engine, meterRegistryProvider);
     * </pre>
     */
    private void registerObserversOnce(@SuppressWarnings("unused") ChainEngine engine) {
        // 占位 — 实际逻辑已迁出。本方法保留仅为最小化本轮 diff(已被 createChain
        // 内联调用替换),后续若无需保留可整段删除。参见 ADR-0047。
    }

    /**
     * 获取 Handler 的禁用配置名称.
     *
     * <p>优先从 {@code @HandlerPriority} 注解关联的 {@link HandlerOrder} 反查
     * {@link HandlerOrder#getDisableName()}(单一事实源),使 handler 类重命名不影响
     * 配置禁用语义。未标注注解的 handler 回退到类名派生(kebab-case)以保持兼容。
     */
    private String getHandlerDisableName(CacheHandler handler) {
        HandlerPriority annotation = handler.getClass().getAnnotation(HandlerPriority.class);
        if (annotation != null) {
            return annotation.value().getDisableName();
        }
        String className = handler.getClass().getSimpleName();
        return className.replace("Handler", "")
                        .replaceAll("([a-z])([A-Z])", "$1-$2")  // camelCase to kebab-case
                        .toLowerCase();
    }

    /**
     * 获取 Handler 的执行顺序
     *
     * @param handler Handler 实例
     * @return 顺序值，未标注则返回 Integer.MAX_VALUE
     */
    private int getOrder(CacheHandler handler) {
        HandlerPriority annotation = handler.getClass().getAnnotation(HandlerPriority.class);
        return annotation != null ? annotation.value().getOrder() : Integer.MAX_VALUE;
    }
}
