package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;

/** Typed common setters for the active read/write policy builders. */
interface RedisCacheAttributeSink {

    RedisCacheAttributeSink cacheNames(String... cacheNames);

    RedisCacheAttributeSink keyGenerator(String keyGenerator);

    RedisCacheAttributeSink cacheManager(String cacheManager);

    RedisCacheAttributeSink cacheResolver(String cacheResolver);

    RedisCacheAttributeSink condition(String condition);

    RedisCacheAttributeSink sync(boolean sync);

    RedisCacheAttributeSink syncTimeout(long syncTimeout);

    RedisCacheAttributeSink ttl(long ttl);

    RedisCacheAttributeSink useBloomFilter(boolean useBloomFilter);

    RedisCacheAttributeSink enableEarlyExpiration(boolean enableEarlyExpiration);

    RedisCacheAttributeSink earlyExpirationThreshold(double earlyExpirationThreshold);

    RedisCacheAttributeSink earlyExpirationMode(EarlyExpirationMode earlyExpirationMode);
}
