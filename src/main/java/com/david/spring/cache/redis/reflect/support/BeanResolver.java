package com.david.spring.cache.redis.reflect.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.lang.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 统一 Bean 解析器，支持任意类型的 Bean 从 Spring 上下文中解析
 * 提供线程安全的缓存机制、静态便捷方法和测试诊断功能
 * 
 * @param <T> Bean 类型
 */
@Slf4j
public class BeanResolver<T> {

    private final Class<T> beanType;
    private final ConcurrentMap<String, T> namedBeanCache = new ConcurrentHashMap<>();
    private volatile T typeBeanCache;
    private volatile long lastResolveTime = 0;
    private static final long CACHE_EXPIRE_TIME = 30_000; // 30秒缓存过期时间

    // 全局静态解析器实例，提供便捷的静态方法
    private static final BeanResolver<KeyGenerator> KEY_GENERATOR_RESOLVER = new BeanResolver<>(KeyGenerator.class);
    private static final BeanResolver<CacheResolver> CACHE_RESOLVER_RESOLVER = new BeanResolver<>(CacheResolver.class);

    public BeanResolver(Class<T> beanType) {
        this.beanType = beanType;
    }

    /**
     * 解析指定名称的 Bean，优先从缓存获取
     * 
     * @param beanName Bean 名称，可以为 null
     * @return 解析的 Bean 实例，失败返回 null
     */
    @Nullable
    public T resolve(@Nullable String beanName) {
        return resolve(beanName, false);
    }

    /**
     * 解析指定名称的 Bean
     * 
     * @param beanName     Bean 名称，可以为 null
     * @param forceRefresh 是否强制刷新缓存
     * @return 解析的 Bean 实例，失败返回 null
     */
    @Nullable
    public T resolve(@Nullable String beanName, boolean forceRefresh) {
        long currentTime = System.currentTimeMillis();
        boolean cacheExpired = (currentTime - lastResolveTime) > CACHE_EXPIRE_TIME;

        if (forceRefresh || cacheExpired) {
            log.debug("Clearing expired cache for bean type: {}", beanType.getSimpleName());
            clearCache();
        }

        // 优先按名称解析
        if (beanName != null && !beanName.isBlank()) {
            T namedBean = resolveByName(beanName);
            if (namedBean != null) {
                lastResolveTime = currentTime;
                return namedBean;
            }
        }

        // 按类型解析
        T typeBean = resolveByType();
        if (typeBean != null) {
            lastResolveTime = currentTime;
        }
        return typeBean;
    }

    /**
     * 按名称解析 Bean，支持缓存
     */
    @Nullable
    private T resolveByName(String beanName) {
        return namedBeanCache.computeIfAbsent(beanName, name -> {
            log.debug("Resolving {} by name: {}", beanType.getSimpleName(), name);
            T bean = SpringContextHolder.getBean(name, beanType);
            if (bean != null) {
                log.debug("Successfully resolved {} by name: {}", beanType.getSimpleName(), name);
            } else {
                log.debug("Failed to resolve {} by name: {}", beanType.getSimpleName(), name);
            }
            return bean;
        });
    }

    /**
     * 按类型解析 Bean，支持缓存
     */
    @Nullable
    private T resolveByType() {
        if (typeBeanCache != null) {
            log.debug("Using cached {} by type", beanType.getSimpleName());
            return typeBeanCache;
        }

        log.debug("Resolving {} by type", beanType.getSimpleName());
        T bean = SpringContextHolder.getBean(beanType);
        if (bean != null) {
            log.debug("Successfully resolved {} by type", beanType.getSimpleName());
            typeBeanCache = bean;
        } else {
            log.warn("Failed to resolve {} by type", beanType.getSimpleName());
        }
        return bean;
    }

