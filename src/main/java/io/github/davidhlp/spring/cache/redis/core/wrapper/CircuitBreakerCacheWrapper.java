package io.github.davidhlp.spring.cache.redis.core.wrapper;

import io.github.davidhlp.spring.cache.redis.core.RedisProCache;

import lombok.extern.slf4j.Slf4j;

import org.springframework.lang.Nullable;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CircuitBreakerCacheWrapper - 断路器模式的缓存包装器，提供Redis降级能力
 *
 * <p>状态机转换:
 * <ul>
 *   <li>CLOSED -> OPEN: 10秒内3次失败</li>
 *   <li>OPEN -> HALF_OPEN: 30秒后允许测试请求</li>
 *   <li>HALF_OPEN -> CLOSED: 成功请求</li>
 *   <li>HALF_OPEN -> OPEN: 失败请求</li>
 * </ul>
 *
 * <p>特性:
 * <ul>
 *   <li>Per-cache-name断路器: 每个缓存独立维护断路器状态</li>
 *   <li>Sliding Window: 追踪最近10秒内的失败次数</li>
 *   <li>Fail-Open: OPEN状态让调用者直接执行loader（跳过缓存）</li>
 *   <li>Stale Timeout: 5分钟后强制转换到HALF_OPEN</li>
 * </ul>
 */
@Slf4j
public class CircuitBreakerCacheWrapper {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long SLIDING_WINDOW_MS = 10_000;
    private static final long HALF_OPEN_TIMEOUT_MS = 30_000;

    private final RedisProCache delegate;

    /** 每个缓存名称的断路器状态 */
    private final Map<String, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();

    public CircuitBreakerCacheWrapper(RedisProCache delegate) {
        this.delegate = delegate;
    }

    /**
     * 带断路器的get操作（Spring Cache标准接口）
     *
     * @param key 缓存key
     * @param loader 当缓存未命中时调用的加载器
     * @return 缓存的值，或通过loader加载的值
     */
    @Nullable
    public <T> T get(Object key, java.util.concurrent.Callable<T> loader) {
        String cacheName = delegate.getName();
        CircuitBreakerState state = circuitBreakers.computeIfAbsent(cacheName, k -> new CircuitBreakerState());

        CircuitState currentState = state.getCurrentState();
        long now = System.currentTimeMillis();

        if (currentState == CircuitState.OPEN) {
            // 检查是否应该转换为HALF_OPEN
            if (state.shouldTryHalfOpen(now)) {
                state.transitionToHalfOpen(now);
                log.debug("Circuit breaker HALF_OPEN: cacheName={}", cacheName);
            } else {
                // OPEN状态 - fail-open，让调用者执行loader
                log.debug("Circuit breaker OPEN (fail-open): cacheName={}", cacheName);
                try {
                    return loader.call();
                } catch (Exception e) {
                    throw new RuntimeException("Loader failed during circuit open", e);
                }
            }
        }

        // 执行实际缓存操作
        try {
            T result = delegate.get(key, loader);
            state.recordSuccess(now);
            return result;
        } catch (Exception e) {
            state.recordFailure(now);
            log.warn("Circuit breaker recorded failure: cacheName={}, error={}", cacheName, e.getMessage());
            // 降级：尝试直接调用loader
            try {
                return loader.call();
            } catch (Exception loaderException) {
                throw new RuntimeException("Both cache and loader failed", loaderException);
            }
        }
    }

    /**
     * 获取缓存名称
     */
    public String getName() {
        return delegate.getName();
    }

    /**
     * 断路器状态枚举
     */
    private enum CircuitState {
        CLOSED,    // 正常状态
        OPEN,      // 断路器打开，fail-fast
        HALF_OPEN  // 半开状态，允许测试请求
    }

    /**
     * 断路器状态内部类
     */
    private static class CircuitBreakerState {
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final Deque<Long> failureTimestamps = new ConcurrentLinkedDeque<>();
        private final AtomicLong stateChangeTime = new AtomicLong(System.currentTimeMillis());
        private volatile CircuitState currentState = CircuitState.CLOSED;

        synchronized CircuitState getCurrentState() {
            return currentState;
        }

        synchronized boolean shouldTryHalfOpen(long now) {
            return now - stateChangeTime.get() >= HALF_OPEN_TIMEOUT_MS;
        }

        synchronized void transitionToHalfOpen(long now) {
            currentState = CircuitState.HALF_OPEN;
            stateChangeTime.set(now);
            failureCount.set(0);
            failureTimestamps.clear();
        }

        synchronized void recordFailure(long now) {
            // 清理旧失败记录
            cleanupOldFailures(now);

            failureTimestamps.add(now);
            int count = failureTimestamps.size();
            failureCount.set(count);

            if (count >= FAILURE_THRESHOLD && currentState == CircuitState.CLOSED) {
                currentState = CircuitState.OPEN;
                stateChangeTime.set(now);
                log.info("Circuit breaker opened after {} failures", count);
            } else if (currentState == CircuitState.HALF_OPEN) {
                // HALF_OPEN状态下失败，回到OPEN
                currentState = CircuitState.OPEN;
                stateChangeTime.set(now);
                log.info("Circuit breaker reopened from HALF_OPEN");
            }
        }

        synchronized void recordSuccess(long now) {
            if (currentState == CircuitState.HALF_OPEN) {
                // 成功，恢复到CLOSED
                currentState = CircuitState.CLOSED;
                stateChangeTime.set(now);
                failureCount.set(0);
                failureTimestamps.clear();
                log.info("Circuit breaker closed after successful request");
            } else if (currentState == CircuitState.CLOSED) {
                // 成功时清理部分旧记录，减少误触发
                cleanupOldFailures(now);
            }
        }

        private synchronized void cleanupOldFailures(long now) {
            Long oldest;
            while ((oldest = failureTimestamps.peek()) != null) {
                if (now - oldest > SLIDING_WINDOW_MS) {
                    failureTimestamps.remove();
                } else {
                    break;
                }
            }
            failureCount.set(failureTimestamps.size());
        }
    }
}
