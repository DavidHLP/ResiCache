package com.david.spring.cache.redis.chain;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 缓存处理器上下文。
 * <p>
 * 在职责链中传递的上下文信息，包含所有处理器可能需要的数据。
 * 设计为不可变对象，确保线程安全。
 * </p>
 *
 * @param cacheName         缓存名称
 * @param key               业务缓存键
 * @param cacheKey          Redis存储键
 * @param valueWrapper      当前缓存值包装器（可能为null）
 * @param result            当前处理结果（在链中传递时更新）
 * @param invocation        缓存调用信息
 * @param invocationContext 调用上下文，包含注解配置
 * @param redisTemplate     Redis操作模板
 * @param callback          回调接口，提供缓存操作支持
 * @param operationType     当前操作类型，用于控制处理器执行条件
 * @author CacheGuard
 * @since 2.0.0
 */
public record CacheHandlerContext(
		@Nonnull String cacheName,
		@Nonnull Object key,
		@Nonnull String cacheKey,
		@Nullable ValueWrapper valueWrapper,
		@Nullable ValueWrapper result,
		@Nonnull CachedInvocation invocation,
		@Nonnull CachedInvocationContext invocationContext,
		@Nonnull RedisTemplate<String, Object> redisTemplate,
		@Nonnull CacheFetchCallback callback,
		@Nonnull CacheOperationType operationType
) {

	/**
	 * 检查当前是否有缓存值。
	 *
	 * @return true表示有有效的缓存值
	 */
	public boolean hasValue() {
		return valueWrapper != null && valueWrapper.get() != null;
	}

	/**
	 * 安全地获取缓存值。
	 *
	 * @return 缓存值，可能为null
	 */
	@Nullable
	public Object getValue() {
		return valueWrapper != null ? valueWrapper.get() : null;
	}

	/**
	 * 检查是否有处理结果。
	 *
	 * @return true表示有处理结果
	 */
	public boolean hasResult() {
		return result != null && result.get() != null;
	}

	/**
	 * 安全地获取处理结果。
	 *
	 * @return 处理结果，可能为null
	 */
	@Nullable
	public Object getResult() {
		return result != null ? result.get() : null;
	}

	/**
	 * 创建带有新结果的上下文副本。
	 * <p>
	 * 用于在处理器之间传递更新的结果。
	 * </p>
	 *
	 * @param newResult 新地处理结果
	 * @return 新的上下文实例
	 */
	@Nonnull
	public CacheHandlerContext withResult(@Nullable ValueWrapper newResult) {
		return new CacheHandlerContext(
				cacheName,
				key,
				cacheKey,
				valueWrapper,
				newResult,
				invocation,
				invocationContext,
				redisTemplate,
				callback,
				operationType
		);
	}

	/**
	 * 创建带有新缓存值的上下文副本。
	 * <p>
	 * 用于在处理器中更新缓存值。
	 * </p>
	 *
	 * @param newValueWrapper 新的缓存值包装器
	 * @return 新的上下文实例
	 */
	@Nonnull
	public CacheHandlerContext withValueWrapper(@Nullable ValueWrapper newValueWrapper) {
		return new CacheHandlerContext(
				cacheName,
				key,
				cacheKey,
				newValueWrapper,
				result,
				invocation,
				invocationContext,
				redisTemplate,
				callback,
				operationType
		);
	}

	/**
	 * 创建带有新操作类型的上下文副本。
	 * <p>
	 * 用于在处理器中切换操作状态，比如从读取切换到刷新。
	 * </p>
	 *
	 * @param newOperationType 新的操作类型
	 * @return 新的上下文实例
	 */
	@Nonnull
	public CacheHandlerContext withOperationType(@Nonnull CacheOperationType newOperationType) {
		return new CacheHandlerContext(
				cacheName,
				key,
				cacheKey,
				valueWrapper,
				result,
				invocation,
				invocationContext,
				redisTemplate,
				callback,
				newOperationType
		);
	}

	/**
	 * 获取当前的有效值。
	 * <p>
	 * 优先返回处理结果，如果没有则返回原始缓存值。
	 * </p>
	 *
	 * @return 当前有效的缓存值包装器
	 */
	@Nullable
	public ValueWrapper getCurrentValue() {
		return result != null ? result : valueWrapper;
	}

	/**
	 * 缓存获取回调接口。
	 * <p>
	 * 提供处理器与缓存系统交互的标准接口。
	 * </p>
	 */
	public interface CacheFetchCallback {

		/**
		 * 获取基础缓存值。
		 *
		 * @param key 缓存键
		 * @return 缓存值包装器
		 */
		@Nullable
		ValueWrapper getBaseValue(@Nonnull Object key);

		/**
		 * 异步刷新缓存。
		 *
		 * @param invocation 缓存调用信息
		 * @param key        缓存键
		 * @param cacheKey   Redis缓存键
		 * @param ttl        当前TTL值
		 */
		void refresh(@Nonnull CachedInvocation invocation,
		             @Nonnull Object key,
		             @Nonnull String cacheKey,
		             long ttl);

		/**
		 * 解析配置的 TTL 时间（秒）。
		 *
		 * @param value 缓存值
		 * @param key   缓存键
		 * @return TTL 秒数，-1表示无法解析或永不过期
		 */
		long resolveConfiguredTtlSeconds(@Nullable Object value, @Nonnull Object key);

		/**
		 * 判断是否需要预刷新。
		 *
		 * @param currentTtl    当前剩余TTL（秒）
		 * @param configuredTtl 配置的TTL（秒）
		 * @return true表示需要预刷新
		 */
		boolean shouldPreRefresh(long currentTtl, long configuredTtl);

		/**
		 * 执行缓存删除操作。
		 *
		 * @param cacheName 缓存名称
		 * @param key       缓存键
		 */
		void evictCache(@Nonnull String cacheName, @Nonnull Object key);

		/**
		 * 执行缓存全量清除操作。
		 *
		 * @param cacheName 缓存名称
		 */
		void clearCache(@Nonnull String cacheName);

		/**
		 * 清理注册表中的调用信息。
		 *
		 * @param cacheName 缓存名称
		 * @param key       缓存键
		 */
		void cleanupRegistries(@Nonnull String cacheName, @Nonnull Object key);

		/**
		 * 清理注册表中的所有调用信息。
		 *
		 * @param cacheName 缓存名称
		 */
		void cleanupAllRegistries(@Nonnull String cacheName);
	}
}