package io.github.davidhlp.spring.cache.redis.observability;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis cache health indicator for Spring Boot Actuator.
 *
 * <p>Path C 后续(WS-1.4)健康级联:
 * <ol>
 *   <li>Redis PING(基础 — Redis 自身连通性)</li>
 *   <li>Protection 机制级联 — sync=true 但无分布式锁后端(Redisson 缺失)时,
 *       报告 {@code protection.degraded=local-only}(不阻断整体 UP 状态,
 *       但暴露安全降级便于运维感知)。本类标 {@code Status.UP} + detail 记录</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "resi-cache.metrics", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RedisCacheHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, Object> redisCacheTemplate;
    private final SyncSupport syncSupport;
    private final RedisProCacheProperties properties;

    public RedisCacheHealthIndicator(RedisTemplate<String, Object> redisCacheTemplate,
                                     ObjectProvider<SyncSupport> syncSupportProvider,
                                     ObjectProvider<RedisProCacheProperties> propertiesProvider) {
        this.redisCacheTemplate = redisCacheTemplate;
        // ObjectProvider null-safe:无 Redisson + 无 sync 配置时 SyncSupport 可能不存在
        this.syncSupport = syncSupportProvider.getIfAvailable();
        this.properties = propertiesProvider.getIfAvailable();
    }

    @Override
    public Health health() {
        Health.Builder builder;
        try {
            builder = executePingBuilder();
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }

        // WS-1.4 health 级联:protection 机制健康(仅在 Redis 自身 UP 时检查)
        if (syncSupport != null && syncSupport.isDegraded()) {
            // sync=true 但无分布式锁后端 — 降级为 local-only(单 JVM 锁,跨实例不协调)
            // 状态仍是 UP(Redis 可用),但 detail 记录 protection.degraded
            log.warn("protection.degraded=local-only: sync=true 但无分布式锁后端,降级为单 JVM synchronized");
            builder = builder
                    .withDetail("protection.degraded", "local-only")
                    .withDetail("protection.degraded.reason",
                            "sync=true configured but no distributed lock manager (Redisson) on classpath");
        }

        return builder.build();
    }

    private Health.Builder executePingBuilder() {
        String pong = redisCacheTemplate.execute((RedisCallback<String>) connection -> {
            connection.ping();
            return "PONG";
        });

        if ("PONG".equals(pong)) {
            return Health.up().withDetail("status", "connected");
        } else {
            return Health.down().withDetail("status", "unexpected response: " + pong);
        }
    }
}
