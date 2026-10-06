package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import java.lang.annotation.Annotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Annotation aliases, active policy and compatibility-only sizing contracts. */
@DisplayName("RedisCacheAttributesProjector Tests")
class RedisCacheAttributesProjectorTest {

    private final RedisCacheAttributesProjector projector = new RedisCacheAttributesProjector();

    @Test
    void nullAnnotationsHaveNoProjection() {
        assertThat(projector.from((RedisCacheable) null)).isNull();
        assertThat(projector.from((RedisCachePut) null)).isNull();
        assertThat(projector.from((RedisCacheEvict) null)).isNull();
    }

    @Test
    void activeDefaultsAndOverridesAreProjected() {
        RedisCacheAttributes read = projector.from(stubCacheable(s -> {}));
        RedisCacheAttributes put = projector.from(stubPut(s -> {}));
        assertThat(read).isEqualTo(put);
        assertThat(read.getTtl()).isEqualTo(60L);
        assertThat(read.getSyncTimeout()).isEqualTo(10L);
        assertThat(read.getVariance()).isEqualTo(0.2F);
        assertThat(read.getEarlyExpirationMode()).isEqualTo(EarlyExpirationMode.SYNC);
        RedisCacheAttributes custom = projector.from(stubPut(s -> {
            s.ttl = 120L;
            s.syncTimeout = 60L;
            s.useBloomFilter = true;
        }));
        assertThat(custom.getTtl()).isEqualTo(120L);
        assertThat(custom.getSyncTimeout()).isEqualTo(60L);
        assertThat(custom.isUseBloomFilter()).isTrue();
    }

    @Test
    void valueAliasWinsAndNamesFallbackIsStable() {
        assertThat(projector.from(stubCacheable(s -> {
            s.values = new String[]{"alias"};
            s.cacheNames = new String[]{"names"};
        })).getCacheNames()).containsExactly("alias");
        assertThat(projector.from(stubPut(s -> s.cacheNames = new String[]{"names"}))
                .getCacheNames()).containsExactly("names");
        assertThat(RedisCacheAttributesProjector.resolveCacheNames(null, null)).isEmpty();
    }

    @Test
    void bloomSizingMembersRemainCompatibleButDoNotEnterRuntimePolicy() throws Exception {
        for (Class<?> annotation : java.util.List.of(RedisCacheable.class, RedisCachePut.class, RedisCacheEvict.class)) {
            assertThat(annotation.getMethod("expectedInsertions").getReturnType()).isEqualTo(long.class);
            assertThat(annotation.getMethod("expectedInsertions").getDefaultValue()).isEqualTo(100_000L);
            assertThat(annotation.getMethod("falseProbability").getDefaultValue()).isEqualTo(0.01);
        }
        assertThat(projector.from(stubCacheable(s -> {
            s.expectedInsertions = Long.MAX_VALUE;
            s.falseProbability = 0.0001;
        }))).isEqualTo(projector.from(stubCacheable(s -> {})));
    }

    @Test
    void evictionProjectsOnlyAopFields() {
        RedisCacheAttributes evict = projector.from(stubEvict(s -> {
            s.cacheNames = new String[]{"evict"};
            s.allEntries = true;
            s.beforeInvocation = true;
            s.ttl = 120L;
            s.sync = true;
            s.useBloomFilter = true;
        }));
        assertThat(evict.getCacheNames()).containsExactly("evict");
        assertThat(evict.isAllEntries()).isTrue();
        assertThat(evict.isBeforeInvocation()).isTrue();
        assertThat(evict.getTtl()).isZero();
        assertThat(evict.isSync()).isFalse();
        assertThat(evict.isUseBloomFilter()).isFalse();
    }

    // ----- Test stubs -----

    static RedisCacheable stubCacheable(java.util.function.Consumer<TestRedisCacheable> config) {
        TestRedisCacheable s = new TestRedisCacheable();
        config.accept(s);
        return s;
    }

    static RedisCachePut stubPut(java.util.function.Consumer<TestRedisCachePut> config) {
        TestRedisCachePut p = new TestRedisCachePut();
        config.accept(p);
        return p;
    }

    static RedisCacheEvict stubEvict(java.util.function.Consumer<TestRedisCacheEvict> config) {
        TestRedisCacheEvict e = new TestRedisCacheEvict();
        config.accept(e);
        return e;
    }

    static class TestRedisCacheable implements RedisCacheable {
        String[] values = {};
        String[] cacheNames = {};
        String key = "";
        String keyGenerator = "";
        String cacheManager = "";
        String cacheResolver = "";
        String condition = "";
        String unless = "";
        boolean sync = false;
        long syncTimeout = 10L;
        long ttl = 60L;
        Class<?> type = Object.class;
        boolean cacheNullValues = false;
        boolean useBloomFilter = false;
        long expectedInsertions = 100_000L;
        double falseProbability = 0.01;
        boolean randomTtl = false;
        float variance = 0.2F;
        boolean enableEarlyExpiration = false;
        double earlyExpirationThreshold = 0.3;
        io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode earlyExpirationMode =
                io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode.SYNC;

