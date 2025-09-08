package com.david.spring.cache.redis.protection;

import com.david.spring.cache.redis.operations.RedisStringOperations;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存雪崩防护服务
 * 
 * <p>
 * 提供独立的缓存雪崩防护功能，不依赖于特定的上下文，可被各种组件调用：
 * 1. 随机TTL：为缓存设置随机过期时间，避免集体失效
 * 2. 健康检查：监控缓存服务健康状态
 * 3. 熔断器：当缓存服务异常时，暂时停止访问
 * 4. 降级策略：缓存服务不可用时的降级处理
 * 5. 预热机制：异步预热相关数据和热点数据
 * 6. 错误统计：统计和监控缓存访问错误
 *
 * @author david
 */
@Getter
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheAvalancheProtection {

    // 随机TTL配置
    public static final double MIN_RANDOM_FACTOR = 0.1; // 最小随机因子10%
    public static final double MAX_RANDOM_FACTOR = 0.3; // 最大随机因子30%
    public static final long MIN_TTL_SECONDS = 60; // 最小TTL 60秒
    public static final long DEFAULT_TTL_SECONDS = 3600; // 默认TTL 1小时
    // 健康检查配置
    public static final String CACHE_HEALTH_KEY = "cache:health:status";
    public static final Duration HEALTH_CHECK_TTL = Duration.ofMinutes(1);
    // 错误统计配置
    public static final String CACHE_ERROR_COUNT_KEY = "cache:error:count";
    public static final int MAX_ERROR_COUNT = 10; // 最大错误次数
    public static final Duration ERROR_WINDOW = Duration.ofMinutes(5); // 错误统计窗口
    // 熔断器配置
    public static final String CIRCUIT_BREAKER_KEY = "cache:circuit:breaker";
    public static final Duration CIRCUIT_BREAKER_TIMEOUT = Duration.ofMinutes(2); // 熔断器超时
    public static final String CIRCUIT_BREAKER_OPEN = "OPEN";
    public static final String CIRCUIT_BREAKER_CLOSED = "CLOSED";
    // 预热配置
    public static final int DEFAULT_PRELOAD_DELAY_MS = 100; // 预热延迟100ms
	/**
	 * -- GETTER --
	 *  获取Redis操作接口（供高级用法）
	 */
	private final RedisStringOperations redisStringOperations;
	/**
	 * -- GETTER --
	 *  获取预热执行器（供高级用法）
	 */
	private final Executor preloadExecutor = Executors.newFixedThreadPool(2);

    /**
     * 使用雪崩防护设置缓存TTL（不操作值）
     *
     * @param cacheName 缓存名称
     * @param cacheKey  缓存键
     * @param baseTtl   基础TTL（秒）
     * @return 防护结果
     */
    public ProtectionResult<Void> setTtlWithProtection(String cacheName, String cacheKey, long baseTtl) {
        return setTtlWithProtection(cacheName, cacheKey, baseTtl, true);
    }

    /**
     * 使用雪崩防护设置缓存TTL（详细配置，不操作值）
     *
     * @param cacheName    缓存名称
     * @param cacheKey     缓存键
     * @param baseTtl      基础TTL（秒）
     * @param enablePreload 是否启用预热
     * @return 防护结果
     */
    public ProtectionResult<Void> setTtlWithProtection(String cacheName, String cacheKey,
                                                       long baseTtl, boolean enablePreload) {
        log.debug("开始缓存雪崩防护TTL设置: cacheName={}, key={}, baseTtl={}",
                cacheName, cacheKey, baseTtl);

        // 1. 检查缓存服务健康状态
        if (isCacheServiceDown()) {
            log.warn("缓存服务异常，启用降级策略: cacheName={}, key={}", cacheName, cacheKey);
            return handleServiceDowngrade();
        }

        // 2. 检查熔断器状态
        if (isCircuitBreakerOpen()) {
            log.warn("缓存熔断器开启，跳过TTL设置: cacheName={}, key={}", cacheName, cacheKey);
            return new ProtectionResult<>(null, false, true, null, "CIRCUIT_BREAKER_OPEN");
        }

        try {
            // 3. 使用随机TTL设置过期时间
            Duration randomTtl = calculateRandomTtl(baseTtl);
            boolean ttlSet = setRandomTtl(cacheName, cacheKey, randomTtl);

            if (ttlSet) {
                // 4. 异步预热相关数据
                if (enablePreload) {
                    asyncPreloadRelatedData(cacheName, cacheKey);
                }

                // 5. 重置错误计数
                resetErrorCount();

                log.debug("缓存雪崩防护TTL设置成功: cacheName={}, key={}, ttl={}s",
                        cacheName, cacheKey, randomTtl.getSeconds());
                return new ProtectionResult<>(null, true, false, randomTtl, "SUCCESS");
            } else {
                return new ProtectionResult<>(null, false, true, randomTtl, "FALLBACK_USED");
            }

        } catch (Exception e) {
            // 6. 记录错误并判断是否需要开启熔断器
            recordCacheError(e);

            if (shouldOpenCircuitBreaker()) {
                openCircuitBreaker();
                log.error("缓存错误过多，开启熔断器: cacheName={}, key={}", cacheName, cacheKey);
            }

            log.error("缓存雪崩防护TTL设置失败: cacheName={}, key={}, error={}",
                    cacheName, cacheKey, e.getMessage());
            return new ProtectionResult<>(null, false, true, null, "ERROR: " + e.getMessage());
        }
    }

    /**
     * 计算随机TTL
     *
     * @param baseTtlSeconds 基础TTL（秒）
     * @return 随机TTL
     */
    public Duration calculateRandomTtl(long baseTtlSeconds) {
        if (baseTtlSeconds <= 0) {
            baseTtlSeconds = DEFAULT_TTL_SECONDS;
        }

        // 计算随机偏移（基础TTL的10%-30%）
        long minOffsetSeconds = (long) (baseTtlSeconds * MIN_RANDOM_FACTOR);
        long maxOffsetSeconds = (long) (baseTtlSeconds * MAX_RANDOM_FACTOR);

        long randomOffsetSeconds = ThreadLocalRandom.current().nextLong(
                -minOffsetSeconds, maxOffsetSeconds + 1);

        // 确保最小TTL
        long finalTtlSeconds = Math.max(MIN_TTL_SECONDS, baseTtlSeconds + randomOffsetSeconds);

        return Duration.ofSeconds(finalTtlSeconds);
    }

    /**
     * 设置随机TTL（不操作值）
     *
     * @param cacheName 缓存名称
     * @param cacheKey  缓存键
     * @param ttl       TTL
     * @return 设置成功返回true
     */
    public boolean setRandomTtl(String cacheName, String cacheKey, Duration ttl) {
        try {
            // 使用Redis直接设置TTL，不操作值
            String redisKey = cacheName + "::" + cacheKey;
            boolean success = redisStringOperations.expire(redisKey, ttl);

            if (success) {
                log.debug("缓存TTL设置成功(随机TTL): key={}, ttl={}s", redisKey, ttl.getSeconds());
            } else {
                log.warn("缓存TTL设置失败，key可能不存在: key={}", redisKey);
            }
            return success;

        } catch (Exception e) {
            log.error("Redis TTL设置失败: cacheName={}, key={}, error={}",
                    cacheName, cacheKey, e.getMessage());
            return false;
        }
    }

    /**
     * 检查缓存服务是否宕机
     *
     * @return 宕机返回true
     */
    public boolean isCacheServiceDown() {
        try {
            // 健康检查：尝试设置一个测试键
            String testKey = "health:check:" + System.currentTimeMillis();
            redisStringOperations.set(testKey, "ok", String.class, Duration.ofSeconds(10));

            // 更新健康状态
            redisStringOperations.set(CACHE_HEALTH_KEY, "UP", String.class, HEALTH_CHECK_TTL);
            return false;

        } catch (Exception e) {
            log.error("缓存健康检查失败: error={}", e.getMessage());
            return true;
        }
    }

    /**
     * 处理服务降级
     *
     * @return 降级结果
     */
    private ProtectionResult<Void> handleServiceDowngrade() {
        log.info("执行缓存雪崩降级策略");

        // 降级策略：
        // 1. 记录降级日志
        // 2. 跳过TTL设置操作
        // 3. 返回降级状态

        return new ProtectionResult<>(null, false, true, null, "SERVICE_DOWN_FALLBACK");
    }

    /**
     * 检查熔断器是否开启
     *
     * @return 开启返回true
     */
    public boolean isCircuitBreakerOpen() {
        try {
            String breakerStatus = redisStringOperations.get(CIRCUIT_BREAKER_KEY, String.class);
            return CIRCUIT_BREAKER_OPEN.equals(breakerStatus);
        } catch (Exception e) {
            log.warn("检查熔断器状态异常: error={}", e.getMessage());
            return false;
        }
    }

    /**
     * 开启熔断器
     */
    public void openCircuitBreaker() {
        try {
            redisStringOperations.set(CIRCUIT_BREAKER_KEY, CIRCUIT_BREAKER_OPEN,
                    String.class, CIRCUIT_BREAKER_TIMEOUT);
            log.warn("缓存熔断器已开启，超时时间: {}分钟", CIRCUIT_BREAKER_TIMEOUT.toMinutes());
        } catch (Exception e) {
            log.error("开启熔断器失败: error={}", e.getMessage());
        }
    }

    /**
     * 关闭熔断器
     */
    public void closeCircuitBreaker() {
        try {
            redisStringOperations.set(CIRCUIT_BREAKER_KEY, CIRCUIT_BREAKER_CLOSED,
                    String.class, Duration.ofMinutes(1));
            log.info("缓存熔断器已手动关闭");
        } catch (Exception e) {
            log.error("关闭熔断器失败: error={}", e.getMessage());
        }
    }

    /**
     * 记录缓存错误
     *
     * @param error 错误信息
     * @return 当前错误次数
     */
    public Long recordCacheError(Exception error) {
        try {
            Long errorCount = redisStringOperations.increment(CACHE_ERROR_COUNT_KEY, 1);

            if (errorCount != null && errorCount == 1) {
                // 首次记录，设置过期时间
                redisStringOperations.expire(CACHE_ERROR_COUNT_KEY, ERROR_WINDOW);
            }

            log.warn("记录缓存错误: count={}, error={}", errorCount, error.getMessage());
            return errorCount;

        } catch (Exception e) {
            log.error("记录缓存错误失败: error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 判断是否应该开启熔断器
     *
     * @return 应该开启返回true
     */
    public boolean shouldOpenCircuitBreaker() {
        try {
            Integer errorCount = redisStringOperations.get(CACHE_ERROR_COUNT_KEY, Integer.class);
            return errorCount != null && errorCount >= MAX_ERROR_COUNT;
        } catch (Exception e) {
            log.warn("检查熔断器开启条件异常: error={}", e.getMessage());
            return false;
        }
    }

    /**
     * 重置错误计数
     */
    public void resetErrorCount() {
        try {
            redisStringOperations.delete(CACHE_ERROR_COUNT_KEY);
        } catch (Exception e) {
            log.warn("重置错误计数失败: error={}", e.getMessage());
        }
    }

    /**
     * 获取当前错误计数
     *
     * @return 错误次数
     */
    public int getCurrentErrorCount() {
        try {
            Integer errorCount = redisStringOperations.get(CACHE_ERROR_COUNT_KEY, Integer.class);
            return errorCount != null ? errorCount : 0;
        } catch (Exception e) {
            log.warn("获取错误计数失败: error={}", e.getMessage());
            return 0;
        }
    }

    /**
     * 异步预热相关数据
     *
     * @param cacheName 缓存名称
     * @param cacheKey  缓存键
     */
    public void asyncPreloadRelatedData(String cacheName, String cacheKey) {
        CompletableFuture.runAsync(() -> {
            try {
                log.debug("开始异步预热相关数据: cacheName={}, key={}", cacheName, cacheKey);
                preloadRelatedKeys(cacheName, cacheKey);
            } catch (Exception e) {
                log.warn("异步预热数据异常: error={}", e.getMessage());
            }
        }, preloadExecutor);
    }

    /**
     * 异步预热相关数据（自定义执行器）
     */
    public void asyncPreloadRelatedData(String cacheName, String cacheKey, Executor executor) {
        CompletableFuture.runAsync(() -> {
            try {
                log.debug("开始异步预热相关数据: cacheName={}, key={}", cacheName, cacheKey);
                preloadRelatedKeys(cacheName, cacheKey);
            } catch (Exception e) {
                log.warn("异步预热数据异常: error={}", e.getMessage());
            }
        }, executor);
    }

    /**
     * 预热相关键值
     *
     * @param cacheName 缓存名称
     * @param cacheKey  缓存键
     */
    private void preloadRelatedKeys(String cacheName, String cacheKey) {
        // 这里可以根据业务逻辑实现预热策略
        log.debug("预热相关键值: cacheName={}, baseKey={}", cacheName, cacheKey);

        // 示例：如果是用户数据，预热用户的其他信息
        // 如果是商品数据，预热相关商品信息
        // 具体实现需要根据业务场景定制
    }

    /**
     * 手动预热缓存TTL（不操作值）
     *
     * @param cacheName   缓存名称
     * @param preloadKeys 要预热的键列表
     * @param ttl         缓存TTL（秒）
     */
    public void manualPreloadCacheTtl(String cacheName, List<String> preloadKeys, long ttl) {
        CompletableFuture.runAsync(() -> {
            log.info("开始手动预热缓存TTL: cacheName={}, keyCount={}", cacheName, preloadKeys.size());

            for (String key : preloadKeys) {
                try {
                    setTtlWithProtection(cacheName, key, ttl, false);
                    log.debug("预热缓存键TTL: cacheName={}, key={}", cacheName, key);

                    // 预热延迟，避免瞬间压力
                    Thread.sleep(DEFAULT_PRELOAD_DELAY_MS);

                } catch (Exception e) {
                    log.warn("预热缓存键TTL失败: cacheName={}, key={}, error={}",
                            cacheName, key, e.getMessage());
                }
            }

            log.info("缓存TTL预热完成: cacheName={}", cacheName);
        }, preloadExecutor);
    }

    /**
     * 手动预热缓存TTL（使用自定义执行器）
     */
    public void manualPreloadCacheTtl(String cacheName, List<String> preloadKeys,
                                     long ttl, Executor executor) {
        CompletableFuture.runAsync(() -> {
            log.info("开始手动预热缓存TTL: cacheName={}, keyCount={}", cacheName, preloadKeys.size());

            for (String key : preloadKeys) {
                try {
                    setTtlWithProtection(cacheName, key, ttl, false);
                    log.debug("预热缓存键TTL: cacheName={}, key={}", cacheName, key);
                    Thread.sleep(DEFAULT_PRELOAD_DELAY_MS);

                } catch (Exception e) {
                    log.warn("预热缓存键TTL失败: cacheName={}, key={}, error={}",
                            cacheName, key, e.getMessage());
                }
            }

            log.info("缓存TTL预热完成: cacheName={}", cacheName);
        }, executor);
    }

    /**
     * 获取缓存健康状态
     *
     * @return 健康状态信息
     */
    public HealthStatus getCacheHealthStatus() {
        try {
            String healthStatus = redisStringOperations.get(CACHE_HEALTH_KEY, String.class);
            Integer errorCount = redisStringOperations.get(CACHE_ERROR_COUNT_KEY, Integer.class);
            String breakerStatus = redisStringOperations.get(CIRCUIT_BREAKER_KEY, String.class);

            boolean healthy = "UP".equals(healthStatus) && !CIRCUIT_BREAKER_OPEN.equals(breakerStatus);
            boolean circuitOpen = CIRCUIT_BREAKER_OPEN.equals(breakerStatus);

            String details = String.format("Health: %s, Errors: %d, Breaker: %s",
                    healthStatus != null ? healthStatus : "UNKNOWN",
                    errorCount != null ? errorCount : 0,
                    breakerStatus != null ? breakerStatus : CIRCUIT_BREAKER_CLOSED);

            return new HealthStatus(healthy, errorCount != null ? errorCount : 0, circuitOpen, details);

        } catch (Exception e) {
            String errorDetails = "Health check failed: " + e.getMessage();
            return new HealthStatus(false, -1, false, errorDetails);
        }
    }

    /**
     * 缓存雪崩防护结果
     */
    public static class ProtectionResult<T> {
        private final T value;
        private final boolean cacheWritten;
        private final boolean fallbackUsed;
        private final Duration actualTtl;
        private final String status;

        public ProtectionResult(T value, boolean cacheWritten, boolean fallbackUsed,
                              Duration actualTtl, String status) {
            this.value = value;
            this.cacheWritten = cacheWritten;
            this.fallbackUsed = fallbackUsed;
            this.actualTtl = actualTtl;
            this.status = status;
        }

        public T getValue() { return value; }
        public boolean isCacheWritten() { return cacheWritten; }
        public boolean isFallbackUsed() { return fallbackUsed; }
        public Duration getActualTtl() { return actualTtl; }
        public String getStatus() { return status; }
    }

    /**
     * 缓存健康状态
     */
    public static class HealthStatus {
        private final boolean healthy;
        private final int errorCount;
        private final boolean circuitBreakerOpen;
        private final String details;

        public HealthStatus(boolean healthy, int errorCount, boolean circuitBreakerOpen, String details) {
            this.healthy = healthy;
            this.errorCount = errorCount;
            this.circuitBreakerOpen = circuitBreakerOpen;
            this.details = details;
        }

        public boolean isHealthy() { return healthy; }
        public int getErrorCount() { return errorCount; }
        public boolean isCircuitBreakerOpen() { return circuitBreakerOpen; }
        public String getDetails() { return details; }
    }

}
