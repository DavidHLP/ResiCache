package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.core.writer.CachedValue;
import io.github.davidhlp.spring.cache.redis.core.writer.support.protect.ttl.TtlPolicy;
import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;
import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshSupport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 预刷新处理器，防止缓存雪崩
 *
 * <p>职责：
 * <ul>
 *   <li>检查缓存是否需要预刷新</li>
 *   <li>同步模式：返回 miss 触发刷新</li>
 *   <li>异步模式：安排后台刷新，缩短 TTL</li>
 * </ul>
 *
 * <p>设计说明：
 * <ul>
 *   <li>原设计：预刷新逻辑在 ActualCacheHandler 中，通过回调实现</li>
 *   <li>新设计：独立为 Handler，直接检查缓存值并做出决策</li>
 *   <li>GET 操作时，先获取缓存值，判断是否需要预刷新</li>
 *   <li>如果需要同步刷新，返回 skipAll，ActualCacheHandler 检查标记后返回 miss</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerPriority(HandlerOrder.PRE_REFRESH)
public class PreRefreshHandler extends AbstractCacheHandler {

    /** 上下文属性键：预刷新决策 */
    private static final String DECISION_KEY = "preRefresh.decision";

    private static final long REFRESH_GRACE_PERIOD_SECONDS = 5;

    private final TtlPolicy ttlPolicy;
    private final PreRefreshSupport preRefreshSupport;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheStatisticsCollector statistics;
    private final ValueOperations<String, Object> valueOperations;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 仅 GET 操作且启用了预刷新
        return context.getOperation() == CacheOperation.GET
               && context.getCacheOperation() != null
               && context.getCacheOperation().isEnablePreRefresh();
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        // 先尝试获取缓存值
        CachedValue cachedValue = (CachedValue) valueOperations.get(context.getRedisKey());

        if (cachedValue == null || cachedValue.isExpired()) {
            // 缓存不存在或已过期，继续执行后续 Handler
            return HandlerResult.continueChain();
        }

        // 检查是否需要预刷新
        PreRefreshDecision decision = checkPreRefresh(context, cachedValue);
        context.setAttribute(DECISION_KEY, decision);

        if (decision.needsRefresh() && decision.isSync()) {
            // 同步预刷新：返回 skipAll，ActualCacheHandler 会返回 miss
            context.setAttribute("preRefresh.skipped", true);
            log.debug("Sync pre-refresh triggered, skipping actual cache: cacheName={}, key={}",
                      context.getCacheName(), context.getRedisKey());
            return HandlerResult.skipAll();
        }

        // 不需要刷新或异步刷新，继续执行
        return HandlerResult.continueChain();
    }

    /**
     * 检查是否需要预刷新
     *
     * @param context 缓存上下文
     * @param cachedValue 缓存的值
     * @return 预刷新决策
     */
    private PreRefreshDecision checkPreRefresh(CacheContext context, CachedValue cachedValue) {
        boolean shouldRefresh = ttlPolicy.shouldPreRefresh(
            cachedValue.getCreatedTime(),
            cachedValue.getTtl(),
            context.getCacheOperation().getPreRefreshThreshold()
        );

        if (!shouldRefresh) {
            return PreRefreshDecision.noRefresh();
        }

        PreRefreshMode mode = resolveMode(context);

        log.info("Pre-refresh needed: cacheName={}, key={}, mode={}, remainingTtl={}s",
                 context.getCacheName(), context.getRedisKey(), mode, cachedValue.getRemainingTtl());

        if (mode == PreRefreshMode.ASYNC) {
            scheduleAsyncRefresh(context, cachedValue);
            return PreRefreshDecision.asyncRefresh();
        }

        statistics.incMisses(context.getCacheName());
        return PreRefreshDecision.syncRefresh();
    }

    /**
     * 解析预刷新模式
     */
    private PreRefreshMode resolveMode(CacheContext context) {
        PreRefreshMode mode = context.getCacheOperation().getPreRefreshMode();
        return mode != null ? mode : PreRefreshMode.SYNC;
    }

    /**
     * 安排异步预刷新任务
     */
    private void scheduleAsyncRefresh(CacheContext context, CachedValue cachedValue) {
        String redisKey = context.getRedisKey();
        String cacheName = context.getCacheName();
        long originalCreated = cachedValue.getCreatedTime();
        long originalVersion = cachedValue.getVersion();

        preRefreshSupport.submitAsyncRefresh(redisKey, () -> {
            try {
                CachedValue liveValue = (CachedValue) valueOperations.get(redisKey);

                if (liveValue == null) {
                    log.debug("Async pre-refresh: key already missing: {}", redisKey);
                    return;
                }

                if (liveValue.getCreatedTime() == originalCreated
                    && liveValue.getVersion() == originalVersion) {
                    // 缩短 TTL 而非直接删除，避免缓存穿透
                    Boolean expired = redisTemplate.expire(
                        redisKey, Duration.ofSeconds(REFRESH_GRACE_PERIOD_SECONDS));
                    log.debug("Async pre-refresh shortened TTL: key={}, gracePeriod={}s, success={}",
                              redisKey, REFRESH_GRACE_PERIOD_SECONDS, expired);
                } else {
                    log.debug("Async pre-refresh skipped: value changed: {}", redisKey);
                }
            } catch (Exception ex) {
                log.error("Async pre-refresh failed: cacheName={}, key={}", cacheName, redisKey, ex);
            }
        });

        log.info("Async pre-refresh scheduled: cacheName={}, key={}", cacheName, redisKey);
    }

    /**
     * 获取预刷新决策（供其他 Handler 使用）
     *
     * @param context 缓存上下文
     * @return 预刷新决策
     */
    public static PreRefreshDecision getDecision(CacheContext context) {
        return context.getAttribute(DECISION_KEY, PreRefreshDecision.noRefresh());
    }
}
