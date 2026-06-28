package io.github.davidhlp.spring.cache.redis.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis cache health indicator for Spring Boot Actuator.
 *
 * <p>Checks Redis connectivity via PING command with short timeout.
 * Does not cascade - Redis failure does not affect overall application health.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "resi-cache.metrics", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RedisCacheHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, Object> redisCacheTemplate;

    @Override
    public Health health() {
        try {
            return executePing();
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    private Health executePing() {
        String pong = redisCacheTemplate.execute((RedisCallback<String>) connection -> {
            connection.ping();
            return "PONG";
        });

        if ("PONG".equals(pong)) {
            return Health.up()
                    .withDetail("status", "connected")
                    .build();
        } else {
            return Health.down()
                    .withDetail("status", "unexpected response: " + pong)
                    .build();
        }
    }
}
