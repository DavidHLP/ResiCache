package com.david.spring.cache.redis.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisOperations;

/**
 * Redis缓存自动配置主入口
 *
 * <p>职责： 1. 作为Redis缓存模块的配置入口点 2. 启用Spring Cache功能 3. 导入各个专门的配置类 4. 确保配置加载顺序正确
 */
@Slf4j
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass({RedisOperations.class})
@EnableCaching
@Import({
    RedisConnectionConfiguration.class,
    RedisCacheRegistryConfiguration.class,
    RedisProxyCachingConfiguration.class,
    RedisProCacheConfiguration.class
})
public class RedisCacheAutoConfiguration {

    @PostConstruct
    public void init() {
        log.info("Initializing Redis Cache Design Pattern Configuration");
    }
}
