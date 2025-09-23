package com.david.spring.cache.redis.chain;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.RegistryFactory;
import com.david.spring.cache.redis.cache.support.CacheOperationService;
import com.david.spring.cache.redis.lock.LockUtils;
import com.david.spring.cache.redis.lock.DistributedLock;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 缓存处理器抽象基类。
 * <p>
 * 为所有缓存处理器提供通用的基础设施和工具方法，包括：
 * - 职责链管理
 * - 日志记录
 * - 性能监控
 * - 锁管理
 * - TTL计算
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractCacheHandler implements CacheHandler {

    /** 下一个处理器 */
    @Nullable
    private CacheHandler next;

    /** 用于缓存操作的注册表工厂 */
    protected final RegistryFactory registryFactory;

    /** 异步执行器，用于非阻塞操作 */
    protected final Executor executor;

    /** 缓存操作服务，提供核心缓存功能 */
    protected final CacheOperationService cacheOperationService;

    /** 分布式锁，用于跨实例同步 */
    protected final DistributedLock distributedLock;

    @Override
    public void setNext(@Nullable CacheHandler next) {
        this.next = next;
    }

    @Override
    @Nullable
    public CacheHandler getNext() {
        return next;
    }

    /**
     * 继续执行链中的下一个处理器。
     * <p>
     * 如果有下一个处理器，则调用它；否则返回CONTINUE表示链执行完成。
     * </p>
     *
     * @param context 处理上下文
     * @return 处理结果
     */
    @Nonnull
    protected HandlerResult proceedToNext(@Nonnull CacheHandlerContext context) {
        if (next != null) {
            return next.handle(context);
        }
        logDebug("End of chain reached, returning CONTINUE");
        return HandlerResult.CONTINUE;
    }

    /**
     * 继续执行链中的下一个处理器，并传递更新的上下文。
     *
     * @param context 更新后的处理上下文
     * @return 处理结果
     */
    @Nonnull
    protected HandlerResult proceedToNext(@Nonnull CacheHandlerContext context,
                                         @Nullable org.springframework.cache.Cache.ValueWrapper result) {
        if (next != null) {
            CacheHandlerContext updatedContext = context.withResult(result);
            return next.handle(updatedContext);
        }
        logDebug("End of chain reached with result, returning CONTINUE");
        return HandlerResult.CONTINUE;
    }

    /**
     * 获取缓存的剩余 TTL（秒）。
     *
     * @param context 处理上下文
     * @return TTL 秒数（正数：剩余秒数，-1：永不失效，-2：键不存在）
     */
    protected long getCacheTtl(@Nonnull CacheHandlerContext context) {
        return cacheOperationService.getCacheTtl(context.cacheKey(), context.redisTemplate());
    }

    /**
     * 获取本地锁。
     *
     * @param context 处理上下文
     * @return 可重入锁实例
     */
    @Nonnull
    protected ReentrantLock obtainLocalLock(@Nonnull CacheHandlerContext context) {
        return registryFactory.getCacheInvocationRegistry().obtainLock(context.cacheName(), context.key());
    }

    /**
     * 记录调试日志。
     *
     * @param message 日志消息模板
     * @param args    消息参数
     */
    protected void logDebug(@Nonnull String message, Object... args) {
        if (log.isDebugEnabled()) {
            Object[] logArgs = new Object[args.length + 1];
            logArgs[0] = getName();
            System.arraycopy(args, 0, logArgs, 1, args.length);
	        log.debug("[{}] {}", logArgs, message);
        }
    }

    /**
     * 记录信息日志。
     *
     * @param message 日志消息模板
     * @param args    消息参数
     */
    protected void logInfo(@Nonnull String message, Object... args) {
        Object[] logArgs = new Object[args.length + 1];
        logArgs[0] = getName();
        System.arraycopy(args, 0, logArgs, 1, args.length);
	    log.info("[{}] {}", logArgs, message);
    }

    /**
     * 记录警告日志。
     *
     * @param message 日志消息模板
     * @param args    消息参数
     */
    protected void logWarn(@Nonnull String message, Object... args) {
        Object[] logArgs = new Object[args.length + 1];
        logArgs[0] = getName();
        System.arraycopy(args, 0, logArgs, 1, args.length);
	    log.warn("[{}] {}", logArgs, message);
    }

    /**
     * 记录错误日志。
     *
     * @param message 日志消息模板
     * @param throwable 异常信息
     * @param args    消息参数
     */
    protected void logError(@Nonnull String message, @Nullable Throwable throwable, Object... args) {
        Object[] logArgs = new Object[args.length + 1];
        logArgs[0] = getName();
        System.arraycopy(args, 0, logArgs, 1, args.length);
	    log.error("[{}] {}", logArgs, message, throwable);
    }

    /**
     * 记录性能日志。
     *
     * @param operation 操作名称
     * @param duration  执行时间（毫秒）
     * @param context   上下文信息
     */
    protected void logPerformance(@Nonnull String operation, long duration, @Nonnull CacheHandlerContext context) {
        if (duration > 100) {
            logWarn("Slow operation: {} took {}ms for cache={}, key={}",
                    operation, duration, context.cacheName(), context.key());
        } else if (log.isDebugEnabled()) {
            logDebug("Operation: {} completed in {}ms for cache={}, key={}",
                    operation, duration, context.cacheName(), context.key());
        }
    }

    /**
     * 执行带有性能监控的操作。
     *
     * @param operation 操作名称
     * @param context   执行上下文
     * @param task      要执行的任务
     * @param <T>       返回值类型
     * @return 任务执行结果
     */
    protected <T> T executeWithMonitoring(@Nonnull String operation,
                                         @Nonnull CacheHandlerContext context,
                                         @Nonnull java.util.function.Supplier<T> task) {
        long startTime = System.currentTimeMillis();
        try {
            T result = task.get();
            long duration = System.currentTimeMillis() - startTime;
            logPerformance(operation, duration, context);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logError("Operation {} failed after {}ms for cache={}, key={}: {}",
                    e, operation, duration, context.cacheName(), context.key(), e.getMessage());
            throw e;
        }
    }

    /**
     * 判断是否需要预刷新。
     */
    protected boolean shouldPreRefresh(long ttl, long configuredTtl) {
        return cacheOperationService.shouldPreRefresh(ttl, configuredTtl);
    }

    /**
     * 判断是否需要预刷新（支持自定义阈值）。
     */
    protected boolean shouldPreRefresh(long ttl, long configuredTtl, double threshold) {
        return cacheOperationService.shouldPreRefresh(ttl, configuredTtl, threshold);
    }

    /**
     * 执行缓存刷新。
     */
    protected void doRefresh(CacheHandlerContext context, long ttl) {
        CacheOperationService.CacheRefreshCallback callback = new CacheOperationService.CacheRefreshCallback() {
            @Override
            public void putCache(Object key, Object value) {
                context.callback().refresh(context.invocation(), key, context.cacheKey(), ttl);
            }

            @Override
            public String getCacheName() {
                return context.cacheName();
            }
        };

        cacheOperationService.doRefresh(context.invocation(), context.key(),
                context.cacheKey(), ttl, callback);
    }

    /**
     * 获取分布式锁名称。
     *
     * @param context 处理上下文
     * @return 分布式锁的键名
     */
    @Nonnull
    protected String getDistributedLockKey(@Nonnull CacheHandlerContext context) {
        return String.format("cache:lock:%s:%s", context.cacheName(), context.key());
    }

    /**
     * 验证处理器是否适用于当前上下文
     * 现在基于具体的布尔配置来判断而不是策略类型
     */
    protected boolean isHandlerApplicable(CachedInvocationContext context) {
        // 子类可以重写这个方法来实现具体的适用性检查
        return true;
    }

    /**
     * 执行带锁的操作。
     */
    protected <T> T executeWithLocks(CacheHandlerContext context,
                                   LockOperation<T> operation) {
        String lockKey = getDistributedLockKey(context);
        ReentrantLock localLock = getLocalLock(context);

        try {
            // 简化实现：直接使用本地锁，然后尝试分布式锁
            if (localLock.tryLock()) {
                try {
                    return operation.execute();
                } finally {
                    localLock.unlock();
                }
            } else {
                // 如果本地锁失败，不执行操作
                logDebug("Failed to acquire local lock for: {}", lockKey);
                return null;
            }
        } catch (Exception e) {
            logError("Lock operation failed: {}", e, e.getMessage());
            return null;
        }
    }

    /**
     * 获取本地锁。
     */
    protected ReentrantLock getLocalLock(CacheHandlerContext context) {
        return registryFactory.getCacheInvocationRegistry()
                .obtainLock(context.cacheName(), context.key());
    }

    /**
     * 锁操作的函数式接口。
     */
    @FunctionalInterface
    protected interface LockOperation<T> {
        T execute() throws Exception;
    }

    /**
     * 判断当前处理器是否应该在指定的操作类型下执行。
     * <p>
     * 子类可以重写此方法来定义自己的执行条件。
     * 默认实现将根据支持的操作类型列表进行判断。
     * </p>
     *
     * @param operationType 当前操作类型
     * @return true表示应该执行，false表示跳过
     */
    protected boolean shouldExecuteForOperation(@Nonnull CacheOperationType operationType) {
        CacheOperationType[] supportedOperations = getSupportedOperations();
        if (supportedOperations == null || supportedOperations.length == 0) {
            return true;
        }
        for (CacheOperationType supportedOperation : supportedOperations) {
            if (supportedOperation == operationType) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取当前处理器支持的操作类型列表。
     * <p>
     * 子类必须重写此方法来定义支持的操作类型。
     * 如果返回null或空数组，表示支持所有操作类型。
     * </p>
     *
     * @return 支持的操作类型数组
     */
    @Nullable
    protected abstract CacheOperationType[] getSupportedOperations();

    /**
     * 检查操作类型兼容性并执行处理器逻辑。
     * <p>
     * 在实际处理前先检查操作类型是否兼容，如果不兼容则直接跳过。
     * </p>
     *
     * @param context 处理上下文
     * @return 处理结果
     */
    @Override
    @Nonnull
    public final HandlerResult handle(@Nonnull CacheHandlerContext context) {
        if (!shouldExecuteForOperation(context.operationType())) {
            logDebug("Skipping handler for operation type: {}", context.operationType());
            return proceedToNext(context);
        }

        try {
            return doHandle(context);
        } catch (Exception e) {
            logError("Handler execution failed for operation type: {}, cache: {}, key: {}",
                    e, context.operationType(), context.cacheName(), context.key());
            return proceedToNext(context);
        }
    }

    /**
     * 子类实现的实际处理逻辑。
     * <p>
     * 此方法在操作类型检查通过后被调用，子类只需关注具体的处理逻辑。
     * </p>
     *
     * @param context 处理上下文
     * @return 处理结果
     */
    @Nonnull
    protected abstract HandlerResult doHandle(@Nonnull CacheHandlerContext context);
}