package io.github.davidhlp.spring.cache.redis.annotation.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheAttributesProjector;
import io.github.davidhlp.spring.cache.redis.operation.OperationKind;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CachingAnnotationHandler —— ADR-0059 收敛后形态。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CachingAnnotationHandler Tests")
class CachingAnnotationHandlerTest {

    @Mock
    private RedisCacheRegister redisCacheRegister;

    @Mock
    private KeyGenerator keyGenerator;

    private final RedisCacheAttributesProjector projector = new RedisCacheAttributesProjector();

    private CachingAnnotationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CachingAnnotationHandler(redisCacheRegister, keyGenerator, projector);
    }

    private Method getMethod(String name) throws NoSuchMethodException {
        return TestClass.class.getMethod(name);
    }

    // Test class with annotated methods
    private static class TestClass {
        @RedisCaching(
                redisCacheable = @RedisCacheable(cacheNames = "cache1", ttl = 60),
                redisCacheEvict = @RedisCacheEvict(cacheNames = "cache2")
        )
        public void combinedMethod() {
        }

        @RedisCaching(
                redisCacheable = {
                        @RedisCacheable(cacheNames = "cache1", ttl = 60),
                        @RedisCacheable(cacheNames = "cache3", ttl = 120)
                }
        )
        public void multipleCacheableMethod() {
        }

        @RedisCaching(
                redisCacheEvict = {
                        @RedisCacheEvict(cacheNames = "cache1"),
                        @RedisCacheEvict(cacheNames = "cache2")
                }
        )
        public void multipleEvictMethod() {
        }

        public void noAnnotation() {
        }
    }

    @Nested
    @DisplayName("canHandle() Tests")
    class CanHandleTests {

        @Test
        @DisplayName("canHandle returns true when method has @RedisCaching annotation")
        void canHandle_withRedisCaching_returnsTrue() throws NoSuchMethodException {
            Method method = getMethod("combinedMethod");

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
    @DisplayName("doHandle() Tests - Combined Annotations")
    class DoHandleCombinedTests {

        @Test
        @DisplayName("doHandle registers both cacheable and evict operations with correct kinds")
        void doHandle_withCombinedAnnotations_registersBoth() throws Exception {
            Method method = getMethod("combinedMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(any(Object.class), any(Method.class), any(Object[].class))).thenReturn("key");

            handler.doHandle(method, target, args);

            // ADR-0065:operation 由真实 projector 生成;验证 2 个 kind 各调用 1 次
            verify(redisCacheRegister).register(
                    any(Method.class), any(Class.class),
                    any(RedisCacheableOperation.class), eq(OperationKind.CACHEABLE));
            verify(redisCacheRegister).register(
                    any(Method.class), any(Class.class),
                    any(RedisCacheEvictOperation.class), eq(OperationKind.CACHE_EVICT));
        }
    }

    @Nested
    @DisplayName("doHandle() Tests - Multiple Annotations")
    class DoHandleMultipleTests {

        @Test
        @DisplayName("doHandle registers multiple cacheable operations for multiple @RedisCacheable")
        void doHandle_withMultipleCacheable_registersAll() throws Exception {
            Method method = getMethod("multipleCacheableMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenReturn("key");

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, times(2)).register(
                    any(Method.class), any(Class.class),
                    any(RedisCacheableOperation.class), eq(OperationKind.CACHEABLE));
        }

        @Test
        @DisplayName("doHandle registers multiple evict operations for multiple @RedisCacheEvict")
        void doHandle_withMultipleEvict_registersAll() throws Exception {
            Method method = getMethod("multipleEvictMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenReturn("key");

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, times(2)).register(
                    any(Method.class), any(Class.class),
                    any(RedisCacheEvictOperation.class), eq(OperationKind.CACHE_EVICT));
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("doHandle handles KeyGenerator exception gracefully")
        void doHandle_withKeyGeneratorException_doesNotThrow() throws Exception {
            Method method = getMethod("combinedMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenThrow(new RuntimeException("Key generation failed"));

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, never()).register(any(), any(), any(CacheOperation.class), any(OperationKind.class));
        }
    }
}
