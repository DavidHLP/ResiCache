package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.chain.model.*;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 缓存处理器责任链工厂
 *
 * 职责：
 * 1. 自动发现所有 CacheHandler 实现
 * 2. 按 @HandlerPriority 注解排序
 * 3. 根据配置过滤禁用的 Handler
 * 4. 构建责任链
 *
 * 设计改进：
 * - 原设计：手动添加 Handler，顺序硬编码
 * - 新设计：自动注入 + 注解排序 + 配置禁用，便于扩展
 */
@Slf4j
@Component
public class CacheHandlerChainFactory {

    /** 自动注入所有 CacheHandler 实现 */
    private final List<CacheHandler> handlers;

    /** 配置属性 */
    private final RedisProCacheProperties properties;

    /** ObjectProvider for MeterRegistry — WS-1.4 链级 Timer 注入 */

    /**
     * 防护纵深 handler 的执行顺序枚举(用于 {@code protection.enabled=false} 时派生 disableName)。
     * 不含 TTL——TtlHandler 兼担基础 TTL 计算,属于不可禁用的基础缓存契约(禁用会导致永久缓存)。
     * 从枚举派生而非硬编码字符串,保证短路逻辑与 handler 自报家门同源。
     */
    private static final List<HandlerOrder> PROTECTION_HANDLER_ORDERS = List.of(
            HandlerOrder.BLOOM_FILTER,
            HandlerOrder.SYNC_LOCK,
            HandlerOrder.EARLY_EXPIRATION,
            HandlerOrder.NULL_VALUE);

    public CacheHandlerChainFactory(List<CacheHandler> handlers,
                                 RedisProCacheProperties properties,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.handlers = handlers;
        this.properties = properties;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** 缓存的责任链实例（单例，避免 handler next 指针被并发修改） */
    private volatile CacheHandlerChain cachedChain;

    /**
     * 创建或获取责任链（单例模式，确保 handler next 指针不被并发修改）
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

            CacheHandlerChain chain = new CacheHandlerChain(meterRegistryProvider);
            // guide §223b:为每个 enabled AbstractCacheHandler 注入 registry 以建 per-handler FIRED counter
            MeterRegistry registry =
                    meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();

            Set<String> disabled = new HashSet<>(properties.getDisabledHandlers());

            // 防护链总开关 + per-mechanism 覆盖(WS-1.4):
            // - 总开关 enabled=false → 全 4 个防护 handler 短路(行为与 Path C 前兼容)
            // - per-mechanism 字段(非 null) → 单独覆盖该机制(分项关闭便于生产故障定位)
            // 注意:"ttl" 不纳入禁用集合——TtlHandler 兼担基础 TTL 计算 + 抖动防护,
            // 禁用会导致 ActualCacheHandler 写入无 TTL 的永久缓存(数据陈旧 + 内存泄漏)。
            // null-safe:测试用 mock/stub 的 properties 可能不设 protection,默认视为开启
            RedisProCacheProperties.ProtectionProperties protection = properties.getProtection();
            if (protection != null && !protection.isEnabled()) {
                // 从 HandlerOrder 枚举派生防护 handler 的 disableName,与 handler 自报家门保持
                // 单一事实源——handler 类重命名不会让此短路静默失效。
                PROTECTION_HANDLER_ORDERS.stream()
                        .map(HandlerOrder::getDisableName)
                        .forEach(disabled::add);
                log.info("Protection chain disabled by resi-cache.protection.enabled=false; "
                        + "protection handlers skipped, TTL preserved (bloom/lock/early-exp/null-value off)");
            } else if (protection != null) {
                // per-mechanism 覆盖(WS-1.4):每个 Boolean 字段 null = 继承 enabled,
                // 非 null = 单独覆盖该机制
                if (Boolean.FALSE.equals(protection.getBloomFilterEnabled())) {
                    disabled.add(HandlerOrder.BLOOM_FILTER.getDisableName());
                    log.info("Bloom filter disabled by resi-cache.protection.bloom-filter.enabled=false");
                }
                if (Boolean.FALSE.equals(protection.getSyncLockEnabled())) {
                    disabled.add(HandlerOrder.SYNC_LOCK.getDisableName());
                    log.info("Sync lock disabled by resi-cache.protection.sync-lock.enabled=false");
                }
                if (Boolean.FALSE.equals(protection.getEarlyExpirationEnabled())) {
                    disabled.add(HandlerOrder.EARLY_EXPIRATION.getDisableName());
                    log.info("Early expiration disabled by resi-cache.protection.early-expiration.enabled=false");
                }
                if (Boolean.FALSE.equals(protection.getNullValueEnabled())) {
                    disabled.add(HandlerOrder.NULL_VALUE.getDisableName());
                    log.info("Null value disabled by resi-cache.protection.null-value.enabled=false");
                }
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
