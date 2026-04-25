package io.github.davidhlp.spring.cache.redis.config;

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
import org.springframework.data.redis.serializer.RedisSerializer;
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
        SecureJackson2JsonRedisSerializer jsonSerializer =
                new SecureJackson2JsonRedisSerializer(objectMapper);

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.setDefaultSerializer(jsonSerializer);
        template.setEnableDefaultSerializer(true);
        // Timeout is configured via spring.data.redis.timeout in application.yml
        template.afterPropertiesSet();

        log.debug(
                "Created RedisCacheTemplate with StringRedisSerializer for keys and SecureJackson2JsonRedisSerializer for values");
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
    public RedissonClient redissonClient(RedisProperties redisProperties, RedisProCacheProperties properties) {
        Config config = new Config();
        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();

        RedisProCacheProperties.RedissonProperties redissonProps = properties.getRedisson();

        config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisProperties.getDatabase())
                .setConnectionPoolSize(redissonProps.getConnectionPoolSize())
                .setConnectionMinimumIdleSize(redissonProps.getConnectionMinimumIdleSize())
                .setIdleConnectionTimeout(redissonProps.getIdleConnectionTimeout())
                .setConnectTimeout(redissonProps.getConnectTimeout())
                .setTimeout(redissonProps.getTimeout())
                .setRetryAttempts(redissonProps.getRetryAttempts())
                .setRetryInterval(redissonProps.getRetryInterval());

        String password = redisProperties.getPassword();
        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }

        log.debug("Created RedissonClient with single server configuration: {}", address);
        return Redisson.create(config);
    }
}
