package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TtlPolicy 单元测试 —— TTL 优先级(注解 / Duration 参数 / 兜底默认)与抖动,均不经 handler 链。
 *
 * <p>行为基线:每条用例断言的是 TtlPolicy 引入前后逐字保留的既有结果。
 */
@DisplayName("TtlPolicy Tests")
class TtlPolicyTest {

    /** Spring Data Redis 写路径传入的 cache 级 TTL(resi-cache.default-ttl 默认 30m)。 */
    private static final Duration CONFIGURED_DEFAULT = Duration.ofMinutes(30);

    private static CachePolicyView annotationPolicy(long ttlSeconds) {
        return annotationPolicy(ttlSeconds, false, 0.2F);
    }

    private static CachePolicyView annotationPolicy(
            long ttlSeconds, boolean randomTtl, float variance) {
        return new CachePolicyView(
                ttlSeconds, randomTtl, variance, false, false, 0, false, false, 0.3,
                EarlyExpirationMode.SYNC);
    }

    @Nested
    @DisplayName("precedence per input combination")
    class PrecedenceTests {

        @Test
        @DisplayName("attribute set (120s) wins over the 30m parameter")
        void annotationSet_winsOverParameter() {
            TtlPolicy.Resolution resolution = TtlPolicy.resolve(CONFIGURED_DEFAULT, annotationPolicy(120));

            assertThat(resolution.source()).isEqualTo(TtlPolicy.Source.ANNOTATION);
            assertThat(resolution.decision().shouldApplyTtl()).isTrue();
            assertThat(resolution.decision().finalTtl()).isEqualTo(120L);
        }

        @Test
        @DisplayName("attribute unset (annotation default 60s) wins over the 30m parameter")
        void annotationUnsetDefault_winsOverParameter() {
            TtlPolicy.Resolution resolution = TtlPolicy.resolve(CONFIGURED_DEFAULT, annotationPolicy(60));

            assertThat(resolution.source()).isEqualTo(TtlPolicy.Source.ANNOTATION);
            assertThat(resolution.decision().finalTtl()).isEqualTo(60L);
        }

        @Test
        @DisplayName("attribute unset without any parameter still resolves to 60s")
        void annotationUnsetDefault_withoutParameter_resolvesTo60s() {
            TtlPolicy.Resolution resolution = TtlPolicy.resolve(null, annotationPolicy(60));

            assertThat(resolution.source()).isEqualTo(TtlPolicy.Source.ANNOTATION);
            assertThat(resolution.decision().finalTtl()).isEqualTo(60L);
        }

        @Test
        @DisplayName("attribute explicitly 0 uses the Duration parameter")
        void annotationZero_usesParameter() {
            TtlPolicy.Resolution resolution = TtlPolicy.resolve(CONFIGURED_DEFAULT, annotationPolicy(0));

            assertThat(resolution.source()).isEqualTo(TtlPolicy.Source.PARAMETER);
            assertThat(resolution.decision().finalTtl()).isEqualTo(1800L);
        }

        @Test
        @DisplayName("attribute explicitly 0 without a parameter falls back to 60s")
        void annotationZero_withoutParameter_fallsBackTo60s() {
            TtlPolicy.Resolution resolution = TtlPolicy.resolve(null, annotationPolicy(0));

            assertThat(resolution.source()).isEqualTo(TtlPolicy.Source.PARAMETER);
            assertThat(resolution.decision().finalTtl()).isEqualTo(60L);
        }

