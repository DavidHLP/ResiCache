package com.david.spring.cache.redis.registry;

import com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.impl.EvictInvocationRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 注册表工厂类
 * 遵循开放封闭原则，支持扩展而不修改现有代码
 */
@Getter
@Slf4j
@Component
public class RegistryFactory {

	/** 缓存调用注册表 */
	private final CacheInvocationRegistry cacheInvocationRegistry;

	/** 驱逐调用注册表 */
	private final EvictInvocationRegistry evictInvocationRegistry;

	public RegistryFactory(CacheInvocationRegistry cacheInvocationRegistry,
	                       EvictInvocationRegistry evictInvocationRegistry) {
		this.cacheInvocationRegistry = cacheInvocationRegistry;
		this.evictInvocationRegistry = evictInvocationRegistry;
	}

	/**
	 * 根据类型获取对应的注册表
	 */
	@SuppressWarnings("unchecked")
	public <T> AbstractInvocationRegistry<T> getRegistry(RegistryType type) {
		return switch (type) {
			case CACHE_INVOCATION -> (AbstractInvocationRegistry<T>) cacheInvocationRegistry;
			case EVICT_INVOCATION -> (AbstractInvocationRegistry<T>) evictInvocationRegistry;
		};
	}

	/**
	 * 获取所有注册表的统计信息
	 */
	public RegistryStats getAllRegistryStats() {
		return new RegistryStats(
				cacheInvocationRegistry.size(),
				evictInvocationRegistry.size()
		);
	}

	/**
	 * 注册表类型枚举
	 */
	public enum RegistryType {
		CACHE_INVOCATION,
		EVICT_INVOCATION
	}

	/**
	 * 注册表统计信息记录类
	 */
	public record RegistryStats(
			int cacheInvocations,
			int evictInvocations
	) {
		public int totalInvocations() {
			return cacheInvocations + evictInvocations;
		}
	}
}