    /**
     * 使用自定义解析逻辑
     * 
     * @param beanName       Bean 名称
     * @param customResolver 自定义解析器
     * @return 解析的 Bean 实例
     */
    @Nullable
    public T resolveWithCustom(@Nullable String beanName, Supplier<T> customResolver) {
        T bean = resolve(beanName);
        if (bean != null) {
            return bean;
        }

        log.debug("Using custom resolver for {}", beanType.getSimpleName());
        try {
            return customResolver.get();
        } catch (Exception e) {
            log.warn("Custom resolver failed for {}: {}", beanType.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 清除所有缓存
     */
    public void clearCache() {
        namedBeanCache.clear();
        typeBeanCache = null;
        log.debug("Cleared all cache for bean type: {}", beanType.getSimpleName());
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getCacheStats() {
        return new CacheStats(
                namedBeanCache.size(),
                typeBeanCache != null ? 1 : 0,
                lastResolveTime);
    }

    /**
     * 缓存统计信息记录类
     */
    public record CacheStats(
            int namedBeanCacheSize,
            int typeBeanCacheSize,
            long lastResolveTime) {
    }

    // ==================== 静态便捷方法 ====================

    /**
     * 解析 KeyGenerator Bean（静态便捷方法）
     * 优先按名称解析，失败则按类型解析，支持向后兼容
     *
     * @param resolvedKeyGenerator 已解析的实例（用于向后兼容）
     * @param name                 Bean 名称
     * @return KeyGenerator 实例，如果解析失败返回 null
     */
    @Nullable
    public static KeyGenerator resolveKeyGenerator(@Nullable KeyGenerator resolvedKeyGenerator, @Nullable String name) {
        // 如果已有解析的实例，直接返回（向后兼容）
        if (resolvedKeyGenerator != null) {
            log.debug("Using provided KeyGenerator instance: {}", resolvedKeyGenerator.getClass().getName());
            return resolvedKeyGenerator;
        }

        log.debug("Resolving KeyGenerator from Spring context (name: {})", name);
        KeyGenerator kg = KEY_GENERATOR_RESOLVER.resolve(name);

        if (kg != null) {
            log.info("Successfully resolved KeyGenerator: {} (name: {})", kg.getClass().getName(), name);
        } else {
            log.warn("Failed to resolve KeyGenerator (name: {})", name);
        }
        return kg;
    }

    /**
     * 解析 CacheResolver Bean（静态便捷方法）
     * 优先按名称解析，失败则按类型解析，支持向后兼容
     *
     * @param resolvedCacheResolver 已解析的实例（用于向后兼容）
     * @param name                  Bean 名称
     * @return CacheResolver 实例，如果解析失败返回 null
     */
    @Nullable
    public static CacheResolver resolveCacheResolver(@Nullable CacheResolver resolvedCacheResolver, @Nullable String name) {
        // 如果已有解析的实例，直接返回（向后兼容）
        if (resolvedCacheResolver != null) {
            log.debug("Using provided CacheResolver instance: {}", resolvedCacheResolver.getClass().getName());
            return resolvedCacheResolver;
        }

        log.debug("Resolving CacheResolver from Spring context (name: {})", name);
        CacheResolver cr = CACHE_RESOLVER_RESOLVER.resolve(name);

        if (cr != null) {
            log.info("Successfully resolved CacheResolver: {} (name: {})", cr.getClass().getName(), name);
        } else {
            log.warn("Failed to resolve CacheResolver (name: {})", name);
        }
        return cr;
    }

    // ==================== 缓存管理方法 ====================

    /**
     * 强制刷新 KeyGenerator 缓存
     */
    public static void refreshKeyGeneratorCache() {
        log.info("Refreshing KeyGenerator cache");
        KEY_GENERATOR_RESOLVER.clearCache();
    }

    /**
     * 强制刷新 CacheResolver 缓存
     */
    public static void refreshCacheResolverCache() {
        log.info("Refreshing CacheResolver cache");
        CACHE_RESOLVER_RESOLVER.clearCache();
    }

    /**
     * 清除所有静态 Bean 解析缓存
     */
    public static void clearAllStaticCache() {
        log.info("Clearing all static Bean resolver caches");
        KEY_GENERATOR_RESOLVER.clearCache();
        CACHE_RESOLVER_RESOLVER.clearCache();
    }

    /**
     * 获取 KeyGenerator 缓存统计信息
     */
    public static CacheStats getKeyGeneratorCacheStats() {
        return KEY_GENERATOR_RESOLVER.getCacheStats();
    }

    /**
     * 获取 CacheResolver 缓存统计信息
     */
    public static CacheStats getCacheResolverCacheStats() {
        return CACHE_RESOLVER_RESOLVER.getCacheStats();
    }

    // ==================== 测试和诊断方法 ====================

    /**
     * 测试 Spring 上下文是否可用
     */
    public static boolean testSpringContext() {
        boolean available = SpringContextHolder.hasContext();
        log.info("Spring context availability test: {}", available ? "PASSED" : "FAILED");
        return available;
    }

    /**
     * 测试 KeyGenerator Bean 解析
     */
    public static void testKeyGeneratorResolution(@Nullable String beanName) {
        log.info("Testing KeyGenerator resolution with name: {}", beanName);

        KeyGenerator kg = KEY_GENERATOR_RESOLVER.resolve(beanName);

        if (kg != null) {
            log.info("✅ KeyGenerator resolution SUCCESS: {} (name: {})",
                    kg.getClass().getName(), beanName);
        } else {
            log.warn("❌ KeyGenerator resolution FAILED (name: {})", beanName);
        }

        // 打印缓存统计
        CacheStats stats = KEY_GENERATOR_RESOLVER.getCacheStats();
        log.info("Cache stats: namedCache={}, typeCache={}, lastResolve={}",
                stats.namedBeanCacheSize(), stats.typeBeanCacheSize(), stats.lastResolveTime());
    }

    /**
     * 测试 CacheResolver Bean 解析
     */
    public static void testCacheResolverResolution(@Nullable String beanName) {
        log.info("Testing CacheResolver resolution with name: {}", beanName);

        CacheResolver cr = CACHE_RESOLVER_RESOLVER.resolve(beanName);

        if (cr != null) {
            log.info("✅ CacheResolver resolution SUCCESS: {} (name: {})",
                    cr.getClass().getName(), beanName);
        } else {
            log.warn("❌ CacheResolver resolution FAILED (name: {})", beanName);
        }

        // 打印缓存统计
        CacheStats stats = CACHE_RESOLVER_RESOLVER.getCacheStats();
        log.info("Cache stats: namedCache={}, typeCache={}, lastResolve={}",
                stats.namedBeanCacheSize(), stats.typeBeanCacheSize(), stats.lastResolveTime());
    }

    /**
     * 运行完整的 Bean 上下文测试套件
     */
    public static void runFullTestSuite() {
        log.info("🚀 Starting Bean Context Test Suite");

        // 1. 测试 Spring 上下文
        testSpringContext();

        // 2. 测试默认 Bean 解析（按类型）
        testKeyGeneratorResolution(null);
        testCacheResolverResolution(null);

        // 3. 测试命名 Bean 解析
        testKeyGeneratorResolution("customKeyGenerator");
        testCacheResolverResolution("customCacheResolver");

        // 4. 测试缓存功能
        testCacheManagement();

        log.info("✅ Bean Context Test Suite completed");
    }

    /**
     * 测试缓存管理功能
     */
    public static void testCacheManagement() {
        log.info("Testing cache management functionality");

        // 获取缓存统计
        CacheStats kgStats = getKeyGeneratorCacheStats();
        CacheStats crStats = getCacheResolverCacheStats();

        log.info("KeyGenerator cache stats: namedCache={}, typeCache={}, lastResolve={}",
                kgStats.namedBeanCacheSize(), kgStats.typeBeanCacheSize(), kgStats.lastResolveTime());
        log.info("CacheResolver cache stats: namedCache={}, typeCache={}, lastResolve={}",
                crStats.namedBeanCacheSize(), crStats.typeBeanCacheSize(), crStats.lastResolveTime());

        // 测试缓存清除
        clearAllStaticCache();
        log.info("✅ Cache cleared successfully");
    }

    /**
     * 打印 Bean 解析器性能统计
     */
    public static void printPerformanceStats() {
        log.info("📊 Bean Resolver Performance Statistics");

        CacheStats kgStats = getKeyGeneratorCacheStats();
        CacheStats crStats = getCacheResolverCacheStats();

        log.info("KeyGenerator Resolver:");
        log.info("  - Named bean cache size: {}", kgStats.namedBeanCacheSize());
        log.info("  - Type bean cache size: {}", kgStats.typeBeanCacheSize());
        log.info("  - Last resolve time: {}", kgStats.lastResolveTime());

        log.info("CacheResolver Resolver:");
        log.info("  - Named bean cache size: {}", crStats.namedBeanCacheSize());
        log.info("  - Type bean cache size: {}", crStats.typeBeanCacheSize());
        log.info("  - Last resolve time: {}", crStats.lastResolveTime());
    }
}
