package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code XxxOperation.fromAttributes(method, key, attributes)} seam 的契约测试。
 *
 * <p>本测试覆盖三个 Operation 的静态 {@code fromAttributes} 方法,验证:
 * <ul>
 *   <li>字段映射完整(21 字段全量 / Evict 17 字段子集)</li>
 *   <li>空入参 / 默认值路径</li>
 *   <li>跨 Operation 字段集差异(Cacheable/Put 全集 vs Evict 子集)</li>
 * </ul>
 *
 * <p>{@code expectedInsertions} 为 long 类型,直传无窄化。
 */
@DisplayName("Operation.fromAttributes seam")
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
        @DisplayName("expectedInsertions 直传无窄化(S1 后 long→long)")
        void fromAttributes_passesExpectedInsertionsThrough() throws Exception {
            // Cacheable Builder 槽位是 long,直传无窄化。
            RedisCacheAttributes a = emptyExcept(RedisCacheAttributes.builder()
                    .cacheNames(new String[]{"c"})
                    .expectedInsertions(Long.MAX_VALUE));

            RedisCacheableOperation op = RedisCacheableOperation.fromAttributes(testMethod(), "k", a);

            assertThat(op.getExpectedInsertions()).isEqualTo(Long.MAX_VALUE);
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
}
