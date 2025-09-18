package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.meta.CacheMata;
import com.david.spring.cache.redis.protection.CacheAvalanche;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 缓存操作服务
 * 提供通用的缓存操作方法，从RedisProCache中下沉而来
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheOperationService {

    private final CacheAvalanche cacheAvalanche;

    /**
     * 判断是否需要预刷新
     */
    public boolean shouldPreRefresh(long remainingTtlSec, long configuredTtlSec) {
        if (remainingTtlSec <= 0 || configuredTtlSec <= 0) {
            return false;
        }
        long threshold = Math.max(1L, (long) Math.floor(configuredTtlSec * 0.20d));
        return remainingTtlSec <= threshold;
    }

    /**
     * 判断是否需要预刷新（支持自定义阈值）
     */
    public boolean shouldPreRefresh(long remainingTtlSec, long configuredTtlSec, double threshold) {
        if (remainingTtlSec <= 0 || configuredTtlSec <= 0) {
            return false;
        }
        long thresholdTime = Math.max(1L, (long) Math.floor(configuredTtlSec * threshold));
        return remainingTtlSec <= thresholdTime;
    }

    /**
     * 解析配置的TTL秒数
     */
    public long resolveConfiguredTtlSeconds(@Nullable Object value, @NonNull Object key,
                                          @Nullable RedisCacheConfiguration cacheConfiguration) {
        try {
            if (cacheConfiguration != null && value != null) {
                Duration d = cacheConfiguration.getTtlFunction().getTimeToLive(value, key);
                if (!d.isNegative() && !d.isZero()) {
                    return d.getSeconds();
                }
            }
        } catch (Exception ignore) {
            // 忽略异常
        }
        return -1L;
    }

    /**
     * 获取Redis中缓存的TTL
     */
    public long getCacheTtl(String cacheKey, RedisTemplate<String, Object> redisTemplate) {
        try {
            return redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Failed to get cache TTL: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 执行缓存刷新
     */
    public void doRefresh(CachedInvocation invocation, Object key, String cacheKey, long ttl,
                         CacheRefreshCallback refreshCallback) {
        try {
            Object refreshed = invocation.invoke();
            refreshCallback.putCache(key, refreshed);
            log.info("Refreshed cache, name={}, redisKey={}, oldTtlSec={}, refreshedType={}",
                    refreshCallback.getCacheName(), cacheKey, ttl,
                    refreshed == null ? "null" : refreshed.getClass().getSimpleName());
        } catch (Throwable ex) {
            log.warn("Failed to refresh cache, name={}, redisKey={}, err={}",
                    refreshCallback.getCacheName(), cacheKey, ex.getMessage());
        }
    }

    /**
     * 包装缓存值（如果元信息不存在）
     */
    public Object wrapIfMataAbsent(@Nullable Object value, @NonNull Object key,
                                  @Nullable RedisCacheConfiguration cacheConfiguration) {
        if (value == null) {
            return null;
        }
        if (value instanceof CacheMata) {
            return value;
        }

        long ttlSecs = resolveConfiguredTtlSeconds(value, key, cacheConfiguration);
        // 雪崩保护：通过策略类对 TTL 进行抖动（随机缩短）
        long effectiveTtl = ttlSecs > 0 ? cacheAvalanche.jitterTtlSeconds(ttlSecs) : ttlSecs;
        // 不再计算本地过期时间，仅使用元信息中的 TTL，并由 Redis 统一管理过期
        return CacheMata.builder().ttl(effectiveTtl).value(value).build();
    }

    /**
     * 从存储值中解包缓存值
     */
    public Object fromStoreValue(@Nullable Object storeValue) {
        if (storeValue instanceof CacheMata) {
            return ((CacheMata) storeValue).getValue();
        }
        return storeValue;
    }

    /**
     * 应用抖动后的过期时间
     */
    public void applyLitteredExpire(Object key, Object toStore, String cacheKey,
                                   RedisTemplate<String, Object> redisTemplate) {
        try {
            if (toStore instanceof CacheMata meta && meta.getTtl() > 0) {
                long seconds = meta.getTtl();
                if (seconds > 0) {
                    redisTemplate.expire(cacheKey, seconds, TimeUnit.SECONDS);
                }
            }
        } catch (Exception ignore) {
            // 忽略异常
        }
    }

    /**
     * 判断 putIfAbsent 前该 key 是否不存在
     */
    public boolean wasKeyAbsentBeforePut(String cacheKey, RedisTemplate<String, Object> redisTemplate) {
        try {
            Long ttl = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
            return ttl == -2L; // -2 表示 Redis 中 key 不存在
        } catch (Exception ignore) {
            return false;
        }
    }

    /**
     * 缓存刷新回调接口
     */
    public interface CacheRefreshCallback {
        void putCache(Object key, Object value);
        String getCacheName();
    }
}