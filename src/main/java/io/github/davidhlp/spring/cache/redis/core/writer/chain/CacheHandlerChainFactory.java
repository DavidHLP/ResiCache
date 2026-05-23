package io.github.davidhlp.spring.cache.redis.core.writer.chain;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.*;

import lombok.extern.slf4j.Slf4j;

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

    public CacheHandlerChainFactory(List<CacheHandler> handlers, RedisProCacheProperties properties) {
        this.handlers = handlers;
        this.properties = properties;
    }

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

            CacheHandlerChain chain = new CacheHandlerChain();

            Set<String> disabled = new HashSet<>(properties.getDisabledHandlers());

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
     * 获取 Handler 的禁用配置名称
     * 根据类名映射到配置中的名称（kebab-case）
     */
    private String getHandlerDisableName(CacheHandler handler) {
        String className = handler.getClass().getSimpleName();
        // BloomFilterHandler -> bloom-filter
        // EarlyExpirationHandler -> early-expiration
        // SyncLockHandler -> sync-lock
        // NullValueHandler -> null-value
        // TtlHandler -> ttl
        // ActualCacheHandler -> actual-cache (always enabled, cannot disable)
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
