package com.david.spring.cache.redis.chain.protection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存雪崩防护：TTL 抖动策略。
 * <p>
 * 通过随机化TTL时间来避免大量缓存同时过期导致的缓存雪崩问题。
 * 默认在基础TTL的基础上随机减少5%-20%的时间。
 * </p>
 */
@Slf4j
@Component
public final class CacheAvalanche {

	/** 默认最小随机缩减比例 */
	private static final double DEFAULT_MIN_JITTER_RATIO = 0.05d;

	/** 默认最大随机缩减比例 */
	private static final double DEFAULT_MAX_JITTER_RATIO = 0.20d;

	/** TTL 的最小下限（秒） */
	private static final long MIN_TTL_SECONDS = 1L;

	/**
	 * 使用默认参数进行 TTL 抖动
	 *
	 * @param baseSeconds 原始 TTL（秒）
	 * @return 随机化后的 TTL（秒）
	 */
	public long jitterTtlSeconds(long baseSeconds) {
		return jitterTtlSeconds(baseSeconds, DEFAULT_MIN_JITTER_RATIO, DEFAULT_MAX_JITTER_RATIO);
	}

	/**
	 * 自定义参数进行 TTL 抖动
	 *
	 * @param baseSeconds    原始 TTL（秒）
	 * @param minJitterRatio 最小随机缩减比例（0-1之间）
	 * @param maxJitterRatio 最大随机缩减比例（0-1之间）
	 * @return 随机化后的 TTL（秒）
	 */
	public long jitterTtlSeconds(long baseSeconds, double minJitterRatio, double maxJitterRatio) {
		if (baseSeconds <= MIN_TTL_SECONDS) {
			return baseSeconds;
		}

		// 参数验证和范围调整
		double low = Math.max(0.0d, Math.min(minJitterRatio, 0.99d));
		double high = Math.max(low, Math.min(maxJitterRatio, 0.99d));

		// 计算随机缩减比例
		double ratio = (high > low) ? ThreadLocalRandom.current().nextDouble(low, high) : low;

		// 计算抖动后的 TTL
		long jitteredTtl = (long) Math.floor(baseSeconds * (1.0d - ratio));
		long result = Math.max(MIN_TTL_SECONDS, jitteredTtl);

		if (log.isDebugEnabled()) {
			log.debug("TTL jitter applied: original={}s, ratio={}, result={}s",
					baseSeconds, ratio, result);
		}

		return result;
	}

	/**
	 * 检查 TTL 是否需要抖动
	 *
	 * @param ttlSeconds TTL（秒）
	 * @return true 表示需要抖动，false 表示不需要
	 */
	public boolean shouldJitter(long ttlSeconds) {
		return ttlSeconds > MIN_TTL_SECONDS;
	}
}
