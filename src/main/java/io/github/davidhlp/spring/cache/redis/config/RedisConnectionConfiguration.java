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
 * <p>职责:仅负责 {@link RedisTemplate} 与衍生 Operations bean 的装配。
 *
 * <p><b>不再持有任何 Redisson 强类型引用</b>——Redisson 相关配置已迁移至
 * {@link RedissonConfiguration}(独立类 + 类级 {@code @ConditionalOnClass}),
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
        // Round 11:SecureJacksonSerializerFactory 抽出后,装配走单点
        // （Round 5 fix 保留 — props 真的传进去）。Round 5 注释里的「与
        // RedisProCacheConfiguration ... 镜像」警示被工厂吸收,两个装配点
        // 再不会漂移。
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
            RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForHash();
    }

    @Bean
    @ConditionalOnMissingBean
    public ValueOperations<String, Object> valueOperations(
            RedisTemplate<String, Object> redisTemplate) {
        return redisTemplate.opsForValue();
    }
}