        @Override public Class<? extends Annotation> annotationType() { return RedisCacheable.class; }
        @Override public String[] value() { return values; }
        @Override public String[] cacheNames() { return cacheNames; }
        @Override public String key() { return key; }
        @Override public String keyGenerator() { return keyGenerator; }
        @Override public String cacheManager() { return cacheManager; }
        @Override public String cacheResolver() { return cacheResolver; }
        @Override public String condition() { return condition; }
        @Override public String unless() { return unless; }
        @Override public boolean sync() { return sync; }
        @Override public long syncTimeout() { return syncTimeout; }
        @Override public long ttl() { return ttl; }
        @Override public Class<?> type() { return type; }
        @Override public boolean cacheNullValues() { return cacheNullValues; }
        @Override public boolean useBloomFilter() { return useBloomFilter; }
        @Override public long expectedInsertions() { return expectedInsertions; }
        @Override public double falseProbability() { return falseProbability; }
        @Override public boolean randomTtl() { return randomTtl; }
        @Override public float variance() { return variance; }
        @Override public boolean enableEarlyExpiration() { return enableEarlyExpiration; }
        @Override public double earlyExpirationThreshold() { return earlyExpirationThreshold; }
        @Override public io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode earlyExpirationMode() { return earlyExpirationMode; }
    }

    static class TestRedisCachePut implements RedisCachePut {
        String[] values = {};
        String[] cacheNames = {};
        String key = "";
        String keyGenerator = "";
        String cacheManager = "";
        String cacheResolver = "";
        String condition = "";
        String unless = "";
        long ttl = 60L;
        Class<?> type = Object.class;
        boolean cacheNullValues = false;
        boolean useBloomFilter = false;
        long expectedInsertions = 100_000L;
        double falseProbability = 0.01;
        boolean sync = false;
        long syncTimeout = 10L;
        boolean randomTtl = false;
        float variance = 0.2F;
        boolean enableEarlyExpiration = false;
        double earlyExpirationThreshold = 0.3;
        io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode earlyExpirationMode =
                io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode.SYNC;

        @Override public Class<? extends Annotation> annotationType() { return RedisCachePut.class; }
        @Override public String[] value() { return values; }
        @Override public String[] cacheNames() { return cacheNames; }
        @Override public String key() { return key; }
        @Override public String keyGenerator() { return keyGenerator; }
        @Override public String cacheManager() { return cacheManager; }
        @Override public String cacheResolver() { return cacheResolver; }
        @Override public String condition() { return condition; }
        @Override public String unless() { return unless; }
        @Override public long ttl() { return ttl; }
        @Override public Class<?> type() { return type; }
        @Override public boolean cacheNullValues() { return cacheNullValues; }
        @Override public boolean useBloomFilter() { return useBloomFilter; }
        @Override public long expectedInsertions() { return expectedInsertions; }
        @Override public double falseProbability() { return falseProbability; }
        @Override public boolean sync() { return sync; }
        @Override public long syncTimeout() { return syncTimeout; }
        @Override public boolean randomTtl() { return randomTtl; }
        @Override public float variance() { return variance; }
        @Override public boolean enableEarlyExpiration() { return enableEarlyExpiration; }
        @Override public double earlyExpirationThreshold() { return earlyExpirationThreshold; }
        @Override public io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode earlyExpirationMode() { return earlyExpirationMode; }
    }

    static class TestRedisCacheEvict implements RedisCacheEvict {
        String[] values = {};
        String[] cacheNames = {};
        String key = "";
        String keyGenerator = "";
        String cacheManager = "";
        String cacheResolver = "";
        String condition = "";
        String unless = "";
        boolean allEntries = false;
        boolean beforeInvocation = false;
        boolean sync = false;
        long syncTimeout = 10L;
        long ttl = 0L;
        boolean useBloomFilter = false;
        long expectedInsertions = 100_000L;
        double falseProbability = 0.01;
        boolean enableEarlyExpiration = false;
        double earlyExpirationThreshold = 0.3;
        io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode earlyExpirationMode =
                io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode.SYNC;

        @Override public Class<? extends Annotation> annotationType() { return RedisCacheEvict.class; }
        @Override public String[] value() { return values; }
        @Override public String[] cacheNames() { return cacheNames; }
        @Override public String key() { return key; }
        @Override public String keyGenerator() { return keyGenerator; }
        @Override public String cacheManager() { return cacheManager; }
        @Override public String cacheResolver() { return cacheResolver; }
        @Override public String condition() { return condition; }
        @Override public String unless() { return unless; }
        @Override public boolean allEntries() { return allEntries; }
        @Override public boolean beforeInvocation() { return beforeInvocation; }
        @Override public boolean sync() { return sync; }
        @Override public long syncTimeout() { return syncTimeout; }
        @Override public long ttl() { return ttl; }
        @Override public boolean useBloomFilter() { return useBloomFilter; }
        @Override public long expectedInsertions() { return expectedInsertions; }
        @Override public double falseProbability() { return falseProbability; }
        @Override public boolean enableEarlyExpiration() { return enableEarlyExpiration; }
        @Override public double earlyExpirationThreshold() { return earlyExpirationThreshold; }
        @Override public io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode earlyExpirationMode() { return earlyExpirationMode; }
    }
}