        @Test
        @DisplayName("zero parameter means permanent")
        void zeroParameter_meansPermanent() {
            TtlPolicy.Resolution resolution = TtlPolicy.resolve(Duration.ZERO, annotationPolicy(0));

            assertThat(resolution.source()).isEqualTo(TtlPolicy.Source.NONE);
            assertThat(resolution.decision().shouldApplyTtl()).isFalse();
            assertThat(resolution.decision().finalTtl()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("negative parameter means permanent")
        void negativeParameter_meansPermanent() {
            TtlPolicy.Resolution resolution =
                    TtlPolicy.resolve(Duration.ofSeconds(-1), annotationPolicy(0));

            assertThat(resolution.source()).isEqualTo(TtlPolicy.Source.NONE);
            assertThat(resolution.decision().shouldApplyTtl()).isFalse();
        }

        @Test
        @DisplayName("no method-level policy uses the configured default only")
        void noAnnotation_usesConfiguredDefault() {
            TtlPolicy.Resolution resolution = TtlPolicy.resolve(CONFIGURED_DEFAULT, CachePolicyView.NONE);

            assertThat(resolution.source()).isEqualTo(TtlPolicy.Source.PARAMETER);
            assertThat(resolution.decision().finalTtl()).isEqualTo(1800L);
        }

        @Test
        @DisplayName("no method-level policy and no parameter falls back to 60s")
        void noAnnotation_withoutParameter_fallsBackTo60s() {
            TtlPolicy.Resolution resolution = TtlPolicy.resolve(null, CachePolicyView.NONE);

            assertThat(resolution.decision().finalTtl()).isEqualTo(60L);
        }

        @Test
        @DisplayName("randomTtl on the annotation path jitters only the annotation TTL")
        void randomTtl_doesNotJitterParameterPath() {
            TtlPolicy.Resolution annotation =
                    TtlPolicy.resolve(CONFIGURED_DEFAULT, annotationPolicy(120, true, 0.5F));
            TtlPolicy.Resolution parameter =
                    TtlPolicy.resolve(CONFIGURED_DEFAULT, annotationPolicy(0, true, 0.5F));

            assertThat(annotation.jitterRequested()).isTrue();
            assertThat(annotation.decision().finalTtl()).isBetween(60L, 240L);
            assertThat(parameter.source()).isEqualTo(TtlPolicy.Source.PARAMETER);
            assertThat(parameter.jitterRequested()).isFalse();
            assertThat(parameter.decision().finalTtl()).isEqualTo(1800L);
        }
    }

    @Nested
    @DisplayName("annotation attribute default (unset) link")
    class AnnotationDefaultLinkTests {

        @RedisCacheable(cacheNames = "ttl-policy-sample")
        private String annotatedWithDefaults(String id) {
            return id;
        }

        @Test
        @DisplayName("unset ttl attribute projects to 60s and wins over the 30m configured default")
        void unsetTtlAttribute_resolvesTo60s() throws Exception {
            Method method = AnnotationDefaultLinkTests.class
                    .getDeclaredMethod("annotatedWithDefaults", String.class);
            RedisCacheable annotation = method.getAnnotation(RedisCacheable.class);
            RedisCacheAttributes attributes = new RedisCacheAttributesProjector().from(annotation);
            RedisCacheableOperation operation =
                    RedisCacheableOperation.fromAttributes(method, annotation.key(), attributes);

            CachePolicyView policy = new CacheInput(
                    CacheOperation.PUT, "ttl-policy-sample", "k", "k", null, null, null, operation)
                    .policy();
            TtlPolicy.Resolution resolution = TtlPolicy.resolve(CONFIGURED_DEFAULT, policy);

            assertThat(policy.ttl()).isEqualTo(60L);
            assertThat(resolution.source()).isEqualTo(TtlPolicy.Source.ANNOTATION);
            assertThat(resolution.decision().finalTtl()).isEqualTo(60L);
        }
    }

    @Nested
    @DisplayName("calculateFinalTtl")
    class JitterTests {

        @Test
        void nullZeroAndNegativeBaseTtl_mapToPermanentSentinel() {
            assertThat(TtlPolicy.calculateFinalTtl(null, false, 0.2f)).isEqualTo(-1L);
            assertThat(TtlPolicy.calculateFinalTtl(0L, false, 0.2f)).isEqualTo(-1L);
            assertThat(TtlPolicy.calculateFinalTtl(-1L, false, 0.2f)).isEqualTo(-1L);
        }

        @Test
        void nonPositiveVariance_doesNotJitterBaseTtl() {
            assertThat(TtlPolicy.calculateFinalTtl(120L, true, 0.0f)).isEqualTo(120L);
            assertThat(TtlPolicy.calculateFinalTtl(120L, true, -0.1f)).isEqualTo(120L);
        }

        @Test
        void varianceAboveOne_isClampedToSafeOutputBounds() {
            for (int i = 0; i < 128; i++) {
                assertThat(TtlPolicy.calculateFinalTtl(120L, true, 2.0f))
                        .isBetween(1L, 240L);
            }
        }

        @Test
        void jitteredBaseTtl_staysWithinBoundedVariance() {
            for (int i = 0; i < 128; i++) {
                assertThat(TtlPolicy.calculateFinalTtl(120L, true, 0.1f))
                        .isBetween(108L, 132L);
            }
        }
    }
}
