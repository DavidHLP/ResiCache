package io.github.davidhlp.spring.cache.redis.cache;





import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.SerializationException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 {@link SecureJacksonSerializerFactory} 的
 * {@code create(ObjectMapper, SerializerProperties)} 真的把 props 传下去,
 * 不只是「调了一下 ctor」。
 *
 * <p>关键 verifying:故意把 whitelist 设为非默认(只含
 * {@code com.example.custom},不含 {@code io.github.davidhlp})。然后试图
 * roundtrip 一个 {@link CachedValue}(@class 为
 * {@code io.github.davidhlp.spring.cache.redis.cache.CachedValue})。
 * 如果 factory 真的把 props 穿下去,白名单检查会拒
 * CachedValue(抛 SerializationException);如果 factory 只用默认
 * (即没接 props),roundtrip 会成功。
 */
@DisplayName("SecureJacksonSerializerFactory Tests")
class SecureJacksonSerializerFactoryTest {

    private final SecureJacksonSerializerFactory factory = new SecureJacksonSerializerFactory();

    @Test
    @DisplayName("create returns a serializer wired with the props whitelist (not defaults)")
    void create_wiresPropsWhitelistIntoSerializer() {
        // 故意把白名单限制为只有 com.example.custom — 不含 io.github.davidhlp
        RedisProCacheProperties.SerializerProperties props =
                new RedisProCacheProperties.SerializerProperties();
        props.setAllowedPackagePrefixes(List.of("com.example.custom"));
        props.setPolymorphicTypingEnabled(true);
        props.setFailOnUnknownType(true);

        SecureJacksonRedisSerializer serializer = factory.create(new ObjectMapper(), props);

        // CachedValue 默认类,@class 指向 io.github.davidhlp.spring.cache.redis.cache.CachedValue
        // 既不在 com.example.custom,也不在 java.lang/java.time/java.math/枚举 java.util 集合内
        // → roundtrip 必须抛白名单 SerializationException
        CachedValue payload = CachedValue.of("payload-value", 60L);
        assertThatThrownBy(() -> {
            serializer.deserialize(serializer.serialize(payload));
        })
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("whitelist");
    }

    @Test
    @DisplayName("create returns non-null serializer for default props")
    void create_returnsSerializerForDefaults() {
        // 默认 props(没特殊配置) — 必须返非 null + String roundtrip 成功
        RedisProCacheProperties.SerializerProperties props =
                new RedisProCacheProperties.SerializerProperties();

        SecureJacksonRedisSerializer serializer = factory.create(new ObjectMapper(), props);

        assertThat(serializer).isNotNull();
        assertThat(serializer.deserialize(serializer.serialize("hello"))).isEqualTo("hello");
    }
}
