package io.github.davidhlp.spring.cache.redis.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonRedisSerializer;
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
            RedisProCacheProperties properties) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        // 与 RedisProCacheConfiguration#defaultRedisCacheConfiguration(行 63-69)对称:让
        // resi-cache.serializer.* 属性真正生效,而不是回退 SecureJacksonRedisSerializer
        // 默认([io.github.davidhlp] 白名单 + 多态类型信息关闭)。Round 5 fix。
        SecureJacksonRedisSerializer jsonSerializer =
                new SecureJacksonRedisSerializer(
                        objectMapper,
                        properties.getSerializer().getAllowedPackagePrefixes(),
                        properties.getSerializer().isFailOnUnknownType(),
                        properties.getSerializer().getTypeProperty(),
                        properties.getSerializer().isPolymorphicTypingEnabled());

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
