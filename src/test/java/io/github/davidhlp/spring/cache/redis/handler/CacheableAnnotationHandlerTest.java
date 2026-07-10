package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheAttributesProjector;
import io.github.davidhlp.spring.cache.redis.operation.OperationKind;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CacheableAnnotationHandler —— ADR-0059 收敛后形态。
 *
 * <p>register 调用已从 {@code redisCacheRegister::registerCacheableOperation} 方法引用
 * 改为 {@link AbstractAnnotationHandler#registerActionFor(OperationKind)} 工厂 lambda,
 * 测试断言改为 {@code redisCacheRegister.register(..., OperationKind.CACHEABLE)}。
 *
 * <p><b>ADR-0060 测试扩展</b>:本类新增 {@link SelectCacheableSourceTests} nested class,
 * 覆盖从 doHandle 抽出的 selectCacheableSource seam —— 验证"ResiCache 注解优先于 Spring
 * 注解"的源选择规则,无需 mock factory/register 即可断言 source 类型,显著提升单测 locality。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CacheableAnnotationHandler Tests")
class CacheableAnnotationHandlerTest {

    @Mock
    private RedisCacheRegister redisCacheRegister;

    @Mock
    private KeyGenerator keyGenerator;

    private final RedisCacheAttributesProjector projector = new RedisCacheAttributesProjector();

    @Mock
    private io.github.davidhlp.spring.cache.redis.operation.SpringCacheableAdapterFactory springCacheableAdapterFactory;

    private CacheableAnnotationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CacheableAnnotationHandler(
                redisCacheRegister, keyGenerator, projector, springCacheableAdapterFactory);
    }

    private Method getMethod(String name) throws NoSuchMethodException {
        return TestClass.class.getMethod(name);
    }

    // Test class with annotated methods
    private static class TestClass {
        @RedisCacheable(cacheNames = "testCache", ttl = 60)
        public void cachedMethod() {
        }

        @RedisCacheable(cacheNames = "anotherCache", ttl = 120)
        public void anotherCachedMethod() {
        }

        @Cacheable(cacheNames = "springCache")
        public void springCachedMethod() {
        }

        @RedisCacheable(cacheNames = "resiCache")
        @Cacheable(cacheNames = "springCache")
        public void bothAnnotatedMethod() {
        }

        public void noAnnotation() {
        }
    }

    @Nested
    @DisplayName("canHandle() Tests")
    class CanHandleTests {

        @Test
        @DisplayName("canHandle returns true when method has @RedisCacheable annotation")
        void canHandle_withRedisCacheable_returnsTrue() throws NoSuchMethodException {
            Method method = getMethod("cachedMethod");

            boolean result = handler.canHandle(method);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("canHandle returns true when method has Spring @Cacheable annotation")
        void canHandle_withSpringCacheable_returnsTrue() throws NoSuchMethodException {
            Method method = getMethod("springCachedMethod");

            boolean result = handler.canHandle(method);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("canHandle returns false when method has no annotation")
        void canHandle_withoutAnnotation_returnsFalse() throws NoSuchMethodException {
            Method method = getMethod("noAnnotation");

            boolean result = handler.canHandle(method);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("doHandle() Tests")
    class DoHandleTests {

        @Test
        @DisplayName("doHandle registers cacheable operation with correct parameters")
        void doHandle_withValidAnnotation_registersOperation() throws Exception {
            Method method = getMethod("cachedMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];
            String generatedKey = "generated-key";

            when(keyGenerator.generate(target, method, args)).thenReturn(generatedKey);

            handler.doHandle(method, target, args);

            // ADR-0065:operation 由真实 projector + fromAttributes 生成;验证 register 收到
            // RedisCacheableOperation + CACHEABLE kind(不再用 eq(cannedOp))
            verify(redisCacheRegister).register(
                    any(Method.class), any(Class.class),
                    any(RedisCacheableOperation.class), eq(OperationKind.CACHEABLE));
        }

        @Test
        @DisplayName("doHandle generates correct key using KeyGenerator")
        void doHandle_callsKeyGenerator() throws Exception {
            Method method = getMethod("cachedMethod");
            Object target = new TestClass();
            Object[] args = new Object[]{"arg1", "arg2"};

            when(keyGenerator.generate(target, method, args)).thenReturn("test-key");

            handler.doHandle(method, target, args);

            verify(keyGenerator).generate(target, method, args);
        }

        @Test
        @DisplayName("ADR-0060:doHandle returns empty list when no annotation is present")
        void doHandle_withoutAnnotation_returnsEmpty() throws Exception {
            Method method = getMethod("noAnnotation");
            Object target = new TestClass();
            Object[] args = new Object[0];

            assertThat(handler.doHandle(method, target, args)).isEmpty();
            verify(redisCacheRegister, never())
                    .register(any(), any(), any(CacheOperation.class), any(OperationKind.class));
        }
    }

    @Nested
    @DisplayName("ADR-0060: selectCacheableSource() Tests")
    class SelectCacheableSourceTests {

        @Test
        @DisplayName("selectCacheableSource returns @RedisCacheable when ResiCache annotation is present")
        void selectCacheableSource_withRedisCacheable_returnsResiCache() throws Exception {
            Method method = getMethod("cachedMethod");

            Optional<java.lang.annotation.Annotation> annotation = handler.selectCacheableSource(method);

            assertThat(annotation).isPresent();
            assertThat(annotation.get()).isInstanceOf(RedisCacheable.class);
        }

        @Test
        @DisplayName("selectCacheableSource returns Spring @Cacheable when only Spring annotation is present")
        void selectCacheableSource_withOnlySpringCacheable_returnsSpring() throws Exception {
            Method method = getMethod("springCachedMethod");

            Optional<java.lang.annotation.Annotation> annotation = handler.selectCacheableSource(method);

            assertThat(annotation).isPresent();
            assertThat(annotation.get()).isInstanceOf(Cacheable.class);
        }

        @Test
        @DisplayName("selectCacheableSource returns empty when no annotation is present")
        void selectCacheableSource_withoutAnnotation_returnsEmpty() throws Exception {
            Method method = getMethod("noAnnotation");

            Optional<java.lang.annotation.Annotation> annotation = handler.selectCacheableSource(method);

            assertThat(annotation).isEmpty();
        }

        @Test
        @DisplayName("ADR-0060: ResiCache 注解优先于 Spring 注解(同方法共存时 ResiCache 胜出)")
        void selectCacheableSource_withBothAnnotations_prefersResiCache() throws Exception {
            // bothAnnotatedMethod 同时标注 @RedisCacheable + Spring @Cacheable;
            // ADR-0060 源选择规则:ResiCache 优先(Spring 路径被忽略)。
            Method method = getMethod("bothAnnotatedMethod");

            Optional<java.lang.annotation.Annotation> annotation = handler.selectCacheableSource(method);

            assertThat(annotation).isPresent();
            assertThat(annotation.get()).isInstanceOf(RedisCacheable.class);
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("doHandle handles KeyGenerator exception gracefully")
        void doHandle_withKeyGeneratorException_doesNotThrow() throws Exception {
            Method method = getMethod("cachedMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenThrow(new RuntimeException("Key generation failed"));

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, never()).register(any(), any(), any(CacheOperation.class), any(OperationKind.class));
        }
    }
}
