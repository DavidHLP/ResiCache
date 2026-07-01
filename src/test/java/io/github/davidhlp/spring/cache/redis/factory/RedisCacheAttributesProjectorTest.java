package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;

import io.github.davidhlp.spring.cache.redis.operation.RedisCacheAttributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisCacheAttributesProjector} 单元测试。
 *
 * <p>聚焦 3 处默认值漂移的修复收敛行为：{@code syncTimeout} /
 * {@code expectedInsertions} / {@code falseProbability}。本类是 drift 修复的
 * <em>唯一</em>收敛点，单元覆盖必须 100%。
 */
@DisplayName("RedisCacheAttributesProjector Tests")
class RedisCacheAttributesProjectorTest {

    private final RedisCacheAttributesProjector projector = new RedisCacheAttributesProjector();

    @Nested
    @DisplayName("null 输入")
    class NullInputs {

        @Test
        @DisplayName("from(RedisCacheable) 为 null 返回 null")
        void from_redisCacheable_null_returnsNull() {
            assertThat(projector.from((RedisCacheable) null)).isNull();
        }

        @Test
        @DisplayName("from(RedisCachePut) 为 null 返回 null")
        void from_redisCachePut_null_returnsNull() {
            assertThat(projector.from((RedisCachePut) null)).isNull();
        }

        @Test
        @DisplayName("from(RedisCacheEvict) 为 null 返回 null")
        void from_redisCacheEvict_null_returnsNull() {
            assertThat(projector.from((RedisCacheEvict) null)).isNull();
        }
    }

    @Nested
    @DisplayName("3 处默认值漂移修复（核心）")
    class DriftFix {

        @Test
        @DisplayName("drift 已修复：三注解 @interface 默认值已对齐，投影器无差别通过")
        void defaultsAligned_acrossAllThreeAnnotations() {
            // @interface 默认值: Cacheable/Put/Evict 都是 syncTimeout=10, expectedInsertions=100000, falseProbability=0.01
            assertThat(projector.from(stubCacheable(s -> {})).getSyncTimeout()).isEqualTo(10L);
            assertThat(projector.from(stubPut(p -> {})).getSyncTimeout()).isEqualTo(10L);
            assertThat(projector.from(stubEvict(e -> {})).getSyncTimeout()).isEqualTo(10L);

            assertThat(projector.from(stubCacheable(s -> {})).getExpectedInsertions()).isEqualTo(100_000L);
            assertThat(projector.from(stubPut(p -> {})).getExpectedInsertions()).isEqualTo(100_000L);
            assertThat(projector.from(stubEvict(e -> {})).getExpectedInsertions()).isEqualTo(100_000L);

            assertThat(projector.from(stubCacheable(s -> {})).getFalseProbability()).isEqualTo(0.01);
            assertThat(projector.from(stubPut(p -> {})).getFalseProbability()).isEqualTo(0.01);
            assertThat(projector.from(stubEvict(e -> {})).getFalseProbability()).isEqualTo(0.01);
        }

        @Test
        @DisplayName("显式覆盖仍生效（投影器不修改用户设置）")
        void explicitOverrides_arePassThrough() {
            assertThat(projector.from(stubPut(p -> p.syncTimeout = 60L)).getSyncTimeout()).isEqualTo(60L);
            assertThat(projector.from(stubCacheable(s -> s.expectedInsertions = 500_000)).getExpectedInsertions())
                    .isEqualTo(500_000L);
            assertThat(projector.from(stubEvict(e -> e.falseProbability = 0.001)).getFalseProbability())
                    .isEqualTo(0.001);
        }
    }

    @Nested
    @DisplayName("cacheNames vs value 合并")
    class CacheNamesResolution {

        @Test
        @DisplayName("cacheNames 非空优先使用")
        void cacheNames_wins_over_value() {
            RedisCacheable ann = stubCacheable(s -> {
                s.cacheNames = new String[]{"primary"};
                s.values = new String[]{"fallback"};
            });
            assertThat(projector.from(ann).getCacheNames()).containsExactly("primary");
        }

        @Test
        @DisplayName("cacheNames 为空时回退到 value")
        void value_used_when_cacheNames_empty() {
            RedisCacheable ann = stubCacheable(s -> {
                s.cacheNames = new String[0];
                s.values = new String[]{"fromValue"};
            });
            assertThat(projector.from(ann).getCacheNames()).containsExactly("fromValue");
        }
    }

    @Nested
    @DisplayName("Evict-only 字段")
    class EvictOnlyFields {

        @Test
        @DisplayName("Cacheable/Put 投影不携带 allEntries / beforeInvocation")
        void cacheablePutDoNotCarryEvictFields() {
            RedisCacheable c = stubCacheable(s -> {});
            RedisCachePut p = stubPut(pp -> {});
            assertThat(projector.from(c).isAllEntries()).isFalse();
            assertThat(projector.from(c).isBeforeInvocation()).isFalse();
            assertThat(projector.from(p).isAllEntries()).isFalse();
            assertThat(projector.from(p).isBeforeInvocation()).isFalse();
        }

        @Test
        @DisplayName("Evict 投影正确传递 allEntries / beforeInvocation")
        void evictCarriesEvictFields() {
            RedisCacheEvict e = stubEvict(ee -> {
                ee.allEntries = true;
                ee.beforeInvocation = true;
            });
            assertThat(projector.from(e).isAllEntries()).isTrue();
            assertThat(projector.from(e).isBeforeInvocation()).isTrue();
        }

        @Test
        @DisplayName("Evict 没有的字段 (type/cacheNullValues/randomTtl/variance) 取合理默认")
        void evictMissingFieldsFallBackSensibly() {
            RedisCacheEvict e = stubEvict(ee -> {});
            RedisCacheAttributes a = projector.from(e);
            assertThat(a.getType()).isEqualTo(Object.class);
            assertThat(a.isCacheNullValues()).isFalse();
            assertThat(a.isRandomTtl()).isFalse();
        }
    }

    @Nested
    @DisplayName("静态工具方法")
    class StaticUtils {

        @Test
        @DisplayName("resolveCacheNames: 全部 null-safe")
        void resolveCacheNames_nullSafe() {
            assertThat(RedisCacheAttributesProjector.resolveCacheNames(null, null))
                    .isEmpty();
            assertThat(RedisCacheAttributesProjector.resolveCacheNames(new String[0], new String[]{"v"}))
                    .containsExactly("v");
            assertThat(RedisCacheAttributesProjector.resolveCacheNames(new String[]{"c"}, null))
                    .containsExactly("c");
        }
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
        int expectedInsertions = 100_000;
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
        @Override public int expectedInsertions() { return expectedInsertions; }
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
