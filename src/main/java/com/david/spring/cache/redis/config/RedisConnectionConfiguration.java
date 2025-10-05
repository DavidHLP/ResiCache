package com.david.spring.cache.redis.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/** Redis连接和模板配置 负责： 1. RedisTemplate配置和序列化策略 2. RedissonClient连接配置 3. 连接池参数优化 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RedisConnectionConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "redisCacheTemplate")
    public RedisTemplate<String, Object> redisCacheTemplate(
            RedisConnectionFactory redisConnectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.setDefaultSerializer(jsonSerializer);
        template.setEnableDefaultSerializer(true);
        template.afterPropertiesSet();

        log.debug(
                "Created RedisCacheTemplate with StringRedisSerializer for keys and GenericJackson2JsonRedisSerializer for values");
        return template;
    }

    @Bean
    @ConditionalOnMissingBean
    public HashOperations<String, String, String> hashOperations(
            RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForHash();
    }

    @Bean
    @ConditionalOnMissingBean
    public ValueOperations<String, Object> valueOperations(
            RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForValue();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RedissonClient.class)
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();

        config.useSingleServer()
                .setAddress(address)
                .setPassword(redisProperties.getPassword())
                .setDatabase(redisProperties.getDatabase())
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(10)
                .setIdleConnectionTimeout(10000)
                .setConnectTimeout(10000)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

        log.debug("Created RedissonClient with single server configuration: {}", address);
        return Redisson.create(config);
    }
}
