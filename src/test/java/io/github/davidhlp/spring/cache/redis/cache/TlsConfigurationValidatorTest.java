package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TlsConfigurationValidator seam")
class TlsConfigurationValidatorTest {

    @Test
    @DisplayName("allows plaintext without credentials for backward compatibility")
    void allowsPlaintextWithoutCredentials() {
        TlsConfigurationValidator validator = validator(false, false, null, null);

        assertThat(validator.shouldFail()).isFalse();
        assertThat(validator.shouldWarn()).isFalse();
        assertThatCode(validator::onApplicationReady).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("warns, but does not block, plaintext credentials")
    void warnsForPlaintextCredentials() {
        TlsConfigurationValidator validator = validator(false, false, "cache-user", "secret");

        assertThat(validator.shouldFail()).isFalse();
        assertThat(validator.shouldWarn()).isTrue();
        assertThatCode(validator::onApplicationReady).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fails fast when TLS is required but disabled")
    void failsWhenTlsRequiredButDisabled() {
        TlsConfigurationValidator validator = validator(false, true, null, null);

        assertThat(validator.shouldFail()).isTrue();
        assertThatThrownBy(validator::onApplicationReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tls-required=true")
                .hasMessageContaining("tls-enabled=false");
    }

    @Test
    @DisplayName("accepts enabled TLS even when credentials and TLS are required")
    void acceptsEnabledTls() {
        TlsConfigurationValidator validator = validator(true, true, "cache-user", "secret");

        assertThat(validator.shouldFail()).isFalse();
        assertThat(validator.shouldWarn()).isFalse();
        assertThatCode(validator::onApplicationReady).doesNotThrowAnyException();
    }

    private static TlsConfigurationValidator validator(
            boolean tlsEnabled, boolean tlsRequired, String username, String password) {
        RedisProCacheProperties properties = new RedisProCacheProperties();
        properties.getRedis().setTlsEnabled(tlsEnabled);
        properties.getRedis().setTlsRequired(tlsRequired);
        properties.getRedis().setUsername(username);
        properties.getRedis().setPassword(password);
        return new TlsConfigurationValidator(properties);
    }
}
