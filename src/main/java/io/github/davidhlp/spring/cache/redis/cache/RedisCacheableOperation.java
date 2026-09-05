package io.github.davidhlp.spring.cache.redis.cache;




import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
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
class RedisCacheableOperation extends CacheableOperation
        implements io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView.Source {

    private final long ttl;
    private final Class<?> type;
    private final boolean cacheNullValues;
    private final boolean useBloomFilter;
    private final long expectedInsertions;
    private final double falseProbability;
    private final boolean randomTtl;
    private final float variance;
    private final boolean enableEarlyExpiration;
    private final double earlyExpirationThreshold;
    private final EarlyExpirationMode earlyExpirationMode;
    private final long syncTimeout;

    protected RedisCacheableOperation(Builder b) {
        super(b);
        this.ttl = b.ttl;
        this.type = b.type;
        this.cacheNullValues = b.cacheNullValues;
        this.useBloomFilter = b.useBloomFilter;
        this.expectedInsertions = b.expectedInsertions;
        this.falseProbability = b.falseProbability;
        this.randomTtl = b.randomTtl;
        this.variance = b.variance;
        this.enableEarlyExpiration = b.enableEarlyExpiration;
        this.earlyExpirationThreshold = b.earlyExpirationThreshold;
        this.earlyExpirationMode = b.earlyExpirationMode;
        this.syncTimeout = b.syncTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从 {@link RedisCacheAttributes} 投影构造 {@link RedisCacheableOperation} — 单一字段映射 seam。
     *
     * <p>本方法为 1 行委派,把"attribute → operation field"的映射知识完全下放给
     * {@link RedisCacheAttributes#applyTo(RedisCacheableOperation.Builder)} (字段拥有者)。
     *
     * <p>Factory 调用形态:
     * <pre>
     *   return RedisCacheableOperation.fromAttributes(method, key, attributes);
     * </pre>
     *
     * <p>字段映射规则:22 字段全量应用;{@code expectedInsertions} 在 Cacheable Builder 是
     * {@code long} 槽位,直传无窄化,由 {@link RedisCacheAttributes#applyTo(RedisCacheableOperation.Builder)}
     * 内部决定。
     *
     * <p>本方法<strong>不是</strong> Spring {@code @Cacheable} 适配路径,后者经
     * {@code SpringCacheableAdapter} 处理(走 hasText 守卫,因
     * Spring {@code CacheableOperation.Builder} 对 null/空串敏感)。
     */
    public static RedisCacheableOperation fromAttributes(
            java.lang.reflect.Method method, String key, RedisCacheAttributes a) {
        return a.applyTo(builder().name(method.getName()).key(key)).build();
    }


    @EqualsAndHashCode(callSuper = true)
    public static class Builder extends CacheableOperation.Builder implements RedisCacheAttributeSink {
        private long ttl = 0;
        private Class<?> type = Object.class;
        private boolean cacheNullValues;
        private boolean useBloomFilter;
        private long expectedInsertions = 100000L;
        private double falseProbability = 0.01;
        private boolean randomTtl;
        private float variance = 0.2F;
        private boolean enableEarlyExpiration;
        private double earlyExpirationThreshold = 0.3;
        private EarlyExpirationMode earlyExpirationMode = EarlyExpirationMode.SYNC;
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

        public Builder expectedInsertions(long expectedInsertions) {
            this.expectedInsertions = expectedInsertions;
            return this;
        }

        public Builder falseProbability(double falseProbability) {
            this.falseProbability = falseProbability;
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
        public RedisCacheableOperation build() {
            return new RedisCacheableOperation(this);
        }
    }
}
