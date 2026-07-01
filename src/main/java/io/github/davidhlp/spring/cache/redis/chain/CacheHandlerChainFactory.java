package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.observer.ChainDebugLogChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainTimerChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.observer.FiredCounterChainObserver;
import io.github.davidhlp.spring.cache.redis.chain.observer.MDCStampChainObserver;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.chain.model.*;

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

            // 1) 装配 observer — 仅注册 4 个标准 observer；用户可通过 @Bean ChainEngine
            //    顶替默认 ChainEngine 并加自定义 observer（暂未启用此 hook，未来需要时
            //    增加 @Bean ObjectProvider<ChainObserver> extraObservers 即可）
            registerObserversOnce(engine);

            // 2) 构建链
            CacheHandlerChain chain = new CacheHandlerChain();
            chain.setEngine(engine);

            // guide §223b:为每个 enabled AbstractCacheHandler 注入 registry
            // 触发 onAttachMetrics 注册子类的语义 counter
            MeterRegistry registry =
                    meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();

            Set<String> disabled = new HashSet<>(properties.getDisabledHandlers());

            // 防护链总开关 + per-mechanism 覆盖(WS-1.4):
            // - 总开关 enabled=false → 全 4 个防护 handler 短路(行为与 Path C 前兼容)
            // - per-mechanism 字段(非 null) → 单独覆盖该机制(分项关闭便于生产故障定位)
            // 注意:"ttl" 不纳入禁用集合 — TtlHandler 兼担基础 TTL 计算 + 抖动防护,
            // 禁用会导致 ActualCacheHandler 写入无 TTL 的永久缓存(数据陈旧 + 内存泄漏)。
            // null-safe:测试用 mock/stub 的 properties 可能不设 protection,默认视为开启
            RedisProCacheProperties.ProtectionProperties protection = properties.getProtection();
            if (protection != null && !protection.isEnabled()) {
                // 从 HandlerOrder 枚举派生防护 handler 的 disableName,与 handler 自报家门保持
                // 单一事实源 — handler 类重命名不会让此短路静默失效。
                PROTECTION_HANDLER_ORDERS.stream()
                        .map(HandlerOrder::getDisableName)
                        .forEach(disabled::add);
                log.info("Protection chain disabled by resi-cache.protection.enabled=false; "
                        + "protection handlers skipped, TTL preserved (bloom/lock/early-exp/null-value off)");
            } else if (protection != null) {
                // per-mechanism 覆盖(WS-1.4):每个 Boolean 字段 null = 继承 enabled,
                // 非 null = 单独覆盖该机制 — 收敛到 PROTECTION_TOGGLES 列表迭代
                // (ADR-0021,本类内嵌套 ProtectionToggle record)
                collectPerMechanismDisables(protection, disabled);
            }

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
     */
    private void registerObserversOnce(ChainEngine engine) {
        // 1. MDC stamp — 必注册（无 registry 依赖）
        engine.addObserver(new MDCStampChainObserver());

        // 2. DEBUG log — 必注册（无 registry 依赖）
        engine.addObserver(new ChainDebugLogChainObserver());

        // 3. Timer — registry 缺失时也注册（observer 内部 lazy 检测）；保证 observer 列表
        //    在 registry 可用前后一致，Engine 调度逻辑无需区分
        MeterRegistry registry =
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        engine.addObserver(new ChainTimerChainObserver(registry));

        // 4. Fired counter — 同上，registry 缺失时内部 no-op
        engine.addObserver(new FiredCounterChainObserver(registry));
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
    /**
     * 每机制禁用 toggle record — 收敛 "if FALSE → add to disabled + log" 4 行模板
     * (ADR-0021,本类内嵌套)。
     *
     * <p>3 字段:
     * <ul>
     *   <li>{@code order} — 对应 {@link HandlerOrder} 枚举值,供 {@link HandlerOrder#getDisableName()}
     *       反查配置禁用名</li>
     *   <li>{@code getter} — {@link RedisProCacheProperties.ProtectionProperties} 上的
     *       {@code Boolean} 字段 getter(可能返回 null,代表"继承 enabled" — null 不触发短路);
     *       用方法引用直接绑定<strong>消除</strong>先前 switch 设计的"加新机制要改 2 处"
     *       drift 风险(getter 是 record 字段,与 PROTECTION_TOGGLES 列表原子绑定)</li>
     *   <li>{@code configPath} — 配置文件中的 kebab-case 路径段,用于日志 (e.g. "bloom-filter")</li>
     * </ul>
     */
    private record ProtectionToggle(
            HandlerOrder order,
            java.util.function.Function<
                    RedisProCacheProperties.ProtectionProperties, Boolean> getter,
            String configPath) {
    }

    /**
     * 4 个 protection 机制 toggle 单一事实源 — 新加第 5 机制时<strong>仅追加一行</strong>到本列表
     * (ADR-0021,本类内嵌套),getter 字段直接绑定到 ProtectionProperties 的 Boolean 字段,
     * 无需另写 switch 映射。
     *
     * <p>注意:本列表仅含 4 个可禁用的防护机制(Bloom / SyncLock / EarlyExpiration / NullValue),
     * 不含 TTL — TtlHandler 兼担基础 TTL 计算,禁用会导致 ActualCacheHandler 写入无 TTL 的
     * 永久缓存(数据陈旧 + 内存泄漏)。
     */
    private static final List<ProtectionToggle> PROTECTION_TOGGLES = List.of(
            new ProtectionToggle(HandlerOrder.BLOOM_FILTER,
                    RedisProCacheProperties.ProtectionProperties::getBloomFilterEnabled,
                    "bloom-filter"),
            new ProtectionToggle(HandlerOrder.SYNC_LOCK,
                    RedisProCacheProperties.ProtectionProperties::getSyncLockEnabled,
                    "sync-lock"),
            new ProtectionToggle(HandlerOrder.EARLY_EXPIRATION,
                    RedisProCacheProperties.ProtectionProperties::getEarlyExpirationEnabled,
                    "early-expiration"),
            new ProtectionToggle(HandlerOrder.NULL_VALUE,
                    RedisProCacheProperties.ProtectionProperties::getNullValueEnabled,
                    "null-value"));

    /**
     * Per-mechanism 禁用集合收集 — 遍历 {@link #PROTECTION_TOGGLES},把显式 {@code Boolean.FALSE}
     * 的机制加到 {@code disabled} 集合 + 记 INFO 日志。
     *
     * <p>本方法抽取自原 4 if-block 重复模板(ADR-0021):
     * <pre>
     *   if (Boolean.FALSE.equals(protection.getXEnabled())) {
     *       disabled.add(HandlerOrder.X.getDisableName());
     *       log.info("X disabled by resi-cache.protection.x.enabled=false");
     *   }
     * </pre>
     *
     * <p>getter 字段在 PROTECTION_TOGGLES 列表初始化时已绑定方法引用,无运行时 switch / 查表;
     * 字段映射与列表定义原子同源,不可能 drift。
     *
     * @param protection 配置对象(由调用方 null-check 保障)
     * @param disabled 输出集合(原 4 if-block 共享的 {@code Set<String>})
     */
    private static void collectPerMechanismDisables(
            RedisProCacheProperties.ProtectionProperties protection,
            Set<String> disabled) {
        for (ProtectionToggle toggle : PROTECTION_TOGGLES) {
            // Boolean.FALSE.equals(null) → false,null 表示"继承 enabled"(per-mechanism 字段未设),
            // 等同原 4 if-block 的 Boolean.FALSE.equals(...) 语义 — null 不触发短路。
            if (Boolean.FALSE.equals(toggle.getter().apply(protection))) {
                disabled.add(toggle.order().getDisableName());
                log.info("{} disabled by resi-cache.protection.{}.enabled=false",
                        toggle.order().getDescription(), toggle.configPath());
            }
        }
    }
}
