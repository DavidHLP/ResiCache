package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.lang.Nullable;

/**
 * 缓存操作输入参数（不可变）
 * 
 * 包含请求的原始数据，在整个责任链中只读。
 * 设计为 record 确保不可变性。
 */
record CacheInput(
    /** 缓存操作类型 */
    CacheOperation operation,
    
    /** 缓存名称 */
    String cacheName,
    
    /** Redis 完整 key */
    String redisKey,
    
    /** 实际 key（去除前缀） */
    String actualKey,
    
    /** 缓存值（字节数组） */
    @Nullable byte[] valueBytes,
    
    /** 反序列化后的值 */
    @Nullable Object deserializedValue,
    
    /** TTL */
    @Nullable Duration ttl,
    
    /** 缓存操作配置 */
    @Nullable CachePolicyView.Source cacheOperation
) implements CacheContext.InputView {
    CacheInput {
        valueBytes = valueBytes == null ? null : Arrays.copyOf(valueBytes, valueBytes.length);
    }

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
        if (cacheOperation == null) {
            return CachePolicyView.NONE;
        }
        return new CachePolicyView(
                cacheOperation.getTtl(), cacheOperation.isRandomTtl(), cacheOperation.getVariance(),
                cacheOperation.isUseBloomFilter(), cacheOperation.isSync(), cacheOperation.getSyncTimeout(),
                cacheOperation.isCacheNullValues(), cacheOperation.isEnableEarlyExpiration(),
                cacheOperation.getEarlyExpirationThreshold(), cacheOperation.getEarlyExpirationMode());
    }
}
