package io.github.davidhlp.spring.cache.redis.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RedisCacheHealthIndicator seam")
class RedisCacheHealthIndicatorTest {

    @Test
    @DisplayName("reports Redis connectivity and protection degradation separately")
    void reportsConnectivityAndProtectionDegradation() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        SyncSupport syncSupport = mock(SyncSupport.class);
        when(template.execute(any(RedisCallback.class))).thenReturn("PONG");
        when(syncSupport.isDegraded()).thenReturn(true);

        RedisCacheHealthIndicator indicator = new RedisCacheHealthIndicator(
                template, provider(syncSupport));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("status", "connected")
                .containsEntry("protection.degraded", "local-only")
                .containsKey("protection.degraded.reason");
    }

    @Test
    @DisplayName("degraded state is reported on every probe but warns only once per context")
    void degradedState_warnsOncePerContext() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        SyncSupport syncSupport = mock(SyncSupport.class);
        when(template.execute(any(RedisCallback.class))).thenReturn("PONG");
        when(syncSupport.isDegraded()).thenReturn(true);

        RedisCacheHealthIndicator indicator = new RedisCacheHealthIndicator(
                template, provider(syncSupport));

        Logger logger = (Logger) LoggerFactory.getLogger(RedisCacheHealthIndicator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Health first = indicator.health();
            Health second = indicator.health();

            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .isEqualTo("protection.degraded=local-only: sync=true 但无分布式锁后端,降级为单 JVM synchronized"));
            assertThat(List.of(first, second)).allSatisfy(health -> {
                assertThat(health.getStatus()).isEqualTo(Status.UP);
                assertThat(health.getDetails())
                        .containsEntry("protection.degraded", "local-only")
                        .containsKey("protection.degraded.reason");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("reports unexpected ping response as down")
    void reportsUnexpectedPingResponseAsDown() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        when(template.execute(any(RedisCallback.class))).thenReturn("NOPE");

        Health health = new RedisCacheHealthIndicator(
                template, provider(null)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("status")).isEqualTo("unexpected response: NOPE");
    }

    @Test
    @DisplayName("reports ping exception as down with the failure detail")
    void reportsPingExceptionAsDown() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        when(template.execute(any(RedisCallback.class))).thenThrow(new IllegalStateException("Redis unavailable"));

        Health health = new RedisCacheHealthIndicator(
                template, provider(null)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "Redis unavailable");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
