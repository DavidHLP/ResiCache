package com.david.spring.cache.redis.chain.handler;

import com.david.spring.cache.redis.cache.support.CacheOperationService;
import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.chain.CacheOperationType;
import com.david.spring.cache.redis.registry.RegistryFactory;
import com.david.spring.cache.redis.lock.DistributedLock;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueWrapper;

import java.util.concurrent.Executor;

/**
 * 简单缓存处理器。
 * <p>
 * 作为职责链的终端处理器，提供基础的缓存操作和兜底逻辑。
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
@Slf4j
public class SimpleHandler extends AbstractCacheHandler {

	public SimpleHandler(RegistryFactory registryFactory, Executor executor,
	                   CacheOperationService cacheOperationService, DistributedLock distributedLock) {
		super(registryFactory, executor, cacheOperationService, distributedLock);
	}

	@Override
	@Nonnull
	public String getName() {
		return "Simple";
	}

	@Override
	public int getOrder() {
		return Integer.MAX_VALUE; // 最低优先级，作为链的最后一个处理器
	}

	@Override
	public boolean supports(@Nonnull CacheHandlerContext context) {
		// 作为终端处理器，总是支持所有场景
		return true;
	}

	@Override
	protected CacheOperationType[] getSupportedOperations() {
		return new CacheOperationType[]{
				CacheOperationType.READ,
				CacheOperationType.REFRESH
		};
	}

	@Override
	@Nonnull
	protected HandlerResult doHandle(@Nonnull CacheHandlerContext context) {
		logDebug("简单处理器处理: cache={}, key={}, operationType={}",
				context.cacheName(), context.key(), context.operationType());

		// 如果是读取操作且没有结果，尝试返回现有值或执行兜底逻辑
		if (context.operationType() == CacheOperationType.READ) {
			ValueWrapper currentValue = context.getCurrentValue();
			if (currentValue != null) {
				logDebug("简单处理器返回当前值: cache={}, key={}",
						context.cacheName(), context.key());
			} else {
				logDebug("简单处理器无可用值: cache={}, key={}",
						context.cacheName(), context.key());
			}
		}

		// 作为终端处理器，标记请求已处理
		return HandlerResult.HANDLED;
	}
}