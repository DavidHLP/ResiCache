package io.github.davidhlp.spring.cache.redis.register.operation;

import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.lang.NonNull;

/**
 * Redis cacheable operation that extends Spring's {@link CacheableOperation}
 * to participate in the standard cacheable execution path while carrying
 * ResiCache-specific metadata.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
public class RedisCacheableOperation extends CacheableOperation {

    private final long ttl;
    private final Class<?> type;
    private final boolean cacheNullValues;
    private final boolean useBloomFilter;
    private final boolean randomTtl;
    private final float variance;
    private final boolean enablePreRefresh;
    private final double preRefreshThreshold;
    private final PreRefreshMode preRefreshMode;
    private final long syncTimeout;

    protected RedisCacheableOperation(Builder b) {
        super(b);
        this.ttl = b.ttl;
        this.type = b.type;
        this.cacheNullValues = b.cacheNullValues;
        this.useBloomFilter = b.useBloomFilter;
        this.randomTtl = b.randomTtl;
        this.variance = b.variance;
        this.enablePreRefresh = b.enablePreRefresh;
        this.preRefreshThreshold = b.preRefreshThreshold;
        this.preRefreshMode = b.preRefreshMode;
        this.syncTimeout = b.syncTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    @EqualsAndHashCode(callSuper = true)
    public static class Builder extends CacheableOperation.Builder {
        private long ttl = 0;
        private Class<?> type = Object.class;
        private boolean cacheNullValues;
        private boolean useBloomFilter;
        private boolean randomTtl;
        private float variance = 0.2F;
        private boolean enablePreRefresh;
        private double preRefreshThreshold = 0.3;
        private PreRefreshMode preRefreshMode = PreRefreshMode.SYNC;
        private long syncTimeout = 10;

        public Builder name(String name) {
            setName(name);
            return this;
        }

        public Builder cacheNames(String... cacheNames) {
            setCacheNames(cacheNames);
            return this;
        }

        public Builder key(String key) {
            setKey(key);
            return this;
        }

        public Builder keyGenerator(String keyGenerator) {
            setKeyGenerator(keyGenerator);
            return this;
        }

        public Builder cacheManager(String cacheManager) {
            setCacheManager(cacheManager);
            return this;
        }

        public Builder cacheResolver(String cacheResolver) {
            setCacheResolver(cacheResolver);
            return this;
        }

        public Builder condition(String condition) {
            setCondition(condition);
            return this;
        }

        public Builder unless(String unless) {
            setUnless(unless);
            return this;
        }

        public Builder sync(boolean sync) {
            setSync(sync);
            return this;
        }

        public Builder syncTimeout(long syncTimeout) {
            this.syncTimeout = syncTimeout;
            return this;
        }

        public Builder ttl(long ttl) {
            this.ttl = ttl;
            return this;
        }

        public Builder type(Class<?> type) {
            this.type = type;
            return this;
        }

        public Builder cacheNullValues(boolean cacheNullValues) {
            this.cacheNullValues = cacheNullValues;
            return this;
        }

        public Builder useBloomFilter(boolean useBloomFilter) {
            this.useBloomFilter = useBloomFilter;
            return this;
        }

        public Builder randomTtl(boolean randomTtl) {
            this.randomTtl = randomTtl;
            return this;
        }

        public Builder variance(float variance) {
            this.variance = variance;
            return this;
        }

        public Builder enablePreRefresh(boolean enablePreRefresh) {
            this.enablePreRefresh = enablePreRefresh;
            return this;
        }

        public Builder preRefreshThreshold(double preRefreshThreshold) {
            this.preRefreshThreshold = preRefreshThreshold;
            return this;
        }

        public Builder preRefreshMode(PreRefreshMode preRefreshMode) {
            this.preRefreshMode = preRefreshMode;
            return this;
        }

        @Override
        @NonNull
        public RedisCacheableOperation build() {
            return new RedisCacheableOperation(this);
        }
    }
}
