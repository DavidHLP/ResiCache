package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 缓存获取策略接口。
 * <p>
 * 定义不同的缓存获取行为，支持责任链模式执行多个策略，每个策略可独立处理特定场景。
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 */
public interface CacheFetchStrategy {

	/** 默认策略优先级 */
	int DEFAULT_ORDER = 100;

	/** 高优先级策略 */
	int HIGH_PRIORITY = 10;

	/**
	 * 执行缓存获取策略。
	 * <p>
	 * 这是策略的核心方法，实现具体的缓存获取逻辑。
	 * </p>
	 *
	 * @param context 策略执行上下文
	 * @return 缓存值包装器，null表示未命中或策略不适用
	 */
	@Nullable
	ValueWrapper fetch(@Nonnull CacheFetchContext context);

	/**
	 * 判断策略是否支持给定的调用上下文。
	 * <p>
	 * 用于快速过滤不适用的策略，提高执行效率。
	 * </p>
	 *
	 * @param invocationContext 缓存调用上下文
	 * @return true表示支持
	 */
	boolean supports(@Nonnull CachedInvocationContext invocationContext);

	/**
	 * 获取策略优先级。数字越小优先级越高。
	 *
	 * @return 优先级数值
	 */
	default int getOrder() {
		return DEFAULT_ORDER;
	}

	/**
	 * 获取策略名称。
	 *
	 * @return 策略名称
	 */
	@Nonnull
	default String getName() {
		return this.getClass().getSimpleName();
	}

	/**
	 * 当策略返回null时是否停止后续策略执行。
	 *
	 * @return true表示停止执行
	 */
	default boolean shouldStopOnNull() {
		return false;
	}

	/**
	 * 当策略抛出异常时是否停止后续策略执行。
	 *
	 * @return true表示停止执行
	 */
	default boolean shouldStopOnException() {
		return false;
	}

	/**
	 * 快速验证上下文的基本有效性。
	 *
	 * @param context 策略执行上下文
	 * @return true表示上下文有效
	 */
	default boolean isValidContext(@Nullable CacheFetchContext context) {
		return context == null;
	}

	/**
	 * 缓存获取回调接口。
	 * <p>
	 * 提供策略与缓存系统交互的标准接口。
	 * </p>
	 */
	interface CacheFetchCallback {

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
	}

	/**
	 * 策略执行上下文。
	 *
	 * @param cacheName         缓存名称
	 * @param key               业务缓存键
	 * @param cacheKey          Redis存储键
	 * @param valueWrapper      当前缓存值包装器
	 * @param invocation        缓存调用信息
	 * @param invocationContext 调用上下文，包含注解配置
	 * @param redisTemplate     Redis操作模板
	 * @param callback          回调接口
	 */
	record CacheFetchContext(
			@Nonnull String cacheName,
			@Nonnull Object key,
			@Nonnull String cacheKey,
			@Nullable ValueWrapper valueWrapper,
			@Nonnull CachedInvocation invocation,
			@Nonnull CachedInvocationContext invocationContext,
			@Nonnull RedisTemplate<String, Object> redisTemplate,
			@Nonnull CacheFetchCallback callback
	) {

		/**
		 * 检查当前是否有缓存值。
		 */
		public boolean hasValue() {
			return valueWrapper != null && valueWrapper.get() != null;
		}

		/**
		 * 安全地获取缓存值。
		 */
		@Nullable
		public Object getValue() {
			return valueWrapper != null ? valueWrapper.get() : null;
		}

	}
}