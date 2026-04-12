package io.github.davidhlp.spring.cache.redis.register.operation;

import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.lang.NonNull;

@Getter
@EqualsAndHashCode(callSuper = true)
public class RedisCachePutOperation extends CacheOperation {

    private final String unless;
    private final long ttl;
    private final Class<?> type;
    private final boolean cacheNullValues;
    private final boolean useBloomFilter;
    private final long expectedInsertions;
    private final double falseProbability;
    private final boolean sync;
    private final long syncTimeout;
    private final boolean randomTtl;
    private final float variance;
    private final boolean enablePreRefresh;
    private final double preRefreshThreshold;
    private final PreRefreshMode preRefreshMode;

    protected RedisCachePutOperation(Builder b) {
        super(b);
        this.unless = b.unless;
        this.ttl = b.ttl;
        this.type = b.type;
        this.cacheNullValues = b.cacheNullValues;
        this.useBloomFilter = b.useBloomFilter;
        this.expectedInsertions = b.expectedInsertions;
        this.falseProbability = b.falseProbability;
        this.sync = b.sync;
        this.syncTimeout = b.syncTimeout;
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
        private String unless = "";
        private long ttl = 60;
        private Class<?> type = Object.class;
        private boolean cacheNullValues;
        private boolean useBloomFilter;
        private long expectedInsertions = 100000;
        private double falseProbability = 0.01;
        private boolean sync;
        private long syncTimeout = -1;
        private boolean randomTtl;
        private float variance = 0.2F;
        private boolean enablePreRefresh;
        private double preRefreshThreshold = 0.3;
        private PreRefreshMode preRefreshMode = PreRefreshMode.SYNC;

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

        public Builder cacheResolver(String cacheResolver) {
            super.setCacheResolver(cacheResolver);
            return this;
        }

        public Builder condition(String condition) {
            super.setCondition(condition);
            return this;
        }

        public Builder unless(String unless) {
            this.unless = unless;
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

        public Builder expectedInsertions(long expectedInsertions) {
            this.expectedInsertions = expectedInsertions;
            return this;
        }

        public Builder falseProbability(double falseProbability) {
            this.falseProbability = falseProbability;
            return this;
        }

        public Builder sync(boolean sync) {
            this.sync = sync;
            return this;
        }

        public Builder syncTimeout(long syncTimeout) {
            this.syncTimeout = syncTimeout;
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
        public RedisCachePutOperation build() {
            return new RedisCachePutOperation(this);
        }
    }
}
