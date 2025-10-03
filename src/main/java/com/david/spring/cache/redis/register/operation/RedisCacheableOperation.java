package com.david.spring.cache.redis.register.operation;

import com.david.spring.cache.redis.core.writer.support.PreRefreshMode;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.lang.NonNull;

@Getter
@EqualsAndHashCode(callSuper = true)
public class RedisCacheableOperation extends CacheOperation {

    private final String unless;
    private final boolean sync;
    private final long ttl;
    private final Class<?> type;
    private final boolean useSecondLevelCache;
    private final boolean cacheNullValues;
    private final boolean useBloomFilter;
    private final boolean randomTtl;
    private final float variance;
    private final boolean enablePreRefresh;
    private final double preRefreshThreshold;
    private final PreRefreshMode preRefreshMode;

    protected RedisCacheableOperation(Builder b) {
        super(b);
        this.unless = b.unless;
        this.sync = b.sync;
        this.ttl = b.ttl;
        this.type = b.type;
        this.useSecondLevelCache = b.useSecondLevelCache;
        this.cacheNullValues = b.cacheNullValues;
        this.useBloomFilter = b.useBloomFilter;
        this.randomTtl = b.randomTtl;
        this.variance = b.variance;
        this.enablePreRefresh = b.enablePreRefresh;
        this.preRefreshThreshold = b.preRefreshThreshold;
        this.preRefreshMode = b.preRefreshMode;
    }

    public static Builder builder() {
        return new Builder();
    }

    @EqualsAndHashCode(callSuper = true)
    public static class Builder extends CacheOperation.Builder {
        private String unless;
        private boolean sync;
        private long ttl;
        private Class<?> type;
        private boolean useSecondLevelCache;
        private boolean cacheNullValues;
        private boolean useBloomFilter;
        private boolean randomTtl;
        private float variance;
        private boolean enablePreRefresh;
        private double preRefreshThreshold;
        private PreRefreshMode preRefreshMode;

        public Builder name(String name) {
            super.setName(name);
            return this;
        }

        public Builder cacheNames(String... cacheNames) {
            super.setCacheNames(cacheNames);
            return this;
        }

        public Builder key(String key) {
            super.setKey(key);
            return this;
        }

        public Builder keyGenerator(String keyGenerator) {
            super.setKeyGenerator(keyGenerator);
            return this;
        }

        public Builder cacheManager(String cacheManager) {
            super.setCacheManager(cacheManager);
            return this;
        }

        public Builder condition(String condition) {
            super.setCondition(condition);
            return this;
        }

        public Builder cacheResolver(String cacheResolver) {
            super.setCacheResolver(cacheResolver);
            return this;
        }

        public Builder unless(String unless) {
            this.unless = unless;
            return this;
        }

        public Builder sync(boolean sync) {
            this.sync = sync;
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

        public Builder useSecondLevelCache(boolean useSecondLevelCache) {
            this.useSecondLevelCache = useSecondLevelCache;
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
