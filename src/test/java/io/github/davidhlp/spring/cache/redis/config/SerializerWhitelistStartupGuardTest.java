package io.github.davidhlp.spring.cache.redis.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SerializerWhitelistStartupGuard 单元测试
 *
 * <p>验证 {@link RedisProCacheProperties.SerializerProperties#allowedPackagePrefixes}
 * 为空(用户显式清空 / 设为 null)时,启动守卫应发出 WARN 提示用户补回白名单。
 *
 * <p>背景:STABILITY §1 声明 {@code resi-cache.*} property keys 是 stable surface;
 * {@code allowed-package-prefixes} 是 SecureJackson 反序列化的安全门。若用户把列表
 * 配成空,所有非框架内部 type 的反序列化都会抛 SerializationException —— 静默
 * footgun,运行期才暴露。启动期 WARN 是无侵入的 observability 提升,不动 API surface,
 * 不动 default value(默认仍是 {@code [io.github.davidhlp]}),所以非 breaking。
 */
@DisplayName("SerializerWhitelistStartupGuard Tests")
class SerializerWhitelistStartupGuardTest {

    @Nested
    @DisplayName("shouldWarn 谓词")
    class ShouldWarnPredicate {

        @Test
        @DisplayName("allowedPackagePrefixes 为 null → 应 warn")
        void nullListShouldWarn() {
            var props = new RedisProCacheProperties();
            props.getSerializer().setAllowedPackagePrefixes(null);
            var guard = new SerializerWhitelistStartupGuard(props);
            assertThat(guard.shouldWarn()).isTrue();
        }

        @Test
        @DisplayName("allowedPackagePrefixes 为空列表 → 应 warn")
        void emptyListShouldWarn() {
            var props = new RedisProCacheProperties();
            props.getSerializer().setAllowedPackagePrefixes(new ArrayList<>());
            var guard = new SerializerWhitelistStartupGuard(props);
            assertThat(guard.shouldWarn()).isTrue();
        }

        @Test
        @DisplayName("allowedPackagePrefixes 含至少一项 → 不应 warn")
        void populatedListShouldNotWarn() {
            var props = new RedisProCacheProperties();
            props.getSerializer().setAllowedPackagePrefixes(List.of("io.example.app"));
            var guard = new SerializerWhitelistStartupGuard(props);
            assertThat(guard.shouldWarn()).isFalse();
        }

        @Test
        @DisplayName("allowedPackagePrefixes 为默认 [io.github.davidhlp] → 不应 warn")
        void defaultListShouldNotWarn() {
            var props = new RedisProCacheProperties();
            // 直接 new() 不改 setter,走默认 ArrayList<>("io.github.davidhlp")
            var guard = new SerializerWhitelistStartupGuard(props);
            assertThat(guard.shouldWarn()).isFalse();
        }
    }
}
