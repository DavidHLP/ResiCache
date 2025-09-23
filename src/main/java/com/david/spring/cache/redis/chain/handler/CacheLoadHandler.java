package com.david.spring.cache.redis.chain.handler;

import com.david.spring.cache.redis.cache.support.CacheOperationService;
import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.chain.CacheOperationType;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.registry.RegistryFactory;
import com.david.spring.cache.redis.lock.DistributedLock;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueWrapper;

import java.util.concurrent.Executor;

/**
 * 统一的缓存加载处理器。
 * <p>
 * 合并了DataLoadHandler和CacheBreakdownHandler的功能，提供：
 * - 缓存击穿保护
 * - 数据加载逻辑
 * - 分布式锁管理
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
@Slf4j
public class CacheLoadHandler extends AbstractCacheHandler {

    public CacheLoadHandler(RegistryFactory registryFactory,
                           Executor executor,
                           CacheOperationService cacheOperationService,
                           DistributedLock distributedLock) {
        super(registryFactory, executor, cacheOperationService, distributedLock);
    }

    @Override
    @Nonnull
    public String getName() {
        return "CacheLoad";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public boolean supports(@Nonnull CacheHandlerContext context) {
        // 只在读取操作且需要锁保护时执行
        CachedInvocationContext invocationContext = context.invocationContext();
        return context.operationType() == CacheOperationType.READ &&
               (invocationContext.distributedLock() || invocationContext.internalLock()) &&
               !context.hasValue();
    }

    @Override
    protected CacheOperationType[] getSupportedOperations() {
        return new CacheOperationType[]{
                CacheOperationType.READ
        };
    }

    @Override
    @Nonnull
    protected HandlerResult doHandle(@Nonnull CacheHandlerContext context) {
        logDebug("开始缓存加载处理: cache={}, key={}",
                context.cacheName(), context.key());

        try {
            ValueWrapper result = executeWithLocks(context, () -> {
                // 再次检查缓存（双重检查锁模式）
                ValueWrapper cachedValue = context.callback().getBaseValue(context.key());
                if (cachedValue != null) {
                    logDebug("锁内发现缓存值: cache={}, key={}",
                            context.cacheName(), context.key());
                    return cachedValue;
                }

                // 如果仍然没有缓存值，尝试调用原方法加载数据
                try {
                    Object loadedValue = context.invocation().invoke();
                    if (loadedValue != null) {
                        logDebug("成功加载数据: cache={}, key={}",
                                context.cacheName(), context.key());

                        // 创建ValueWrapper返回
                        return new SimpleValueWrapper(loadedValue);
                    } else {
                        logDebug("加载的数据为null: cache={}, key={}",
                                context.cacheName(), context.key());

                        // 根据配置决定是否缓存null值
                        if (context.invocationContext().cacheNullValues()) {
                            return new SimpleValueWrapper(null);
                        }
                    }
                } catch (Exception e) {
                    logError("数据加载失败: cache={}, key={}, error={}", e,
                            context.cacheName(), context.key(), e.getMessage());
                }

                return null;
            });

            if (result != null) {
                // 更新上下文并继续处理链
                CacheHandlerContext updatedContext = context.withResult(result);
                return proceedToNext(updatedContext);
            }

            logDebug("缓存加载完成，无有效结果: cache={}, key={}",
                    context.cacheName(), context.key());

            return HandlerResult.CONTINUE;

        } catch (Exception e) {
            logError("缓存加载处理失败: cache={}, key={}, error={}", e,
                    context.cacheName(), context.key(), e.getMessage());
            return HandlerResult.CONTINUE;
        }
    }

    /**
     * 简单的ValueWrapper实现
     */
    private static class SimpleValueWrapper implements ValueWrapper {
        private final Object value;

        public SimpleValueWrapper(Object value) {
            this.value = value;
        }

        @Override
        public Object get() {
            return value;
        }
    }
}