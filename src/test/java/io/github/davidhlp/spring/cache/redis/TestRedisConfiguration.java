package io.github.davidhlp.spring.cache.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.config.SecureJackson2JsonRedisSerializer;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Test configuration for Redis integration tests.
 *
 * <p>This configuration uses Spring Boot's auto-configured RedisConnectionFactory
 * which is populated with dynamic properties from the Testcontainers-managed
 * Redis container via @DynamicPropertySource in AbstractRedisIntegrationTest.
 */
@TestConfiguration
public class TestRedisConfiguration {

    @Bean
    @Primary
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
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @Primary
    public HashOperations<String, String, String> hashOperations(
            RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForHash();
    }

    @Bean
    @Primary
    public ValueOperations<String, Object> valueOperations(
            RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForValue();
    }

    @Bean
    @Primary
    public RedissonClient redissonClient(
            RedisProCacheProperties properties,
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;

        RedisProCacheProperties.RedissonProperties redissonProps = properties.getRedisson();

        config.useSingleServer()
                .setAddress(address)
                .setDatabase(0)
                .setConnectionPoolSize(redissonProps.getConnectionPoolSize())
                .setConnectionMinimumIdleSize(redissonProps.getConnectionMinimumIdleSize())
                .setIdleConnectionTimeout(redissonProps.getIdleConnectionTimeout())
                .setConnectTimeout(redissonProps.getConnectTimeout())
                .setTimeout(redissonProps.getTimeout())
                .setRetryAttempts(redissonProps.getRetryAttempts())
                .setRetryInterval(redissonProps.getRetryInterval());

        return Redisson.create(config);
    }
}
