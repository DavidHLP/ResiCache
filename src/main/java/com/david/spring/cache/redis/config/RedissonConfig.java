package com.david.spring.cache.redis.config;

import lombok.extern.slf4j.Slf4j;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 基于 Spring 的 RedissonClient 简易配置。
 *
 * 仅在容器中不存在 RedissonClient 时生效，支持：
 *  - 根据 spring.data.redis.url 直接配置（推荐）
 *  - 或根据 host/port/database/username/password 组装单机地址
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
@ConditionalOnClass(RedissonClient.class)
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        String address = buildAddress(redisProperties);

        var single = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisProperties.getDatabase());

        if (StringUtils.hasText(redisProperties.getUsername())) {
            single.setUsername(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            single.setPassword(redisProperties.getPassword());
        }
        if (redisProperties.getTimeout() != null) {
            single.setTimeout((int) redisProperties.getTimeout().toMillis());
        }
        if (redisProperties.getConnectTimeout() != null) {
            single.setConnectTimeout((int) redisProperties.getConnectTimeout().toMillis());
        }
        if (StringUtils.hasText(redisProperties.getClientName())) {
            single.setClientName(redisProperties.getClientName());
        }

        return Redisson.create(config);
    }

    private String buildAddress(RedisProperties p) {
        if (StringUtils.hasText(p.getUrl())) {
            String url = p.getUrl().trim();
            if (url.startsWith("redis://") || url.startsWith("rediss://")) {
                return url;
            }
            return "redis://" + url;
        }
        String host = StringUtils.hasText(p.getHost()) ? p.getHost() : "localhost";
        int port = (p.getPort() > 0) ? p.getPort() : 6379;
        return "redis://" + host + ":" + port;
    }
}
