package io.github.davidhlp.spring.cache.redis.cache;





import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import org.springframework.stereotype.Component;

/**
 * {@link SecureJacksonRedisSerializer} 的工厂组件 —— 把「序列化器装配」收敛为
 * Spring 注入的单一构造点。
 *
 * <p>生产装配点有两处:
 * <ol>
 *   <li>{@code RedisConnectionConfiguration#redisCacheTemplate}(生产底层 RedisTemplate)</li>
 *   <li>{@code RedisProCacheConfiguration#defaultRedisCacheConfiguration}(生产
 *       Spring {@code @Cacheable} 走默认配置)</li>
 * </ol>
 * 两处的「SerializerProperties → 5 个入参 → ctor」必须严格一致,任一处漂移会
 * 产生 wired/unwired 双轨 bug。本工厂把该装配折叠为单点,消除镜像漂移。
 *
 * <p>本工厂输出与「直接调 5-arg ctor」完全等价。
 */
@Component
class SecureJacksonSerializerFactory {

    /**
     * 给定 {@link ObjectMapper} 与 {@link RedisProCacheProperties.SerializerProperties},
     * 构造一个完全装配好的 {@link SecureJacksonRedisSerializer}。
     *
     * @param objectMapper    复用的 Jackson ObjectMapper 拷贝
     * @param serializerProps 待装配的序列化器属性(白名单 + 失败策略 + 类型标签 + 多态开关)
     * @return 一个新实例,后续 {@code serialize}/{@code deserialize} 直接可用
     */
    public SecureJacksonRedisSerializer create(
            ObjectMapper objectMapper,
            RedisProCacheProperties.SerializerProperties serializerProps) {
        return new SecureJacksonRedisSerializer(
                objectMapper,
                serializerProps.getAllowedPackagePrefixes(),
                serializerProps.isFailOnUnknownType(),
                serializerProps.getTypeProperty(),
                serializerProps.isPolymorphicTypingEnabled());
    }
}
