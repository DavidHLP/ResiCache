package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import java.time.Duration;
import java.util.Arrays;

/**
 * 缓存操作输入参数（不可变）
 *
 * 包含请求的原始数据，在整个责任链中只读。
 * 构造时生成一次不可变策略视图。
 */
final class CacheInput implements CacheContext.InputView {
    private final CachePolicyView policy;

    private final CacheOperation operation;
    private final String cacheName;
    private final String redisKey;
    private final String actualKey;
    private final byte[] valueBytes;
    private final Object deserializedValue;
    private final Duration ttl;

    CacheInput(CacheOperation operation, String cacheName, String redisKey, String actualKey,
               byte[] valueBytes, Object deserializedValue, Duration ttl, CachePolicyView.Source cacheOperation) {
        this.operation = operation;
        this.cacheName = cacheName;
        this.redisKey = redisKey;
        this.actualKey = actualKey;
        this.valueBytes = valueBytes == null ? null : Arrays.copyOf(valueBytes, valueBytes.length);
        this.deserializedValue = deserializedValue;
        this.ttl = ttl;
        policy = cacheOperation == null ? CachePolicyView.NONE : new CachePolicyView(
                cacheOperation.getTtl(), cacheOperation.isRandomTtl(), cacheOperation.getVariance(),
                cacheOperation.isUseBloomFilter(), cacheOperation.isSync(), cacheOperation.getSyncTimeout(),
                cacheOperation.isCacheNullValues(), cacheOperation.isEnableEarlyExpiration(),
                cacheOperation.getEarlyExpirationThreshold(), cacheOperation.getEarlyExpirationMode());
    }

    public CacheOperation operation() { return operation; }
    public String cacheName() { return cacheName; }
    public String redisKey() { return redisKey; }
    public String actualKey() { return actualKey; }
    public byte[] valueBytes() { return valueBytes; }
    public Object deserializedValue() { return deserializedValue; }
    public Duration ttl() { return ttl; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private CacheOperation operation;
        private String cacheName;
        private String redisKey;
        private String actualKey;
        private byte[] valueBytes;
        private Object deserializedValue;
        private Duration ttl;
        private CachePolicyView.Source cacheOperation;

        public Builder operation(CacheOperation operation) { this.operation = operation; return this; }
        public Builder cacheName(String cacheName) { this.cacheName = cacheName; return this; }
        public Builder redisKey(String redisKey) { this.redisKey = redisKey; return this; }
        public Builder actualKey(String actualKey) { this.actualKey = actualKey; return this; }
        public Builder valueBytes(byte[] valueBytes) { this.valueBytes = valueBytes; return this; }
        public Builder deserializedValue(Object value) { this.deserializedValue = value; return this; }
        public Builder ttl(Duration ttl) { this.ttl = ttl; return this; }
        public Builder cacheOperation(CachePolicyView.Source op) { this.cacheOperation = op; return this; }

        public CacheInput build() {
            return new CacheInput(
                operation, cacheName, redisKey, actualKey,
                valueBytes, deserializedValue, ttl, cacheOperation
            );
        }
    }

    @Override
    public CachePolicyView policy() {
        return policy;
    }
}
