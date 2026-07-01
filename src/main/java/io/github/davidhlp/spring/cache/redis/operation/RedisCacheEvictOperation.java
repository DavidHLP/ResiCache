package io.github.davidhlp.spring.cache.redis.operation;

import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
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
    private final boolean enableEarlyExpiration;
    private final double earlyExpirationThreshold;
    private final EarlyExpirationMode earlyExpirationMode;

    protected RedisCacheEvictOperation(Builder b) {
        super(b);
        this.sync = b.sync;
        this.syncTimeout = b.syncTimeout;
        this.ttl = b.ttl;
        this.useBloomFilter = b.useBloomFilter;
        this.expectedInsertions = b.expectedInsertions;
        this.falseProbability = b.falseProbability;
        this.enableEarlyExpiration = b.enableEarlyExpiration;
        this.earlyExpirationThreshold = b.earlyExpirationThreshold;
        this.earlyExpirationMode = b.earlyExpirationMode;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从 {@link RedisCacheAttributes} 投影构造 {@link RedisCacheEvictOperation} — 单一字段映射 seam
     * (ADR-0017)。
     *
     * <p>本方法替代原 {@code EvictOperationFactory.materialize} 的 18 行 builder 链。
     *
     * <p>Evict 的字段集是 Cacheable/Put 的<strong>子集 + Evict-only 字段</strong>:
     * <ul>
     *   <li><strong>缺失</strong>(无对应 Builder 槽位):{@code unless} / {@code type} /
     *       {@code cacheNullValues} / {@code randomTtl} / {@code variance} —
     *       Evict 不持有这些语义,故 fromAttributes 显式忽略(对应语义:"Evict 不缓存值,故无
     *       TTL 随机化/无空值/无类型槽位")</li>
     *   <li><strong>独有</strong>:{@code allEntries} / {@code beforeInvocation} —
     *       Evict-only 字段,直接映射</li>
     * </ul>
     *
     * <p>Factory 退化为单行委派:
     * <pre>
     *   return RedisCacheEvictOperation.fromAttributes(method, key, attributes);
     * </pre>
     */
    public static RedisCacheEvictOperation fromAttributes(
            java.lang.reflect.Method method, String key, RedisCacheAttributes a) {
        Builder b = builder();
        b.name(method.getName())
                .key(key)
                .cacheNames(a.getCacheNames())
                .keyGenerator(a.getKeyGenerator())
                .cacheManager(a.getCacheManager())
                .cacheResolver(a.getCacheResolver())
                .condition(a.getCondition())
                .allEntries(a.isAllEntries())
                .beforeInvocation(a.isBeforeInvocation())
                .sync(a.isSync())
                .syncTimeout(a.getSyncTimeout())
                .ttl(a.getTtl())
                .useBloomFilter(a.isUseBloomFilter())
                .expectedInsertions(a.getExpectedInsertions())
                .falseProbability(a.getFalseProbability())
                .enableEarlyExpiration(a.isEnableEarlyExpiration())
                .earlyExpirationThreshold(a.getEarlyExpirationThreshold())
                .earlyExpirationMode(a.getEarlyExpirationMode());
        return b.build();
    }


    @EqualsAndHashCode(callSuper = true)
    public static class Builder extends CacheEvictOperation.Builder {
        private boolean sync;
        private long syncTimeout = -1;
        private long ttl = 0;
        private boolean useBloomFilter;
        private long expectedInsertions = 100000;
        private double falseProbability = 0.01;
        private boolean enableEarlyExpiration;
        private double earlyExpirationThreshold = 0.3;
        private EarlyExpirationMode earlyExpirationMode = EarlyExpirationMode.SYNC;

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

        public Builder enableEarlyExpiration(boolean enableEarlyExpiration) {
            this.enableEarlyExpiration = enableEarlyExpiration;
            return this;
        }

        public Builder earlyExpirationThreshold(double earlyExpirationThreshold) {
            this.earlyExpirationThreshold = earlyExpirationThreshold;
            return this;
        }

        public Builder earlyExpirationMode(EarlyExpirationMode earlyExpirationMode) {
            this.earlyExpirationMode = earlyExpirationMode;
            return this;
        }

        @Override
        @NonNull
        public RedisCacheEvictOperation build() {
            return new RedisCacheEvictOperation(this);
        }
    }
}
