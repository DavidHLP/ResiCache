package com.david.spring.cache.redis.config;

import lombok.extern.slf4j.Slf4j;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Redisson客户端自动配置类
 *
 * 用于自动配置RedissonClient，仅在容器中不存在RedissonClient时生效。
 *
 * 支持的配置方式：
 * - 根据spring.data.redis.url直接配置（推荐）
 * - 根据host/port/database/username/password组装单机地址
 *
 * 主要功能：
 * - 自动创建RedissonClient Bean
 * - 支持单机Redis连接配置
 * - 支持身份验证和超时配置
 * - 提供地址构建工具方法
 */
@Slf4j
@AutoConfiguration(after = RedisAutoConfiguration.class)
@EnableConfigurationProperties(RedisProperties.class)
@ConditionalOnClass(RedissonClient.class)
public class RedissonConfig {

    /**
     * 创建RedissonClient Bean
     *
     * 仅在容器中不存在RedissonClient时才会创建。
     * 支持根据Spring Boot的Redis配置属性自动配置连接参数。
     *
     * @param redisProperties Spring Boot Redis配置属性
     * @return 配置好的RedissonClient实例
     */
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

    /**
     * 构建Redis连接地址
     *
     * 优先使用url配置，如果没有则使用host和port组合。
     * 自动添加redis://协议前缀（如果需要）。
     *
     * @param p Redis配置属性
     * @return 格式化的Redis连接地址
     */
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
