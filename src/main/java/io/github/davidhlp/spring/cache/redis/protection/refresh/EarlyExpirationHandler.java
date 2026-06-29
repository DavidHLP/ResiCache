package io.github.davidhlp.spring.cache.redis.protection.refresh;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.model.*;


import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.cache.CachedValue;
import io.github.davidhlp.spring.cache.redis.protection.avalanche.DefaultTtlPolicy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;

/**
 * 提前过期处理器，防止缓存雪崩
 *
 * <p>职责：
 * <ul>
 *   <li>检查缓存是否需要提前过期</li>
 *   <li>同步模式：返回 miss 触发刷新</li>
 *   <li>异步模式：安排后台刷新，缩短 TTL</li>
 * </ul>
 *
 * <p>设计说明：
 * <ul>
 *   <li>原设计：提前过期逻辑在 ActualCacheHandler 中，通过回调实现</li>
 *   <li>新设计：独立为 Handler，直接检查缓存值并做出决策</li>
 *   <li>GET 操作时，先获取缓存值，判断是否需要提前过期</li>
 *   <li>如果需要同步刷新，返回 skipAll，ActualCacheHandler 检查标记后返回 miss</li>
 * </ul>
 */
@Slf4j
@Component
@HandlerPriority(HandlerOrder.EARLY_EXPIRATION)
public class EarlyExpirationHandler extends AbstractCacheHandler {

    /** 上下文属性键：提前过期决策 */
    private static final String DECISION_KEY = "earlyExpiration.decision";

    private static final long REFRESH_GRACE_PERIOD_SECONDS = 5;

    private static final String ATOMIC_TTL_SHORTEN_SCRIPT =
        "local current = redis.call('get', KEYS[1]) " +
        "if current == ARGV[1] then " +
        "    redis.call('expire', KEYS[1], ARGV[2]) " +
        "    return 1 " +
        "else " +
        "    return 0 " +
        "end";

    private final DefaultTtlPolicy ttlPolicy;
    private final EarlyExpirationSupport earlyExpirationSupport;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheStatisticsCollector statistics;
    private final ValueOperations<String, Object> valueOperations;

    /**
     * Path C 后续(WS-1.4) — per-handler tag:同步提前过期触发事件计数。
     * <p>ObjectProvider 允许 MeterRegistry 缺失 — 没有 registry 时 earlyRefreshTriggeredCounter
     * 静默 no-op,行为不变。
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private Counter earlyRefreshTriggeredCounter;

    public EarlyExpirationHandler(DefaultTtlPolicy ttlPolicy,
                                  EarlyExpirationSupport earlyExpirationSupport,
                                  RedisTemplate<String, Object> redisTemplate,
                                  CacheStatisticsCollector statistics,
                                  ValueOperations<String, Object> valueOperations,
                                  ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.ttlPolicy = ttlPolicy;
        this.earlyExpirationSupport = earlyExpirationSupport;
        this.redisTemplate = redisTemplate;
        this.statistics = statistics;
        this.valueOperations = valueOperations;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @PostConstruct
    void initMetrics() {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            this.earlyRefreshTriggeredCounter = Counter.builder("resicache.handler.early-refresh.triggered")
                    .description("Early refresh triggered (sync=true early expiration path, ActualCacheHandler skipped)")
                    .register(registry);
        }
    }

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 仅 GET 操作且启用了提前过期
        return context.getOperation() == CacheOperation.GET
               && context.getCacheOperation() != null
               && context.getCacheOperation().isEnableEarlyExpiration();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        // 先尝试获取缓存值
        CachedValue cachedValue = (CachedValue) valueOperations.get(context.getRedisKey());

        // 将预取的缓存值存入上下文，供 ActualCacheHandler 复用，避免双重 Redis GET
        context.setAttribute(CacheContext.AttributeKey.PREFETCHED_CACHED_VALUE, cachedValue);

        if (cachedValue == null || cachedValue.checkExpired()) {
            // 缓存不存在或已过期，继续执行后续 Handler
            return HandlerResult.continueChain();
        }

        // 检查是否需要提前过期
        EarlyExpirationDecision decision = checkEarlyExpiration(context, cachedValue);
        context.setAttribute(DECISION_KEY, decision);

        if (decision.needsRefresh() && decision.isSync()) {
            // 同步提前过期：返回 skipAll，ActualCacheHandler 会返回 miss
            context.setAttribute(CacheContext.AttributeKey.EARLY_EXPIRATION_SKIPPED, true);
            log.debug("Sync early-expiration triggered, skipping actual cache: cacheName={}, key={}",
                      context.getCacheName(), context.getRedisKey());
            // WS-1.4 per-handler tag:同步提前过期触发事件计数
            if (earlyRefreshTriggeredCounter != null) {
                earlyRefreshTriggeredCounter.increment();
            }
            return HandlerResult.skipAll();
        }

        // 不需要刷新或异步刷新，继续执行
        return HandlerResult.continueChain();
    }

    /**
     * 检查是否需要提前过期
     *
     * @param context 缓存上下文
     * @param cachedValue 缓存的值
     * @return 提前过期决策
     */
    private EarlyExpirationDecision checkEarlyExpiration(CacheContext context, CachedValue cachedValue) {
        boolean shouldRefresh = ttlPolicy.shouldEarlyExpiration(
            cachedValue.getCreatedTime(),
            cachedValue.getTtl(),
            context.getCacheOperation().getEarlyExpirationThreshold()
        );

        if (!shouldRefresh) {
            return EarlyExpirationDecision.noRefresh();
        }

        EarlyExpirationMode mode = resolveMode(context);

        log.info("Pre-refresh needed: cacheName={}, key={}, mode={}, remainingTtl={}s",
                 context.getCacheName(), context.getRedisKey(), mode, cachedValue.getRemainingTtl());

        if (mode == EarlyExpirationMode.ASYNC) {
            scheduleAsyncRefresh(context, cachedValue);
            return EarlyExpirationDecision.asyncRefresh();
        }

        statistics.incMisses(context.getCacheName());
        return EarlyExpirationDecision.syncRefresh();
    }

