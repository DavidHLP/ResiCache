package com.david.spring.cache.redis.reflect.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.lang.Nullable;

/**
 * Bean 解析支持抽象类，为需要解析 Spring Bean 的类提供通用功能
 * 使用统一的 BeanResolver 提供懒加载、缓存和线程安全的 Bean 解析机制
 */
@Slf4j
public abstract class BeanResolvableSupport {

	// 已解析的 Bean 实例缓存（使用 transient 避免序列化）
	private transient KeyGenerator resolvedKeyGenerator;
	private transient CacheResolver resolvedCacheResolver;

	/**
	 * 获取 KeyGenerator Bean 名称
	 * 子类需要实现此方法提供 Bean 名称
	 */
	@Nullable
	protected abstract String getKeyGeneratorName();

	/**
	 * 获取 CacheResolver Bean 名称
	 * 子类需要实现此方法提供 Bean 名称
	 */
	@Nullable
	protected abstract String getCacheResolverName();

	/**
	 * 获取目标方法的描述信息，用于日志记录
	 * 子类可以重写此方法提供更详细的信息
	 */
	protected String getTargetMethodDescription() {
		return "unknown";
	}

	/**
	 * 懒加载解析 KeyGenerator Bean
	 * 优先按名称解析，失败则按类型解析
	 * 解析结果会缓存在内存中，提高性能
	 *
	 * @return KeyGenerator 实例，如果解析失败返回 null
	 */
	@Nullable
	public final KeyGenerator resolveKeyGenerator() {
		String kgName = getKeyGeneratorName();
		log.debug("Resolving KeyGenerator (name: {}) for {}", kgName, getTargetMethodDescription());

		// 使用统一的 BeanResolver 静态方法
		KeyGenerator kg = BeanResolver.resolveKeyGenerator(this.resolvedKeyGenerator, kgName);
		this.resolvedKeyGenerator = kg;

		return kg;
	}

	/**
	 * 懒加载解析 CacheResolver Bean
	 * 优先按名称解析，失败则按类型解析
	 * 解析结果会缓存在内存中，提高性能
	 *
	 * @return CacheResolver 实例，如果解析失败返回 null
	 */
	@Nullable
	public final CacheResolver resolveCacheResolver() {
		String crName = getCacheResolverName();
		log.debug("Resolving CacheResolver (name: {}) for {}", crName, getTargetMethodDescription());

		// 使用统一的 BeanResolver 静态方法
		CacheResolver cr = BeanResolver.resolveCacheResolver(this.resolvedCacheResolver, crName);
		this.resolvedCacheResolver = cr;

		return cr;
	}

	/**
	 * 清除已缓存的解析 Bean，强制下次调用时重新从上下文解析
	 * 在 Spring 上下文刷新或 Bean 定义变更时可以调用此方法
	 */
	public final void clearResolved() {
		boolean hadKg = this.resolvedKeyGenerator != null;
		boolean hadCr = this.resolvedCacheResolver != null;

		log.info("Clearing resolved beans for {} -> keyGeneratorPresent: {}, cacheResolverPresent: {}",
				getTargetMethodDescription(), hadKg, hadCr);

		this.resolvedKeyGenerator = null;
		this.resolvedCacheResolver = null;
	}

	/**
	 * 检查是否有已解析的 KeyGenerator
	 */
	public final boolean hasResolvedKeyGenerator() {
		return resolvedKeyGenerator != null;
	}

	/**
	 * 检查是否有已解析的 CacheResolver
	 */
	public final boolean hasResolvedCacheResolver() {
		return resolvedCacheResolver != null;
	}

	/**
	 * 获取解析状态信息
	 */
	public final BeanResolveStatus getResolveStatus() {
		return new BeanResolveStatus(
				hasResolvedKeyGenerator(),
				hasResolvedCacheResolver(),
				getKeyGeneratorName(),
				getCacheResolverName(),
				getTargetMethodDescription());
	}

	/**
	 * Bean 解析状态记录类
	 */
	public record BeanResolveStatus(
			boolean keyGeneratorResolved,
			boolean cacheResolverResolved,
			String keyGeneratorName,
			String cacheResolverName,
			String targetMethodDescription) {
	}
}
