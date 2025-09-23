package com.david.spring.cache.redis.chain.protection;

import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.lock.LockUtils;
import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 缓存击穿防护工具。
 * <p>
 * 通过双重锁定（本地锁 + 分布式锁）+ 三重检查模式，确保集群内只有一个请求回源，
 * 避免热点数据失效时的并发击穿问题。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class CacheBreakdown {

	private final DistributedLock distributedLock;

	/**
	 * 在双重锁保护下加载数据，支持三重检查
	 *
	 * @param name          操作名称，用于日志
	 * @param distKey       分布式锁键
	 * @param localLock     本地锁
	 * @param distLeaseTime 分布式锁租约时间
	 * @param unit          时间单位
	 * @param cacheReader   缓存读取器
	 * @param loader        数据加载器（可能抛异常）
	 * @param cacheWriter   缓存写入器
	 * @return 加载的数据
	 */
	@Nonnull
	public <T> T loadWithProtection(
			@Nonnull String name,
			@Nonnull String distKey,
			@Nonnull ReentrantLock localLock,
			long distLeaseTime,
			@Nonnull TimeUnit unit,
			@Nonnull Supplier<T> cacheReader,
			@Nonnull LockUtils.ThrowingSupplier<T> loader,
			@Nonnull Consumer<T> cacheWriter) throws Exception {

		// 第一重检查：无锁快速检查
		T value = cacheReader.get();
		if (value != null) {
			log.debug("Cache hit before lock: {}", name);
			return value;
		}

		// 第二重检查：本地锁保护
		localLock.lock();
		try {
			value = cacheReader.get();
			if (value != null) {
				log.debug("Cache hit after local lock: {}", name);
				return value;
			}

			// 第三重检查：分布式锁保护
			distributedLock.lock(distKey, distLeaseTime, unit);
			try {
				T finalValue = cacheReader.get();
				if (finalValue != null) {
					log.debug("Cache hit after distributed lock: {}", name);
					return finalValue;
				}

				// 真正的数据加载
				T loadedValue = loader.get();
				if (loadedValue == null) {
					throw new IllegalStateException("Loader returned null for: " + name);
				}
				cacheWriter.accept(loadedValue);
				log.debug("Data loaded and cached: {}, type: {}", name, loadedValue.getClass().getSimpleName());
				return loadedValue;
			} finally {
				distributedLock.unlock(distKey);
			}

		} finally {
			localLock.unlock();
		}
	}

}
