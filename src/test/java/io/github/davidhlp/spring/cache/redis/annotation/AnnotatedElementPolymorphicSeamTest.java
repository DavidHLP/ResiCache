package io.github.davidhlp.spring.cache.redis.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnnotationTargets} 反射多态 seam contract tests。
 *
 * <p>钉住 2 个 helper 在 Method / Class 双向的行为等价。零 Spring 容器依赖,
 * 纯反射 + Lombok + Spring Core API。
 */
@DisplayName("AnnotationTargets Polymorphic Seam Tests")
class AnnotatedElementPolymorphicSeamTest {

    // -------- fixtures --------

    @RedisCacheable("cacheable-m")
    @RedisCaching(redisCacheable = @RedisCacheable("caching-m"))
    static class Fixture {
        @RedisCacheable("cacheable-meth")
        public void cacheableMethod() { }

        public void plainMethod() { }
    }

    private static final Class<?> FIXTURE_CLASS = Fixture.class;
    private static final Method CACHEABLE_METHOD;
    private static final Method PLAIN_METHOD;
    static {
        try {
            CACHEABLE_METHOD = Fixture.class.getMethod("cacheableMethod");
            PLAIN_METHOD = Fixture.class.getMethod("plainMethod");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    // -------- findMerged contract --------

    @Nested
    @DisplayName("findMerged(Object, Class) — polymorphic on AnnotatedElement")
    class FindMergedContract {

        @Test
        @DisplayName("Method 上能找到 @RedisCacheable 注解(merged)")
        void methodTargetReturnsAnnotation() {
            RedisCacheable ann = AnnotationTargets.findMerged(CACHEABLE_METHOD, RedisCacheable.class);
            assertThat(ann).isNotNull();
            assertThat(ann.value()).containsExactly("cacheable-meth");
        }

        @Test
        @DisplayName("Class 上能找到 @RedisCacheable 注解(merged)")
        void classTargetReturnsAnnotation() {
            RedisCacheable ann = AnnotationTargets.findMerged(FIXTURE_CLASS, RedisCacheable.class);
            assertThat(ann).isNotNull();
            assertThat(ann.value()).containsExactly("cacheable-m");
        }

        @Test
        @DisplayName("Class 上能找到 @RedisCaching 复合注解")
        void classTargetReturnsCachingAnnotation() {
            RedisCaching ann = AnnotationTargets.findMerged(FIXTURE_CLASS, RedisCaching.class);
            assertThat(ann).isNotNull();
            assertThat(ann.redisCacheable()).hasSize(1);
        }

        @Test
        @DisplayName("Method 上不存在 @RedisCacheEvict 注解 → 返回 null")
        void methodTargetReturnsNullForAbsentAnnotation() {
            assertThat(AnnotationTargets.findMerged(CACHEABLE_METHOD, RedisCacheEvict.class)).isNull();
        }

        @Test
        @DisplayName("Class 上不存在 @RedisCachePut 注解 → 返回 null")
        void classTargetReturnsNullForAbsentAnnotation() {
            assertThat(AnnotationTargets.findMerged(FIXTURE_CLASS, RedisCachePut.class)).isNull();
        }

        @Test
        @DisplayName("非 AnnotatedElement 的 target(字符串)→ 返回 null,不抛 CCE")
        void nonAnnotatedElementTargetReturnsNull() {
            // 防御性:运行时调用方若意外传入非 Method/Class,不应 ClassCastException
            Object weird = "not an annotated element";
            assertThat(AnnotationTargets.findMerged(weird, RedisCacheable.class)).isNull();
        }
    }

    // -------- extractTargetName contract --------

    @Nested
    @DisplayName("extractTargetName(Object) — polymorphic on Method/Class")
    class ExtractTargetNameContract {

        @Test
        @DisplayName("Method 路径返回 method.getName()(短名,不依赖 toString)")
        void methodTargetReturnsMethodName() {
            // CACHEABLE_METHOD 名为 "cacheableMethod"
            assertThat(AnnotationTargets.extractTargetName(CACHEABLE_METHOD))
                    .isEqualTo("cacheableMethod");
        }

        @Test
        @DisplayName("Class 路径返回 class.getName()(全限定名)")
        void classTargetReturnsClassName() {
            assertThat(AnnotationTargets.extractTargetName(FIXTURE_CLASS))
                    .isEqualTo(FIXTURE_CLASS.getName());
        }

        @Test
        @DisplayName("非 Method/Class 走 toString() fallback(与原 else 分支行为兼容)")
        void nonMethodOrClassTargetFallsBackToToString() {
            Object weird = "any object";
            assertThat(AnnotationTargets.extractTargetName(weird)).isEqualTo("any object");
        }
    }
}
