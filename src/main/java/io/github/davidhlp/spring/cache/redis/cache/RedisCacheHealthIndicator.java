package io.github.davidhlp.spring.cache.redis.cache;




import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis cache health indicator for Spring Boot Actuator.
 *
 * <p>健康级联:
 * <ol>
 *   <li>Redis PING(基础 — Redis 自身连通性)</li>
 *   <li>Protection 机制级联 — 报告 {@link SyncSupport.ProtectionMode} 的实测模式,
 *       均不阻断整体 UP 状态:无分布式锁后端且显式 {@code local-only=true} 时报告
 *       {@code protection.degraded=local-only}(单 JVM 同步,跨实例不协调);无后端且
 *       未启用 local-only 时报告 {@code protection.degraded=fail-fast}(sync=true 操作
 *       将在首次未命中直接失败)。后端存在时不附 protection detail</li>
 * </ol>
 *
 * <p>本指标报告的是 Redis 连通性与 sync 保护状态,与 metrics 无关,故只按
 * Actuator 是否在 classpath 上启用(见 {@code @ConditionalOnClass})。
 */
@Slf4j
@Component
@ConditionalOnClass(HealthIndicator.class)
class RedisCacheHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, Object> redisCacheTemplate;
    private final SyncSupport syncSupport;
    /** 每种非 DISTRIBUTED 模式至多一条 WARN —— 状态本身仍每次响应都报告在 detail 中。 */
    private final AtomicReference<SyncSupport.ProtectionMode> warnedMode = new AtomicReference<>();

    public RedisCacheHealthIndicator(RedisTemplate<String, Object> redisCacheTemplate,
                                     ObjectProvider<SyncSupport> syncSupportProvider) {
        this.redisCacheTemplate = redisCacheTemplate;
        // ObjectProvider null-safe:无 Redisson + 无 sync 配置时 SyncSupport 可能不存在
        this.syncSupport = syncSupportProvider.getIfAvailable();
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

        // protection 机制健康(仅在 Redis 自身 UP 时检查)
        if (syncSupport != null) {
            // detail 报告 SyncSupport 实测的保护模式;后端列表构造期固定,local-only 属性
            // 实时读取,而 health 端点按探针节奏被反复调用 —— 同一模式的恒同 WARN 只是
            // 噪声,每模式至多一条;状态本身仍在每次响应 detail 中报告。
            SyncSupport.ProtectionMode mode = syncSupport.protectionMode();
            if (mode != SyncSupport.ProtectionMode.DISTRIBUTED
                    && !mode.equals(warnedMode.getAndSet(mode))) {
                log.warn(mode == SyncSupport.ProtectionMode.LOCAL_ONLY
                        ? "protection.degraded=local-only: 无分布式锁后端,已按 local-only=true 显式降级为单 JVM synchronized"
                        : "protection.degraded=fail-fast: 无分布式锁后端且未启用 local-only,sync=true 操作将在首次未命中直接失败");
            }
            if (mode == SyncSupport.ProtectionMode.LOCAL_ONLY) {
                builder = builder
                        .withDetail("protection.degraded", "local-only")
                        .withDetail("protection.degraded.reason",
                                "no distributed LockManager bean and "
                                        + "resi-cache.sync-lock.local-only=true: sync=true "
                                        + "degrades to single-JVM synchronized "
                                        + "(not multi-instance protection)");
            } else if (mode == SyncSupport.ProtectionMode.FAIL_FAST) {
                builder = builder
                        .withDetail("protection.degraded", "fail-fast")
                        .withDetail("protection.degraded.reason",
                                "no distributed LockManager bean and "
                                        + "resi-cache.sync-lock.local-only=false: sync=true "
                                        + "operations fail fast on the first cache miss");
            }
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
