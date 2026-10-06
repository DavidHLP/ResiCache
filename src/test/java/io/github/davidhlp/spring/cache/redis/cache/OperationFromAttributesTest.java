package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Regression coverage for active read/write policy mapping from the shared projection. */
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
        @DisplayName("读策略字段正确映射")
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
            assertThat(op.isRandomTtl()).isTrue();
            assertThat(op.getVariance()).isEqualTo(0.3F);
            assertThat(op.isEnableEarlyExpiration()).isTrue();
            assertThat(op.getEarlyExpirationThreshold()).isEqualTo(0.4);
            assertThat(op.getEarlyExpirationMode()).isEqualTo(EarlyExpirationMode.ASYNC);
            assertThat(op.isSync()).isTrue();
            assertThat(op.getSyncTimeout()).isEqualTo(30L);
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
        @DisplayName("Put 字段集与 Cacheable 相同,同步策略正确映射")
        void fromAttributes_putIsLongType() throws Exception {
            RedisCacheAttributes a = emptyExcept(RedisCacheAttributes.builder()
                    .cacheNames(new String[]{"c"})
                    .sync(true));

            RedisCachePutOperation op = RedisCachePutOperation.fromAttributes(testMethod(), "k", a);

            assertThat(op.getName()).isEqualTo("sample");
            assertThat(op.getKey()).isEqualTo("k");
            assertThat(op.isSync()).isTrue();
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

}
