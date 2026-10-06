package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;

/** Projects each annotation once for the Spring AOP operation and active chain policy. */
class RedisCacheAttributesProjector {
    public RedisCacheAttributes from(RedisCacheable annotation) {
        if (annotation == null) {
            return null;
        }
        return RedisCacheAttributes.builder()
                .cacheNames(resolveCacheNames(annotation.cacheNames(), annotation.value()))
                .key(annotation.key())
                .keyGenerator(annotation.keyGenerator())
                .cacheManager(annotation.cacheManager())
                .cacheResolver(annotation.cacheResolver())
                .condition(annotation.condition())
                .unless(annotation.unless())
                .ttl(annotation.ttl())
                .type(annotation.type())
                .cacheNullValues(annotation.cacheNullValues())
                .useBloomFilter(annotation.useBloomFilter())
                .randomTtl(annotation.randomTtl())
                .variance(annotation.variance())
                .enableEarlyExpiration(annotation.enableEarlyExpiration())
                .earlyExpirationThreshold(annotation.earlyExpirationThreshold())
                .earlyExpirationMode(annotation.earlyExpirationMode())
                .sync(annotation.sync())
                .syncTimeout(annotation.syncTimeout())
                .build();
    }

    public RedisCacheAttributes from(RedisCachePut annotation) {
        if (annotation == null) {
            return null;
        }
        return RedisCacheAttributes.builder()
                .cacheNames(resolveCacheNames(annotation.cacheNames(), annotation.value()))
                .key(annotation.key())
                .keyGenerator(annotation.keyGenerator())
                .cacheManager(annotation.cacheManager())
                .cacheResolver(annotation.cacheResolver())
                .condition(annotation.condition())
                .unless(annotation.unless())
                .ttl(annotation.ttl())
                .type(annotation.type())
                .cacheNullValues(annotation.cacheNullValues())
                .useBloomFilter(annotation.useBloomFilter())
                .randomTtl(annotation.randomTtl())
                .variance(annotation.variance())
                .enableEarlyExpiration(annotation.enableEarlyExpiration())
                .earlyExpirationThreshold(annotation.earlyExpirationThreshold())
                .earlyExpirationMode(annotation.earlyExpirationMode())
                .sync(annotation.sync())
                .syncTimeout(annotation.syncTimeout())
                .build();
    }

    public RedisCacheAttributes from(RedisCacheEvict annotation) {
        if (annotation == null) {
            return null;
        }
        return RedisCacheAttributes.builder()
                .cacheNames(resolveCacheNames(annotation.cacheNames(), annotation.value()))
                .key(annotation.key())
                .keyGenerator(annotation.keyGenerator())
                .cacheManager(annotation.cacheManager())
                .cacheResolver(annotation.cacheResolver())
                .condition(annotation.condition())
                .allEntries(annotation.allEntries())
                .beforeInvocation(annotation.beforeInvocation())
                .build();
    }

    /** The value alias takes precedence when both names are supplied. */
    public static String[] resolveCacheNames(String[] cacheNames, String[] value) {
        if (value != null && value.length > 0) {
            return value;
        }
        return cacheNames != null ? cacheNames : new String[0];
    }
}
