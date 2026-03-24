package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 统一的缓存错误处理器
 * 
 * 职责：
 * 1. 定义不同操作的错误处理策略
 * 2. 统一日志记录级别
 * 3. 支持自定义降级行为
 */
@Slf4j
@Component
public class CacheErrorHandler {

    /**
     * 错误处理策略
     */
    public enum ErrorStrategy {
        /** 返回失败结果，记录错误日志 */
        FAIL_FAST,
        
        /** 降级处理，返回 miss，记录警告日志 */
        GRACEFUL_DEGRADATION,
        
        /** 静默失败，仅记录 debug 日志 */
        SILENT
    }

    /**
     * 处理缓存操作异常
     * 
     * @param operation 操作类型
     * @param cacheName 缓存名称
     * @param key 缓存 key
     * @param e 异常
     * @param strategy 错误处理策略
     * @return CacheResult
     */
    public CacheResult handleException(
            String operation,
            String cacheName,
            String key,
            Exception e,
            ErrorStrategy strategy) {
        
        return switch (strategy) {
            case FAIL_FAST -> {
                log.error("Cache {} failed: cacheName={}, key={}", 
                          operation, cacheName, key, e);
                yield CacheResult.failure(e);
            }
            case GRACEFUL_DEGRADATION -> {
                log.warn("Cache {} failed, degrading gracefully: cacheName={}, key={}, error={}", 
                         operation, cacheName, key, e.getMessage());
                yield CacheResult.miss();
            }
            case SILENT -> {
                log.debug("Cache {} failed (silent): cacheName={}, key={}", 
                          operation, cacheName, key, e);
                yield CacheResult.miss();
            }
        };
    }

    /**
     * GET 操作默认错误策略：优雅降级
     * 
     * GET 失败不应阻塞业务，返回 miss 让业务重新加载数据。
     */
    public CacheResult handleGetError(String cacheName, String key, Exception e) {
        return handleException("GET", cacheName, key, e, ErrorStrategy.GRACEFUL_DEGRADATION);
    }

    /**
     * PUT 操作默认错误策略：快速失败
     * 
     * PUT 失败可能表示 Redis 不可用，应该让调用方知道。
     */
    public CacheResult handlePutError(String cacheName, String key, Exception e) {
        return handleException("PUT", cacheName, key, e, ErrorStrategy.FAIL_FAST);
    }

    /**
     * PUT_IF_ABSENT 操作默认错误策略：快速失败
     */
    public CacheResult handlePutIfAbsentError(String cacheName, String key, Exception e) {
        return handleException("PUT_IF_ABSENT", cacheName, key, e, ErrorStrategy.FAIL_FAST);
    }

    /**
     * REMOVE 操作默认错误策略：静默失败
     * 
     * 删除失败不应影响业务流程。
     */
    public CacheResult handleRemoveError(String cacheName, String key, Exception e) {
        return handleException("REMOVE", cacheName, key, e, ErrorStrategy.SILENT);
    }

    /**
     * CLEAN 操作默认错误策略：快速失败
     * 
     * 批量清理失败应该让调用方知道。
     */
    public CacheResult handleCleanError(String cacheName, String pattern, Exception e) {
        return handleException("CLEAN", cacheName, pattern, e, ErrorStrategy.FAIL_FAST);
    }
}
