package io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 布隆过滤器行为的配置持有者。
 */
@Getter
@Component
public class BloomFilterConfig {

	private final String keyPrefix;
	private final int bitSize;
	private final int hashFunctions;
	private final int hashCacheSize;

	public BloomFilterConfig(
			@Value("${spring.resiCache.bloom.prefix:bf:}") String keyPrefix,
			@Value("${spring.resiCache.bloom.bit-size:8388608}") int bitSize,
			@Value("${spring.resiCache.bloom.hash-functions:3}") int hashFunctions,
			@Value("${spring.resiCache.bloom.hash-cache-size:10000}") int hashCacheSize) {
		this.keyPrefix = keyPrefix;
		this.bitSize = Math.max(1, bitSize);
		this.hashFunctions = Math.max(1, hashFunctions);
		this.hashCacheSize = Math.max(1, hashCacheSize);
	}
}
