package io.github.davidhlp.spring.cache.redis.operation;

import io.github.davidhlp.spring.cache.redis.factory.RedisCacheAttributesProjector;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code XxxOperation.fromAttributes(method, key, attributes)} seam (ADR-0017) 的契约测试。
 *
 * <p>本测试覆盖三个 Operation 的静态 {@code fromAttributes} 方法,验证:
 * <ul>
 *   <li>字段映射完整(21 字段全量 / Evict 17 字段子集)</li>
 *   <li>边界裁剪正确(Cacheable 的 long→int 窄化)</li>
 *   <li>空入参 / 默认值路径</li>
 *   <li>跨 Operation 字段集差异(Cacheable/Put 全集 vs Evict 子集)</li>
 * </ul>
 *
 * <p>本测试是 ADR-0017 Factory 1-liner 委派的<strong>唯一</strong>契约钉子 —
 * 三个具体 factory 不再持有 Builder 填充逻辑,行为由本测试+Operation 类静态方法
 * 共同保证。
 */
@DisplayName("Operation.fromAttributes seam (ADR-0017)")
class OperationFromAttributesTest {

    private static Method testMethod() throws NoSuchMethodException {
        return Sample.class.getMethod("sample", String.class);
    }

    /**
     * 把 String 字段填成空串(避免 Spring {@code CacheOperation.Builder.setX(...)} 的
     * {@code Assert.notNull} 抛 IAE)。type 给 {@link Object} 默认避免 getType()=null。
     * 其余数值字段<strong>不</strong>在这里给默认,留给 Operation Builder 自身的
     * {@code @Builder.Default} 生效——这样测试传入的字段值不会被 helper 覆盖。
     */
    private static RedisCacheAttributes emptyExcept(RedisCacheAttributes.RedisCacheAttributesBuilder b) {
        return b.key("").keyGenerator("").cacheManager("").cacheResolver("")
                .condition("").unless("")
                .type(Object.class)
                .build();
    }

    static class Sample {
        public Object sample(String arg) {
            return arg;
        }
    }

    // -----------------------------------------------------------------
    // RedisCacheableOperation.fromAttributes
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("RedisCacheableOperation.fromAttributes")
    class CacheableFromAttributes {

        @Test
        @DisplayName("全部 21 字段正确映射")
        void fromAttributes_allFieldsPropagate() throws Exception {
            RedisCacheAttributes a = RedisCacheAttributes.builder()
                    .cacheNames(new String[]{"c1"})
                    .key("k")
                    .keyGenerator("kg")
                    .cacheManager("cm")
                    .cacheResolver("cr")
                    .condition("#a!=null")
                    .unless("#r==null")
                    .ttl(120L)
                    .type(String.class)
                    .cacheNullValues(true)
                    .useBloomFilter(true)
                    .expectedInsertions(5000L)
                    .falseProbability(0.005)
                    .randomTtl(true)
                    .variance(0.3F)
                    .enableEarlyExpiration(true)
                    .earlyExpirationThreshold(0.4)
                    .earlyExpirationMode(EarlyExpirationMode.ASYNC)
                    .sync(true)
                    .syncTimeout(30L)
                    .allEntries(false)
                    .beforeInvocation(false)
                    .build();

            RedisCacheableOperation op = RedisCacheableOperation.fromAttributes(testMethod(), "k", a);

            assertThat(op.getName()).isEqualTo("sample");
            assertThat(op.getKey()).isEqualTo("k");
            assertThat(op.getCacheNames()).containsExactly("c1");
            assertThat(op.getKeyGenerator()).isEqualTo("kg");
            assertThat(op.getCacheManager()).isEqualTo("cm");
            assertThat(op.getCacheResolver()).isEqualTo("cr");
            assertThat(op.getCondition()).isEqualTo("#a!=null");
            assertThat(op.getUnless()).isEqualTo("#r==null");
            assertThat(op.getTtl()).isEqualTo(120L);
            assertThat(op.getType()).isEqualTo(String.class);
            assertThat(op.isCacheNullValues()).isTrue();
            assertThat(op.isUseBloomFilter()).isTrue();
            assertThat(op.getExpectedInsertions()).isEqualTo(5000);
            assertThat(op.getFalseProbability()).isEqualTo(0.005);
            assertThat(op.isRandomTtl()).isTrue();
            assertThat(op.getVariance()).isEqualTo(0.3F);
            assertThat(op.isEnableEarlyExpiration()).isTrue();
            assertThat(op.getEarlyExpirationThreshold()).isEqualTo(0.4);
            assertThat(op.getEarlyExpirationMode()).isEqualTo(EarlyExpirationMode.ASYNC);
            assertThat(op.isSync()).isTrue();
            assertThat(op.getSyncTimeout()).isEqualTo(30L);
        }

