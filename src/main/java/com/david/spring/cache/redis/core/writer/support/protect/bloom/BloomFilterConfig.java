package com.david.spring.cache.redis.core.writer.support.protect.bloom;

import lombok.Getter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Configuration holder for bloom filter behaviour. */
@Getter
@Component
public class BloomFilterConfig {

    private final String keyPrefix;
    private final int bitSize;
    private final int hashFunctions;

    public BloomFilterConfig(
            @Value("${cacheguard.bloom.prefix:bf:}") String keyPrefix,
            @Value("${cacheguard.bloom.bit-size:8388608}") int bitSize,
            @Value("${cacheguard.bloom.hash-functions:3}") int hashFunctions) {
        this.keyPrefix = keyPrefix;
        this.bitSize = bitSize;
        this.hashFunctions = Math.max(1, hashFunctions);
    }
}
