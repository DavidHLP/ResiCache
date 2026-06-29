package io.github.davidhlp.spring.cache.redis.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;

import org.springframework.stereotype.Component;

/**
 * {@link SecureJacksonRedisSerializer} 的工厂组件 —— 把「序列化器装配」从
 * 配置类内联逻辑里抽出,作为 Spring 注入的单一构造点。
 *
 * <p><b>背景</b>:Round 5 (commit {@code 5949d29}) 修了 {@code resi-cache.serializer.*}
 * 属性在 {@code RedisConnectionConfiguration#redisCacheTemplate} 上静默忽略
 * 的 bug,顺手把 5-arg ctor 的调用在三个装配点镜像了:
 * <ol>
 *   <li>{@code RedisConnectionConfiguration#redisCacheTemplate}(生产底层 RedisTemplate)</li>
 *   <li>{@code RedisProCacheConfiguration#defaultRedisCacheConfiguration}(生产
 *       Spring {@code @Cacheable} 走默认配置)</li>
 *   <li>{@code TestRedisConfiguration#redisCacheTemplate}(测试镜像 {@code @Primary})</li>
 * </ol>
 * 三处构造参数必须严格一致,任何一处漂移就会再次产生 wired/unwired 双轨 bug。本类把
 * 这三处装配的「SerializerProperties → 5 个入参 → ctor」折叠为单点,Round 11。
 *
 * <p><b>非破坏</b>:本工厂输出与「直接调 5-arg ctor」完全等价,所有现有配置行为
 * (Round 5 + 9 + 10) 不变;Round 5 起的 673 测试 = regression 基线。
 *
 * <p><b>未做</b>:TestRedisConfiguration 也应注入本工厂,但它已是测试镜像 + 测试
 * 路径,不依赖 Spring 上下文外装配,所以保留其手工构造(只是少一处镜像负担
 * 的来源);若日后 test config 也想统一,改注入即可。
 */
@Component
public class SecureJacksonSerializerFactory {

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
