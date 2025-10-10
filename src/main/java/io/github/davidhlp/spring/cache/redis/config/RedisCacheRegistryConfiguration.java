package io.github.davidhlp.spring.cache.redis.config;

import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis缓存注册器配置
 * 负责：
 * 1. 缓存操作注册器的bean配置
 * 2. 缓存操作元数据管理
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RedisCacheRegistryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RedisCacheRegister redisCacheRegister() {
        log.debug("Created RedisCacheRegister for cache operation registry");
        return new RedisCacheRegister();
    }
}