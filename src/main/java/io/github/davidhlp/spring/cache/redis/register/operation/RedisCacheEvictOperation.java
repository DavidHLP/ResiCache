package io.github.davidhlp.spring.cache.redis.register.operation;

import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.lang.NonNull;

/**
 * Redis cache evict operation that extends Spring's {@link CacheEvictOperation}
 * to participate in the standard cache evict execution path while carrying
 * ResiCache-specific metadata.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
public class RedisCacheEvictOperation extends CacheEvictOperation {

    private final boolean sync;
    private final long syncTimeout;
    private final long ttl;
    private final boolean useBloomFilter;
    private final long expectedInsertions;
    private final double falseProbability;
    private final boolean enablePreRefresh;
    private final double preRefreshThreshold;
    private final PreRefreshMode preRefreshMode;

    protected RedisCacheEvictOperation(Builder b) {
        super(b);
        this.sync = b.sync;
        this.syncTimeout = b.syncTimeout;
        this.ttl = b.ttl;
        this.useBloomFilter = b.useBloomFilter;
        this.expectedInsertions = b.expectedInsertions;
        this.falseProbability = b.falseProbability;
        this.enablePreRefresh = b.enablePreRefresh;
        this.preRefreshThreshold = b.preRefreshThreshold;
        this.preRefreshMode = b.preRefreshMode;
    }

    public static Builder builder() {
        return new Builder();
    }

    @EqualsAndHashCode(callSuper = true)
    public static class Builder extends CacheEvictOperation.Builder {
        private boolean sync;
        private long syncTimeout = -1;
        private long ttl = 0;
        private boolean useBloomFilter;
        private long expectedInsertions = 100000;
        private double falseProbability = 0.01;
        private boolean enablePreRefresh;
        private double preRefreshThreshold = 0.3;
        private PreRefreshMode preRefreshMode = PreRefreshMode.SYNC;

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

        public Builder allEntries(boolean allEntries) {
            setCacheWide(allEntries);
            return this;
        }

        public Builder beforeInvocation(boolean beforeInvocation) {
            setBeforeInvocation(beforeInvocation);
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

        public Builder ttl(long ttl) {
            this.ttl = ttl;
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
        public RedisCacheEvictOperation build() {
            return new RedisCacheEvictOperation(this);
        }
    }
}
