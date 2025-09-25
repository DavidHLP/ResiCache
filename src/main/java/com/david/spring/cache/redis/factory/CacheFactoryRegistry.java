package com.david.spring.cache.redis.factory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 缓存工厂注册中心
 * 管理所有缓存工厂，根据需求选择合适的工厂创建缓存
 */
@Slf4j
@Component
public class CacheFactoryRegistry {

    private final List<CacheFactory> factories;

    public CacheFactoryRegistry(List<CacheFactory> factories) {
        this.factories = factories.stream()
                .sorted((f1, f2) -> Integer.compare(f1.getOrder(), f2.getOrder()))
                .collect(Collectors.toList());
        log.info("Initialized CacheFactoryRegistry with {} factories", this.factories.size());
    }

    /**
     * 创建缓存实例
     *
     * @param config 缓存配置
     * @return 缓存实例
     */
    public Cache createCache(CacheCreationConfig config) {
        CacheFactory selectedFactory = selectFactory(config.getCacheType());

        log.debug("Creating cache '{}' using factory: {}",
                config.getCacheName(), selectedFactory.getClass().getSimpleName());

        return selectedFactory.createCache(config);
    }

    /**
     * 根据缓存类型选择合适的工厂
     *
     * @param cacheType 缓存类型
     * @return 选择的工厂
     */
    private CacheFactory selectFactory(CacheType cacheType) {
        return factories.stream()
                .filter(factory -> factory.supports(cacheType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No factory found for cache type: " + cacheType));
    }

    /**
     * 获取所有支持的缓存类型
     *
     * @return 缓存类型列表
     */
    public List<CacheType> getSupportedCacheTypes() {
        return factories.stream()
                .flatMap(factory -> List.of(CacheType.values()).stream().filter(factory::supports))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 获取已注册的工厂数量
     */
    public int getFactoryCount() {
        return factories.size();
    }
}