        @Test
        @DisplayName("expectedInsertions 超 Integer.MAX_VALUE 裁剪到 int 边界")
        void fromAttributes_clampsExpectedInsertionsToInt() throws Exception {
            RedisCacheAttributes a = emptyExcept(RedisCacheAttributes.builder()
                    .cacheNames(new String[]{"c"})
                    .expectedInsertions(Long.MAX_VALUE));

            RedisCacheableOperation op = RedisCacheableOperation.fromAttributes(testMethod(), "k", a);

            assertThat(op.getExpectedInsertions()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("expectedInsertions 负值裁剪到 0")
        void fromAttributes_clampsNegativeExpectedInsertionsToZero() throws Exception {
            RedisCacheAttributes a = emptyExcept(RedisCacheAttributes.builder()
                    .cacheNames(new String[]{"c"})
                    .expectedInsertions(-100L));

            RedisCacheableOperation op = RedisCacheableOperation.fromAttributes(testMethod(), "k", a);

            assertThat(op.getExpectedInsertions()).isEqualTo(0);
        }

        @Test
        @DisplayName("@Builder 默认值不传 attributes 也能构造")
        void fromAttributes_defaultsAreStable() throws Exception {
            // 与 Spring 注解默认对齐:String 字段填 ""(非 null),否则 setKeyGenerator 会抛 IAE
            RedisCacheableOperation op = RedisCacheableOperation.fromAttributes(
                    testMethod(), "k", RedisCacheAttributes.builder()
                            .cacheNames(new String[]{})
                            .key("").keyGenerator("").cacheManager("").cacheResolver("")
                            .condition("").unless("")
                            .type(Object.class)
                            .build());

            // Builder 内的 @Builder.Default 应生效(若 fromAttributes 显式 set 字段,
            // 则 builder 的 default 会被覆盖——这里只断言 type = Object.class,因 type 显式给了)
            assertThat(op.getType()).isEqualTo(Object.class);
            // 其他字段(@Builder.Default 在 Cacheable Builder 内)需要完整默认值场景下断言
            // ——本测试专注于 fromAttributes 不抛 IAE + type 默认值正确传递
        }
    }

    // -----------------------------------------------------------------
    // RedisCachePutOperation.fromAttributes
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("RedisCachePutOperation.fromAttributes")
    class PutFromAttributes {

        @Test
        @DisplayName("Put 字段集与 Cacheable 相同,直接 long→long 透传 expectedInsertions")
        void fromAttributes_putIsLongType() throws Exception {
            RedisCacheAttributes a = emptyExcept(RedisCacheAttributes.builder()
                    .cacheNames(new String[]{"c"})
                    .expectedInsertions(50_000L)
                    .sync(true));

            RedisCachePutOperation op = RedisCachePutOperation.fromAttributes(testMethod(), "k", a);

            assertThat(op.getName()).isEqualTo("sample");
            assertThat(op.getKey()).isEqualTo("k");
            assertThat(op.isSync()).isTrue();
            // Put 的 expectedInsertions 是 long,无窄化
            assertThat(op.getExpectedInsertions()).isEqualTo(50_000L);
        }

        @Test
        @DisplayName("Put 透传 cacheNullValues / randomTtl / variance")
        void fromAttributes_putExtras() throws Exception {
            RedisCacheAttributes a = emptyExcept(RedisCacheAttributes.builder()
                    .cacheNames(new String[]{"c"})
                    .cacheNullValues(true)
                    .randomTtl(true)
                    .variance(0.4F));

            RedisCachePutOperation op = RedisCachePutOperation.fromAttributes(testMethod(), "k", a);

            assertThat(op.isCacheNullValues()).isTrue();
            assertThat(op.isRandomTtl()).isTrue();
            assertThat(op.getVariance()).isEqualTo(0.4F);
        }
    }

    // -----------------------------------------------------------------
    // RedisCacheEvictOperation.fromAttributes
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("RedisCacheEvictOperation.fromAttributes")
    class EvictFromAttributes {

        @Test
        @DisplayName("Evict 缺失字段(unless/type/cacheNullValues/randomTtl/variance)不映射")
        void fromAttributes_dropsMissingEvictFields() throws Exception {
            // String 字段填 ""(非 null),与 Spring 注解默认对齐
            RedisCacheAttributes a = RedisCacheAttributes.builder()
                    .cacheNames(new String[]{"c"})
                    .key("").keyGenerator("").cacheManager("").cacheResolver("")
                    .condition("").unless("#r==null")
                    .type(String.class)
                    .cacheNullValues(true)
                    .randomTtl(true)
                    .variance(0.5F)
                    .allEntries(true)
                    .beforeInvocation(true)
                    .build();

            RedisCacheEvictOperation op = RedisCacheEvictOperation.fromAttributes(testMethod(), "k", a);

            assertThat(op.getName()).isEqualTo("sample");
            assertThat(op.isCacheWide()).isTrue();
            assertThat(op.isBeforeInvocation()).isTrue();
            // Evict 无 unless / type / cacheNullValues / randomTtl / variance 槽位
            // ——这些字段在 Evict 注解上本身就无对应语义
        }

        @Test
        @DisplayName("Evict 共享字段(布隆/早过期/同步)透传")
        void fromAttributes_evictSharedFields() throws Exception {
            RedisCacheAttributes a = emptyExcept(RedisCacheAttributes.builder()
                    .cacheNames(new String[]{"c"})
                    .useBloomFilter(true)
                    .expectedInsertions(50_000L)
                    .falseProbability(0.01)
                    .enableEarlyExpiration(true)
                    .earlyExpirationThreshold(0.5)
                    .earlyExpirationMode(EarlyExpirationMode.ASYNC)
                    .sync(true)
                    .syncTimeout(20L)
                    .ttl(60L));

            RedisCacheEvictOperation op = RedisCacheEvictOperation.fromAttributes(testMethod(), "k", a);

            assertThat(op.isUseBloomFilter()).isTrue();
            assertThat(op.getExpectedInsertions()).isEqualTo(50_000L);
            assertThat(op.getFalseProbability()).isEqualTo(0.01);
            assertThat(op.isEnableEarlyExpiration()).isTrue();
            assertThat(op.getEarlyExpirationThreshold()).isEqualTo(0.5);
            assertThat(op.getEarlyExpirationMode()).isEqualTo(EarlyExpirationMode.ASYNC);
            assertThat(op.isSync()).isTrue();
            assertThat(op.getSyncTimeout()).isEqualTo(20L);
            assertThat(op.getTtl()).isEqualTo(60L);
        }
    }

    // -----------------------------------------------------------------
    // Factory 1-liner 委派契约 — 验证 3 个 factory.materialize 完全等价
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Factory 1-liner 委派契约 (factory.materialize ≡ Operation.fromAttributes)")
    class FactoryDelegateContract {

        private final RedisCacheAttributesProjector projector = new RedisCacheAttributesProjector();

        @Test
        @DisplayName("CacheableOperationFactory.materialize === RedisCacheableOperation.fromAttributes")
        void cacheableFactoryDelegatesToFromAttributes() throws Exception {
            io.github.davidhlp.spring.cache.redis.factory.CacheableOperationFactory factory =
                    new io.github.davidhlp.spring.cache.redis.factory.CacheableOperationFactory(projector);
            Method m = testMethod();

            // 直接用 factory.create
            io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable annotation =
                    makeCacheable(new String[]{"c"}, 60L, true);
            RedisCacheableOperation viaFactory = factory.create(m, annotation, "k");

            // 直接用 fromAttributes
            RedisCacheAttributes a = projector.from(annotation);
            RedisCacheableOperation viaFromAttributes = RedisCacheableOperation.fromAttributes(m, "k", a);

            // 关键字段必须一致
            assertThat(viaFactory.getName()).isEqualTo(viaFromAttributes.getName());
            assertThat(viaFactory.getKey()).isEqualTo(viaFromAttributes.getKey());
            assertThat(viaFactory.getTtl()).isEqualTo(viaFromAttributes.getTtl());
            assertThat(viaFactory.getType()).isEqualTo(viaFromAttributes.getType());
            assertThat(viaFactory.isEnableEarlyExpiration()).isEqualTo(viaFromAttributes.isEnableEarlyExpiration());
        }

        @Test
        @DisplayName("EvictOperationFactory.materialize === RedisCacheEvictOperation.fromAttributes")
        void evictFactoryDelegatesToFromAttributes() throws Exception {
            io.github.davidhlp.spring.cache.redis.factory.EvictOperationFactory factory =
                    new io.github.davidhlp.spring.cache.redis.factory.EvictOperationFactory(projector);
            Method m = testMethod();

            io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict annotation =
                    makeEvict(new String[]{"c"}, true, true);
            RedisCacheEvictOperation viaFactory = factory.create(m, annotation, "k");

            RedisCacheAttributes a = projector.from(annotation);
            RedisCacheEvictOperation viaFromAttributes = RedisCacheEvictOperation.fromAttributes(m, "k", a);

            assertThat(viaFactory.isCacheWide()).isEqualTo(viaFromAttributes.isCacheWide());
            assertThat(viaFactory.isBeforeInvocation()).isEqualTo(viaFromAttributes.isBeforeInvocation());
            assertThat(viaFactory.isSync()).isEqualTo(viaFromAttributes.isSync());
        }

        @Test
        @DisplayName("CachePutOperationFactory.materialize === RedisCachePutOperation.fromAttributes")
        void putFactoryDelegatesToFromAttributes() throws Exception {
            io.github.davidhlp.spring.cache.redis.factory.CachePutOperationFactory factory =
                    new io.github.davidhlp.spring.cache.redis.factory.CachePutOperationFactory(projector);
            Method m = testMethod();

            io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut annotation =
                    makePut(new String[]{"c"}, 60L);
            RedisCachePutOperation viaFactory = factory.create(m, annotation, "k");

            RedisCacheAttributes a = projector.from(annotation);
            RedisCachePutOperation viaFromAttributes = RedisCachePutOperation.fromAttributes(m, "k", a);

            assertThat(viaFactory.getTtl()).isEqualTo(viaFromAttributes.getTtl());
            assertThat(viaFactory.isSync()).isEqualTo(viaFromAttributes.isSync());
        }

        // 简易 annotation stub 工厂 — 复用现有测试基础设施
        private io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable makeCacheable(
                String[] cacheNames, long ttl, boolean enableEarly) {
            return new io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable() {
                @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                    return io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable.class;
                }
                @Override public String[] cacheNames() { return cacheNames; }
                @Override public String[] value() { return new String[0]; }
                @Override public String key() { return ""; }
                @Override public String keyGenerator() { return ""; }
                @Override public String cacheManager() { return ""; }
                @Override public String cacheResolver() { return ""; }
                @Override public String condition() { return ""; }
                @Override public String unless() { return ""; }
                @Override public long ttl() { return ttl; }
                @Override public Class<?> type() { return Object.class; }
                @Override public boolean cacheNullValues() { return false; }
                @Override public boolean useBloomFilter() { return false; }
                @Override public int expectedInsertions() { return 100000; }
                @Override public double falseProbability() { return 0.01; }
                @Override public boolean randomTtl() { return false; }
                @Override public float variance() { return 0.2F; }
                @Override public boolean enableEarlyExpiration() { return enableEarly; }
                @Override public double earlyExpirationThreshold() { return 0.3; }
                @Override public EarlyExpirationMode earlyExpirationMode() { return EarlyExpirationMode.SYNC; }
                @Override public boolean sync() { return false; }
                @Override public long syncTimeout() { return 10L; }
            };
        }

        private io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut makePut(
                String[] cacheNames, long ttl) {
            return new io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut() {
                @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                    return io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut.class;
                }
                @Override public String[] cacheNames() { return cacheNames; }
                @Override public String[] value() { return new String[0]; }
                @Override public String key() { return ""; }
                @Override public String keyGenerator() { return ""; }
                @Override public String cacheManager() { return ""; }
                @Override public String cacheResolver() { return ""; }
                @Override public String condition() { return ""; }
                @Override public String unless() { return ""; }
                @Override public long ttl() { return ttl; }
                @Override public Class<?> type() { return Object.class; }
                @Override public boolean cacheNullValues() { return false; }
                @Override public boolean useBloomFilter() { return false; }
                @Override public long expectedInsertions() { return 100000L; }
                @Override public double falseProbability() { return 0.01; }
                @Override public boolean randomTtl() { return false; }
                @Override public float variance() { return 0.2F; }
                @Override public boolean enableEarlyExpiration() { return false; }
                @Override public double earlyExpirationThreshold() { return 0.3; }
                @Override public EarlyExpirationMode earlyExpirationMode() { return EarlyExpirationMode.SYNC; }
                @Override public boolean sync() { return false; }
                @Override public long syncTimeout() { return 10L; }
            };
        }

        private io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict makeEvict(
                String[] cacheNames, boolean allEntries, boolean beforeInvocation) {
            return new io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict() {
                @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                    return io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict.class;
                }
                @Override public String[] cacheNames() { return cacheNames; }
                @Override public String[] value() { return new String[0]; }
                @Override public String key() { return ""; }
                @Override public String keyGenerator() { return ""; }
                @Override public String cacheManager() { return ""; }
                @Override public String cacheResolver() { return ""; }
                @Override public String condition() { return ""; }
                @Override public String unless() { return ""; }
                @Override public long ttl() { return 0L; }
                @Override public boolean useBloomFilter() { return false; }
                @Override public long expectedInsertions() { return 100000L; }
                @Override public double falseProbability() { return 0.01; }
                @Override public boolean enableEarlyExpiration() { return false; }
                @Override public double earlyExpirationThreshold() { return 0.3; }
                @Override public EarlyExpirationMode earlyExpirationMode() { return EarlyExpirationMode.SYNC; }
                @Override public boolean sync() { return false; }
                @Override public long syncTimeout() { return 10L; }
                @Override public boolean allEntries() { return allEntries; }
                @Override public boolean beforeInvocation() { return beforeInvocation; }
            };
        }
    }
}
