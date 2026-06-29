package io.github.davidhlp.spring.cache.redis.config;

import com.example.round5.CustomDomainValue;
import io.github.davidhlp.spring.cache.redis.AbstractRedisIntegrationTest;
import io.github.davidhlp.spring.cache.redis.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 5 regression IT — verifies that the low-level
 * {@code RedisConnectionConfiguration#redisCacheTemplate} bean (the one used by
 * {@link io.github.davidhlp.spring.cache.redis.cache.RedisProCacheWriter} for
 * direct {@code opsForValue/HashOperations}) actually honors the
 * {@code resi-cache.serializer.*} configuration properties, instead of falling
 * back to {@link io.github.davidhlp.spring.cache.redis.serialization.SecureJacksonRedisSerializer}
 * defaults (whitelist = {@code [io.github.davidhlp]}, polymorphic typing off).
 *
 * <p>The companion {@code RedisProCacheConfiguration#defaultRedisCacheConfiguration}
 * (path used by Spring {@code @Cacheable} infrastructure) already wires the
 * properties at lines 63-69 — this IT covers the previously unwired sister site.
 *
 * <p>Per COMPETITIVENESS_GUIDE.md §3 pillar B1 first-contact consistency: a
 * property documented in CHANGELOG must behave identically in every code path.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("RedisConnectionConfiguration serializer property wiring")
class RedisConnectionConfigurationTest extends AbstractRedisIntegrationTest {

    /**
     * 白名单需同时包含测试 POJO 包 {@code com.example.round5} 与框架内部 envelope
     * 包 {@code io.github.davidhlp.spring.cache.redis.serialization}(VersionEnvelope,
     * CachedValue, NullValue 等)。打开多态类型信息,使得 {@code @class} 元数据会被写入
     * 序列化 JSON 中,从而真正触发 {@code SecureJacksonRedisSerializer#validateTypeIds}
     * 的白名单校验路径。
     *
     * <p>Bug 状态下(未注入 properties)两个属性被忽略,序列化器回退到默认:多态类型
     * 信息关闭 + 白名单 {@code [io.github.davidhlp]}。roundtrip 后的对象类型会降级为
     * {@code LinkedHashMap}(class 信息丢失)+ 不抛异常(whitelist 检查未触发)。
     */
    @DynamicPropertySource
    static void serializerProperties(DynamicPropertyRegistry registry) {
        registry.add("resi-cache.serializer.allowed-package-prefixes", () -> "com.example.round5,io.github.davidhlp.spring.cache.redis.serialization");
        registry.add("resi-cache.serializer.polymorphic-typing-enabled", () -> "true");
        registry.add("resi-cache.serializer.fail-on-unknown-type", () -> "true");
    }

    @Autowired
    private RedisTemplate<String, Object> redisCacheTemplate;

    private ValueOperations<String, Object> valueOps;

    @BeforeEach
    void setUp() {
        valueOps = redisCacheTemplate.opsForValue();
        redisCacheTemplate.getConnectionFactory().getConnection().flushDb();
    }

    @Test
    @DisplayName("redisCacheTemplate honors resi-cache.serializer.* properties end-to-end")
    void redisCacheTemplate_honorsSerializerProperties() {
        // Arrange: 一个类型在自定义白名单 com.example.round5 的对象
        CustomDomainValue original = new CustomDomainValue(42L, "round-5-regression");
        String key = "round5::custom::42";

        // Act: 通过 redisCacheTemplate 写入 + 读回
        valueOps.set(key, original);
        Object roundtripped = valueOps.get(key);

        // Assert (1): 读回结果非 null + 类型是原 POJO(白名单 + 多态类型信息均被尊重)
        assertThat(roundtripped)
                .as("redisCacheTemplate must deserialize back to the original POJO type, "
                    + "confirming both polymorphicTypingEnabled=true and the custom "
                    + "whitelist prefix are wired through (Round 5 fix)")
                .isNotNull()
                .isInstanceOf(CustomDomainValue.class);

        // Assert (2): 字段值往返保持
        CustomDomainValue restored = (CustomDomainValue) roundtripped;
        assertThat(restored.getId()).isEqualTo(42L);
        assertThat(restored.getLabel()).isEqualTo("round-5-regression");
    }
}
