package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.*;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainDebugLogChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainTimerChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.observer.FiredCounterChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.observer.MDCStampChainObserver;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

/**
 * 缓存处理器责任链工厂。
 *
 * <p>职责:自动发现 / 排序 / 过滤 / 构建 / 装配 metric + 按需注册 {@link ChainObserver}
 * 到 {@link ChainEngine}：
 *
 * <ol>
 *   <li>{@link MDCStampChainObserver} — 无 registry 依赖，必注册</li>
 *   <li>{@link ChainDebugLogChainObserver} — 无 registry 依赖，必注册</li>
 *   <li>{@link ChainTimerChainObserver} — registry 缺失时全 no-op，仍注册</li>
 *   <li>{@link FiredCounterChainObserver} — registry 缺失时全 no-op，仍注册</li>
 * </ol>
 *
 * <p>observer 装配时机：首次 {@link #createChain} 调时。ChainHandlerChain
 * 自身不持有 metric 状态，所有 per-handler / per-chain 观测收口到 Engine 的
 * observer 列表。Timer 在节点 around-hook 中记录 handler + decision + cacheName。
 *
 * <p>设计要点：
 * <ul>
 *   <li>观测逻辑全部在 {@link ChainEngine} 单一 seam，由本工厂统一装配 4 个
 *       observer，Engine / handler 子类零感知</li>
 *   <li>新增观测维度(如 OTel/Span)只需新增 {@code SpanObserver},Engine / handler
 *       子类零修改</li>
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
     * 4 个可禁用防护机制 toggle 单一事实源 — 新增第 5 个机制时仅追加一行。
     *
     * <p>注意:不含 TTL — {@code TtlHandler} 兼担基础 TTL 计算,禁用会导致
     * {@code ActualCacheHandler} 写入无 TTL 永久缓存(数据陈旧 + 内存泄漏)。
     *
     * <p>每条 toggle 三要素:order 字段(既是短路枚举又是 disableName 派生源),
     * getter 字段(per-mechanism 覆盖读取,null = 继承 enabled),
     * configPath 字段(kebab-case 路径段,仅用于日志)。
     */
    private static final List<Toggle> PROTECTION_TOGGLES = List.of(
            new Toggle(HandlerOrder.BLOOM_FILTER,
                    RedisProCacheProperties.ProtectionProperties::getBloomFilterEnabled,
                    "bloom-filter"),
            new Toggle(HandlerOrder.SYNC_LOCK,
                    RedisProCacheProperties.ProtectionProperties::getSyncLockEnabled,
                    "sync-lock"),
            new Toggle(HandlerOrder.EARLY_EXPIRATION,
                    RedisProCacheProperties.ProtectionProperties::getEarlyExpirationEnabled,
                    "early-expiration"),
            new Toggle(HandlerOrder.NULL_VALUE,
                    RedisProCacheProperties.ProtectionProperties::getNullValueEnabled,
                    "null-value"));

    /**
     * 单条 protection 机制 toggle 描述。
     *
     * @param order      防护机制对应的 {@link HandlerOrder}
     * @param getter     从 {@link RedisProCacheProperties.ProtectionProperties} 读取
     *                   Boolean 字段(null = 继承 enabled,非 null = 单独覆盖)
     * @param configPath 配置文件中的 kebab-case 路径段(用于日志)
     */
    private record Toggle(
            HandlerOrder order,
            Function<RedisProCacheProperties.ProtectionProperties, Boolean> getter,
            String configPath) {
    }

    public CacheHandlerChainFactory(List<CacheHandler> handlers,
                                 RedisProCacheProperties properties,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider,
                                 ChainEngine engine) {
        this.handlers = handlers;
        this.properties = properties;
        this.meterRegistryProvider = meterRegistryProvider;
        this.engine = engine;
    }

    /** 缓存的责任链实例（单例，避免 handler 列表被并发重建） */
    private volatile CacheHandlerChain cachedChain;

    /**
     * 创建或获取责任链（单例模式，确保 observer 注册与链构建仅执行一次）。
     *
     * <p>首次调用时：
     * <ol>
     *   <li>注册 4 个 ChainObserver 到 Engine（MDC / DebugLog / Timer / FiredCounter）</li>
     *   <li>按 {@code @HandlerPriority} 排序 + 过滤禁用 + 构建链 + 注入 registry</li>
     *   <li>委派给 {@link CacheHandlerChain}</li>
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

            // 1) 装配 observer（固定顺序:MDC → DebugLog → Timer → FiredCounter）。
            //    idempotent 由本方法的单例缓存 miss pattern 保证,首次 miss 后不会再进本块。
            registerStandardObservers();

            // 2) 构建链
            CacheHandlerChain chain = new CacheHandlerChain();
            chain.setEngine(engine);

            // guide §223b:为每个 enabled AbstractCacheHandler 注入 registry
            MeterRegistry registry =
                    meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();

            // 3) 收集禁用集合 — 用户自定义 disabled + 总开关 + per-mechanism 覆盖
            // null-safe:测试用 mock/stub 的 properties 可能不设 protection,默认视为开启
            Set<String> disabled = new HashSet<>(properties.getDisabledHandlers());
            resolveProtectionDisabled(properties, disabled);

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
     * 注册 4 个标准 observer 到 Engine。
     *
     * <p>注册顺序固定(MDC → DebugLog → Timer → FiredCounter)。idempotent 性由
     * {@link #createChain} 的单例缓存 miss pattern 保证,本方法只会在首次 createChain
     * 时被调用,不会重复向 Engine 追加 observer。
     *
     * <p>关于 registry 缺失:
     * <ul>
     *   <li>MDCStampChainObserver / ChainDebugLogChainObserver — 无 registry 依赖,直接 new</li>
     *   <li>ChainTimerChainObserver / FiredCounterChainObserver — 接受 nullable registry,
     *       内部 lazy 检测,registry 缺失时全 no-op</li>
     * </ul>
     */
    private void registerStandardObservers() {
        // 1. MDC stamp — 必注册(无 registry 依赖)
        engine.addObserver(new MDCStampChainObserver());

        // 2. DEBUG log — 必注册(无 registry 依赖)
        engine.addObserver(new ChainDebugLogChainObserver());

        // 3. Timer — registry 缺失时也注册(observer 内部 no-op);保证 observer 列表
        //    在 registry 可用前后一致,Engine 调度逻辑无需区分
        MeterRegistry registry =
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        engine.addObserver(new ChainTimerChainObserver(registry));

        // 4. Fired counter — 同上,registry 缺失时内部 no-op
        engine.addObserver(new FiredCounterChainObserver(registry));
    }

    /**
     * 解析 protection 机制禁用集合 — 处理两条独立路径,追加到 {@code disabled} 集合:
     * <ul>
     *   <li>总开关 {@code protection.enabled=false} → 全 4 个防护 handler 短路</li>
     *   <li>per-mechanism 字段为 {@link Boolean#FALSE} → 单独覆盖该机制</li>
     * </ul>
     *
     * <p>输入 {@code disabled} 可含调用方已有的其他禁用项(自定义 handler),本方法
     * 仅追加,不动既有项。无 protection 配置(测试 mock)时默认视为开启 — 不追加任何禁用项。
     */
    private static void resolveProtectionDisabled(RedisProCacheProperties properties, Set<String> disabled) {
        RedisProCacheProperties.ProtectionProperties protection =
                properties == null ? null : properties.getProtection();
        if (protection == null) {
            return;
        }

        if (!protection.isEnabled()) {
            // 总开关关闭:从单一 PROTECTION_TOGGLES 列表派生 4 个 disableName,避免平行列表漂移
            PROTECTION_TOGGLES.stream()
                    .map(toggle -> toggle.order().getDisableName())
                    .forEach(disabled::add);
            log.info("Protection chain disabled by resi-cache.protection.enabled=false; "
                    + "protection handlers skipped, TTL preserved (bloom/lock/early-exp/null-value off)");
        } else {
            // per-mechanism 覆盖:遍历单一 PROTECTION_TOGGLES 列表,把显式 FALSE 的加到 disabled
            // Boolean.FALSE.equals(null) → false(null 表示"继承 enabled")
            for (Toggle toggle : PROTECTION_TOGGLES) {
                if (Boolean.FALSE.equals(toggle.getter().apply(protection))) {
                    disabled.add(toggle.order().getDisableName());
                    log.info("{} disabled by resi-cache.protection.{}.enabled=false",
                            toggle.order().getDescription(), toggle.configPath());
                }
            }
        }
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
