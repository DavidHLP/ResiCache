package io.github.davidhlp.spring.cache.redis.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonRedisSerializer;
import io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonSerializerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 连接和模板配置.
 *
 * <p>职责:仅负责 ResiCache 的安全 {@link RedisTemplate} 与衍生 Operations
 * bean 的装配。模板使用专用基础设施名称 {@code redisCacheTemplate},以便与
 * Spring Boot 可能提供的通用 template 共存；库内消费者统一限定到该模板。
 *
 * <p>本类不持有任何 Redisson 强类型引用——Redisson 配置由
 * {@link RedissonConfiguration}(独立类 + 类级 {@code @ConditionalOnClass})负责,
 * 使 Redisson 成为真正的可选依赖:当 Redisson 不在 classpath 时,本类仍可
 * 正常加载与实例化,不会触发 {@code NoClassDefFoundError}。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RedisConnectionConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "redisCacheTemplate")
    public RedisTemplate<String, Object> redisCacheTemplate(
            RedisConnectionFactory redisConnectionFactory,
            ObjectMapper objectMapper,
            RedisProCacheProperties properties,
            SecureJacksonSerializerFactory serializerFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        // 装配走单点 SecureJacksonSerializerFactory,与
        // RedisProCacheConfiguration 同源,两个装配点不会漂移。
        SecureJacksonRedisSerializer jsonSerializer =
                serializerFactory.create(objectMapper, properties.getSerializer());

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.setDefaultSerializer(jsonSerializer);
        template.setEnableDefaultSerializer(true);
        // Timeout is configured via spring.data.redis.timeout in application.yml
        template.afterPropertiesSet();

        log.debug(
                "Created RedisCacheTemplate with StringRedisSerializer for keys and SecureJacksonRedisSerializer for values (allowed-package-prefixes={}, polymorphicTypingEnabled={})",
                properties.getSerializer().getAllowedPackagePrefixes(),
                properties.getSerializer().isPolymorphicTypingEnabled());
        return template;
    }

    @Bean
    @ConditionalOnMissingBean
    public HashOperations<String, String, String> hashOperations(
            @org.springframework.beans.factory.annotation.Qualifier("redisCacheTemplate")
            RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForHash();
    }

    @Bean
    @ConditionalOnMissingBean
    public ValueOperations<String, Object> valueOperations(
            @org.springframework.beans.factory.annotation.Qualifier("redisCacheTemplate")
            RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForValue();
    }
}
