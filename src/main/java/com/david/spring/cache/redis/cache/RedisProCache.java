package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.locks.DistributedLock;
import com.david.spring.cache.redis.locks.LockUtils;
import com.david.spring.cache.redis.meta.CacheMata;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class RedisProCache extends RedisCache {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheInvocationRegistry registry;
    private final Executor executor;
    private final RedisCacheConfiguration cacheConfiguration;
    private final DistributedLock distributedLock;

    public RedisProCache(
            String name,
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration cacheConfiguration,
            RedisTemplate<String, Object> redisTemplate,
            CacheInvocationRegistry registry,
            Executor executor,
            DistributedLock distributedLock) {
        super(name, cacheWriter, cacheConfiguration);
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.registry = Objects.requireNonNull(registry);
        this.executor = Objects.requireNonNull(executor);
        this.cacheConfiguration = Objects.requireNonNull(cacheConfiguration);
        this.distributedLock = Objects.requireNonNull(distributedLock);
    }

    @Override
    @Nullable
    public ValueWrapper get(@NonNull Object key) {
        ValueWrapper valueWrapper = super.get(key);
        if (valueWrapper == null) {
            return null;
        }

        try {
            String cacheKey = createCacheKey(key);
            long ttl = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
            long configuredTtl = resolveConfiguredTtlSeconds(valueWrapper.get(), key);
            log.debug(
                    "Pre-refresh check: name={}, redisKey={}, ttlSec={}, configuredTtlSec={}",
                    getName(),
                    cacheKey,
                    ttl,
                    configuredTtl);
            if (ttl >= 0 && shouldPreRefresh(ttl, configuredTtl)) {
                ReentrantLock lock = registry.obtainLock(getName(), key);
                executor.execute(
                        () -> {
                            try {
                                // 第一次检查，避免不必要刷新
                                long ttl2 = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
                                long configuredTtl2 =
                                        resolveConfiguredTtlSeconds(valueWrapper.get(), key);
                                if (ttl2 < 0 || !shouldPreRefresh(ttl2, configuredTtl2)) {
                                    log.debug(
                                            "Pre-refresh skipped after first-check: name={}, redisKey={}, ttl2Sec={}, configuredTtl2Sec={}",
                                            getName(),
                                            cacheKey,
                                            ttl2,
                                            configuredTtl2);
                                    return;
                                }

                                String distKey = "cache:refresh:" + cacheKey;
                                long leaseTimeSec = Math.max(5L, Math.min(30L, ttl2));
                                log.debug(
                                        "Pre-refresh attempt: name={}, redisKey={}, distKey={}, leaseTimeSec={}",
                                        getName(),
                                        cacheKey,
                                        distKey,
                                        leaseTimeSec);

                                boolean executed =
                                        LockUtils.runWithLocalTryThenDistTry(
                                                lock,
                                                distributedLock,
                                                distKey,
                                                0L,
                                                leaseTimeSec,
                                                TimeUnit.SECONDS,
                                                () -> {
                                                    // 第二次检查（拿到双锁后再次确认）
                                                    long t3 =
                                                            redisTemplate.getExpire(
                                                                    cacheKey, TimeUnit.SECONDS);
                                                    long c3 =
                                                            resolveConfiguredTtlSeconds(
                                                                    valueWrapper.get(), key);
                                                    if (t3 < 0 || !shouldPreRefresh(t3, c3)) {

                                                        log.debug(
                                                                "Pre-refresh skipped after second-check: name={}, redisKey={}, t3Sec={}, c3Sec={}",
                                                                getName(),
                                                                cacheKey,
                                                                t3,
                                                                c3);

                                                        return;
                                                    }

                                                    log.debug(
                                                            "Pre-refresh acquired locks, start refresh: name={}, redisKey={}",
                                                            getName(),
                                                            cacheKey);

                                                    registry.get(getName(), key)
                                                            .ifPresent(
                                                                    invocation ->
                                                                            doRefresh(
                                                                                    invocation,
                                                                                    key,
                                                                                    cacheKey,
                                                                                    t3));
                                                });

                                log.debug(
                                        "Pre-refresh executed={} name={}, redisKey={}, distKey={}, leaseTimeSec={}",
                                        executed,
                                        getName(),
                                        cacheKey,
                                        distKey,
                                        leaseTimeSec);

                            } catch (Exception ex) {
                                log.warn(
                                        "Cache pre-refresh error, name={}, key={}, err= {}",
                                        getName(),
                                        cacheKey,
                                        ex.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            log.debug("Skip pre-refresh due to error: {}", e.getMessage());
        }
        // 由 fromStoreValue 负责解包，直接返回
        return valueWrapper;
    }

    private boolean shouldPreRefresh(long remainingTtlSec, long configuredTtlSec) {
        if (remainingTtlSec <= 0 || configuredTtlSec <= 0) return false;
        long threshold = Math.max(1L, (long) Math.floor(configuredTtlSec * 0.20d));
        return remainingTtlSec <= threshold;
    }

    private long resolveConfiguredTtlSeconds(@Nullable Object value, @NonNull Object key) {
        try {
            if (cacheConfiguration != null) {
                if (value == null) return -1L; // 避免将 @Nullable 传入要求 @NonNull 的 API
                Duration d = cacheConfiguration.getTtlFunction().getTimeToLive(value, key);
                if (!d.isNegative() && !d.isZero()) {
                    return d.getSeconds();
                }
            }
        } catch (Exception ignore) {
        }
        return -1L;
    }

    private void doRefresh(CachedInvocation invocation, Object key, String cacheKey, long ttl) {
        try {
            Object refreshed = invocation.invoke();
            // 使用包装写回，重置 TTL
            this.put(key, refreshed);
            log.info(
                    "Refreshed cache, name={}, redisKey={}, oldTtlSec={}, refreshedType={}",
                    getName(),
                    cacheKey,
                    ttl,
                    refreshed == null ? "null" : refreshed.getClass().getSimpleName());
        } catch (Throwable ex) {
            log.warn(
                    "Failed to refresh cache, name={}, redisKey={}, err={}",
                    getName(),
                    cacheKey,
                    ex.getMessage());
        }
    }

    @Override
    public void put(@NonNull Object key, @Nullable Object value) {
        Object toStore = wrapIfMataAbsent(value, key);
        super.put(key, toStore);
        // 雪崩保护：统一调用
        applyLitteredExpire(key, toStore);
    }

    @Override
    @NonNull
    public ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
        Object toStore = wrapIfMataAbsent(value, key);
        boolean firstInsert = wasKeyAbsentBeforePut(key);
        ValueWrapper previous = super.putIfAbsent(key, toStore);
        if (firstInsert) {
            applyLitteredExpire(key, toStore);
        }
        return previous;
    }

    @Override
    @Nullable
    public <T> T get(@NonNull Object key, @Nullable Class<T> type) {
        ValueWrapper wrapper = this.get(key);
        if (wrapper == null) return null;
        Object val = wrapper.get();
        if (type == null || type == Object.class) {
            @SuppressWarnings("unchecked")
            T casted = (T) val;
            return casted;
        }
        return (type.isInstance(val) ? type.cast(val) : null);
    }

    @Override
    @NonNull
    @SuppressWarnings("unchecked")
    public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
        ValueWrapper wrapper = this.get(key);
        if (wrapper != null) {
            Object val = wrapper.get();
            if (val != null) {
                return (T) val;
            }
            // 若命中但值为 null，回退到加载器
        }
        // 首次加载：本地锁 -> 分布式锁，双重检查，避免缓存击穿与数据库过载
        String cacheKey = createCacheKey(key);
        ReentrantLock localLock = registry.obtainLock(getName(), key);
        String distKey = "cache:load:" + cacheKey;
        long leaseTimeSec = 30L; // 防御性租期，避免锁永久占用

        log.debug(
                "First-load attempt: name={}, redisKey={}, distKey={}, leaseTimeSec={}",
                getName(),
                cacheKey,
                distKey,
                leaseTimeSec);

        try {
            T result =
                    LockUtils.runWithLocalBlockThenDistBlock(
                            localLock,
                            distributedLock,
                            distKey,
                            leaseTimeSec,
                            TimeUnit.SECONDS,
                            (LockUtils.ThrowingSupplier<T>)
                                    () -> {
                                        log.debug(
                                                "First-load supplier start: name={}, redisKey={}, distKey={}",
                                                getName(),
                                                cacheKey,
                                                distKey);

                                        // 双重检查1（本地锁后）
                                        ValueWrapper afterLocal = super.get(key);
                                        if (afterLocal != null) {
                                            Object val = afterLocal.get();
                                            if (val != null) {

                                                log.debug(
                                                        "First-load hit after local-check, name={}, redisKey={}",
                                                        getName(),
                                                        cacheKey);

                                                return (T) val;
                                            }
                                        }
                                        // 双重检查2（分布式锁后）
                                        ValueWrapper afterDist = super.get(key);
                                        if (afterDist != null) {
                                            Object val = afterDist.get();
                                            if (val != null) {

                                                log.debug(
                                                        "First-load hit after dist-check, name={}, redisKey={}",
                                                        getName(),
                                                        cacheKey);

                                                return (T) val;
                                            }
                                        }
                                        // 真正回源加载

                                        log.debug(
                                                "First-load calling valueLoader: name={}, redisKey={}",
                                                getName(),
                                                cacheKey);

                                        T value = valueLoader.call();
                                        if (value == null) {
                                            throw new Cache.ValueRetrievalException(
                                                    key,
                                                    valueLoader,
                                                    new NullPointerException(
                                                            "ValueLoader returned null"));
                                        }
                                        this.put(key, value);

                                        log.debug(
                                                "First-load loaded and cached: name={}, redisKey={}, valueType={}",
                                                getName(),
                                                cacheKey,
                                                value.getClass().getSimpleName());

                                        return value;
                                    });

            log.debug(
                    "First-load finished: name={}, redisKey={}, distKey={}",
                    getName(),
                    cacheKey,
                    distKey);

            return result;
        } catch (Exception ex) {
            throw new Cache.ValueRetrievalException(key, valueLoader, ex);
        }
    }

    private Object wrapIfMataAbsent(@Nullable Object value, @NonNull Object key) {
        if (value == null) return null;
        if (value instanceof CacheMata) return value;
        long ttlSecs = -1L;
        try {
            if (cacheConfiguration != null) {
                {
                    Duration d = cacheConfiguration.getTtlFunction().getTimeToLive(value, key);
                    if (!d.isNegative() && !d.isZero()) {
                        ttlSecs = d.getSeconds();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 雪崩保护：将 TTL 在原有基础上减少 5%~20%
        long effectiveTtl = ttlSecs;
        if (ttlSecs > 1) {
            double p = ThreadLocalRandom.current().nextDouble(0.05d, 0.20d); // 5%~20%
            effectiveTtl = Math.max(1, (long) Math.floor(ttlSecs * (1.0d - p)));
        }
        // 不再计算本地过期时间，仅使用元信息中的 TTL，并由 Redis 统一管理过期
        return CacheMata.builder().ttl(effectiveTtl).value(value).build();
    }

    // 解包职责已集中在 fromStoreValue()

    @Override
    protected Object fromStoreValue(@Nullable Object storeValue) {
        Object v = super.fromStoreValue(storeValue);
        if (v instanceof CacheMata) {
            return ((CacheMata) v).getValue();
        }
        return v;
    }

    /** 统一设置抖动后的过期时间，避免重复代码。 */
    private void applyLitteredExpire(Object key, Object toStore) {
        try {
            if (toStore instanceof CacheMata meta && meta.getTtl() > 0) {
                String cacheKey = createCacheKey(key);
                long seconds = meta.getTtl();
                if (seconds > 0) {
                    redisTemplate.expire(cacheKey, seconds, TimeUnit.SECONDS);
                }
            }
        } catch (Exception ignore) {
        }
    }

    /** 判断 putIfAbsent 前该 key 是否不存在（-2 表示 Redis 中 key 不存在）。 */
    private boolean wasKeyAbsentBeforePut(Object key) {
        try {
            Long ttl = redisTemplate.getExpire(createCacheKey(key), TimeUnit.SECONDS);
            return ttl == -2L;
        } catch (Exception ignore) {
            return false;
        }
    }
}
