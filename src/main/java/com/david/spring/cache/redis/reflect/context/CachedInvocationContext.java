package com.david.spring.cache.redis.reflect.context;

import com.david.spring.cache.redis.reflect.interfaces.InvocationContext;
import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 缓存调用上下文实现类
 * 使用分组设计提高可读性和可维护性
 *
 * @author David
 */
@Builder
public record CachedInvocationContext(
		// 基础配置
		@Nullable String[] cacheNames,
		@Nullable String[] value,
		@Nullable String key,
		@Nullable String condition,
		@Nullable String unless,
		boolean sync,

		// Spring集成配置
		@Nullable String keyGenerator,
		@Nullable String cacheManager,
		@Nullable String cacheResolver,

		// 缓存行为配置
		long ttl,
		@Nullable Class<?> type,
		boolean cacheNullValues,
		boolean useSecondLevelCache,

		// 保护机制配置
		ProtectionConfig protectionConfig,

		// TTL配置
		TtlConfig ttlConfig
) implements InvocationContext {

	// InvocationContext接口实现
	@Override
	public String[] getCacheNames() {
		return cacheNames != null ? cacheNames : (value != null ? value : new String[0]);
	}

	@Override
	@Nullable
	public String getKey() {
		return key;
	}

	@Override
	@Nullable
	public String getCondition() {
		return condition;
	}

	@Override
	@Nullable
	public String getKeyGenerator() {
		return keyGenerator;
	}

	@Override
	@Nullable
	public String getCacheManager() {
		return cacheManager;
	}

	@Override
	@Nullable
	public String getCacheResolver() {
		return cacheResolver;
	}

	@Override
	public boolean isSync() {
		return sync;
	}

	@Override
	public String getContextType() {
		return "CachedInvocation";
	}

	// 便利方法，提供简洁的API访问
	public boolean distributedLock() {
		return protectionConfig != null && protectionConfig.distributedLock();
	}

	public String distributedLockName() {
		return protectionConfig != null ? protectionConfig.distributedLockName() : null;
	}

	public boolean internalLock() {
		return protectionConfig != null && protectionConfig.internalLock();
	}

	public boolean useBloomFilter() {
		return protectionConfig != null && protectionConfig.useBloomFilter();
	}

	public boolean randomTtl() {
		return ttlConfig != null && ttlConfig.randomTtl();
	}

	public float variance() {
		return ttlConfig != null ? ttlConfig.variance() : 0.0f;
	}

	/**
	 * 检查是否需要保护机制
	 *
	 * @return 如果需要任何保护机制则返回true
	 */
	public boolean needsProtection() {
		return protectionConfig != null && (
				protectionConfig.distributedLock() ||
						protectionConfig.internalLock() ||
						protectionConfig.useBloomFilter()
		);
	}

	/**
	 * 检查TTL配置是否有效
	 *
	 * @return 如果TTL配置有效则返回true
	 */
	public boolean isValidTtlConfig() {
		return ttlConfig == null || ttlConfig.isValid();
	}

	/**
	 * 保护机制配置
	 */
	@Builder
	public record ProtectionConfig(
			boolean distributedLock,
			@Nullable String distributedLockName,
			boolean internalLock,
			boolean useBloomFilter
	) {
		public static ProtectionConfig defaultConfig() {
			return ProtectionConfig.builder().build();
		}

		public boolean hasLockProtection() {
			return distributedLock || internalLock;
		}
	}

	/**
	 * TTL配置
	 */
	@Builder
	public record TtlConfig(
			boolean randomTtl,
			float variance
	) {
		public static TtlConfig defaultConfig() {
			return TtlConfig.builder().variance(0.1f).build();
		}

		public boolean isValid() {
			return variance >= 0.0f && variance <= 1.0f;
		}
	}

}