    /**
     * 解析提前过期模式
     */
    private EarlyExpirationMode resolveMode(CacheContext context) {
        EarlyExpirationMode mode = context.getCacheOperation().getEarlyExpirationMode();
        return mode != null ? mode : EarlyExpirationMode.SYNC;
    }

    /**
     * 安排异步提前过期任务
     */
    private void scheduleAsyncRefresh(CacheContext context, CachedValue cachedValue) {
        String redisKey = context.getRedisKey();
        String cacheName = context.getCacheName();

        earlyExpirationSupport.submitAsyncRefresh(redisKey, () -> {
            try {
                CachedValue liveValue = (CachedValue) valueOperations.get(redisKey);

                if (liveValue == null) {
                    log.debug("Async early-expiration: key already missing: {}", redisKey);
                    return;
                }

                // 检查 TTL 是否即将过期（避免刷新已过期数据）
                long remainingTtl = liveValue.getRemainingTtl();
                if (remainingTtl > 0 && remainingTtl < REFRESH_GRACE_PERIOD_SECONDS) {
                    log.debug("Async early-expiration skipped: key={} remainingTtl={}s is below grace period {}s",
                              redisKey, remainingTtl, REFRESH_GRACE_PERIOD_SECONDS);
                    return;
                }

                boolean shortened = atomicShortenTtlIfValueUnchanged(redisKey, cachedValue);
                if (shortened) {
                    log.debug("Async early-expiration shortened TTL: key={}, gracePeriod={}s",
                              redisKey, REFRESH_GRACE_PERIOD_SECONDS);
                } else {
                    log.debug("Async early-expiration skipped: value changed: {}", redisKey);
                }
            } catch (Exception ex) {
                log.error("Async early-expiration failed: cacheName={}, key={}", cacheName, redisKey, ex);
            }
        });

        log.info("Async early-expiration scheduled: cacheName={}, key={}", cacheName, redisKey);
    }

    private boolean atomicShortenTtlIfValueUnchanged(String redisKey, CachedValue expectedValue) {
        return Boolean.TRUE.equals(redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) connection -> {
            RedisSerializer<String> keySerializer = redisTemplate.getStringSerializer();
            @SuppressWarnings("unchecked")
            RedisSerializer<Object> valueSerializer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();

            byte[] keyBytes = keySerializer.serialize(redisKey);
            byte[] expectedValueBytes = valueSerializer.serialize(expectedValue);
            byte[] ttlBytes = String.valueOf(REFRESH_GRACE_PERIOD_SECONDS).getBytes(StandardCharsets.UTF_8);

            Long result = connection.eval(
                ATOMIC_TTL_SHORTEN_SCRIPT.getBytes(StandardCharsets.UTF_8),
                ReturnType.INTEGER,
                1,
                keyBytes, expectedValueBytes, ttlBytes
            );
            return result != null && result == 1;
        }));
    }

    /**
     * 获取提前过期决策（供其他 Handler 使用）
     *
     * @param context 缓存上下文
     * @return 提前过期决策
     */
    public static EarlyExpirationDecision getDecision(CacheContext context) {
        return context.getAttribute(DECISION_KEY, EarlyExpirationDecision.noRefresh());
    }
}
