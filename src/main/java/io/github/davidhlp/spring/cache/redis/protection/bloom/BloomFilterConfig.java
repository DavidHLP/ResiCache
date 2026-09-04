package io.github.davidhlp.spring.cache.redis.protection.bloom;

import lombok.Getter;

/**
 * 布隆过滤器行为的配置持有者。
 */
@Getter
public class BloomFilterConfig {

	private final String keyPrefix;
	private final int bitSize;
	private final int hashFunctions;
	private final int hashCacheSize;

	public BloomFilterConfig(
			String keyPrefix, int bitSize, int hashFunctions, int hashCacheSize) {
		this.keyPrefix = keyPrefix;
		this.bitSize = Math.max(1, bitSize);
		this.hashFunctions = Math.max(1, hashFunctions);
		this.hashCacheSize = Math.max(1, hashCacheSize);
	}
}
