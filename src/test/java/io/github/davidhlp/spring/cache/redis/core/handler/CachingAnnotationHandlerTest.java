package io.github.davidhlp.spring.cache.redis.core.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import io.github.davidhlp.spring.cache.redis.core.factory.CacheableOperationFactory;
import io.github.davidhlp.spring.cache.redis.core.factory.EvictOperationFactory;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CachingAnnotationHandler.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CachingAnnotationHandler Tests")
class CachingAnnotationHandlerTest {

    @Mock
    private RedisCacheRegister redisCacheRegister;

    @Mock
    private KeyGenerator keyGenerator;

    @Mock
    private CacheableOperationFactory cacheableOperationFactory;

    @Mock
    private EvictOperationFactory evictOperationFactory;

    private CachingAnnotationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CachingAnnotationHandler(
                redisCacheRegister, keyGenerator, cacheableOperationFactory, evictOperationFactory);
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
        @DisplayName("doHandle registers both cacheable and evict operations")
        void doHandle_withCombinedAnnotations_registersBoth() throws Exception {
            Method method = getMethod("combinedMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(any(Object.class), any(Method.class), any(Object[].class))).thenReturn("key");
            when(cacheableOperationFactory.create(any(Method.class), any(RedisCacheable.class), any(Object.class), any(Object[].class), any(String.class)))
                    .thenReturn(RedisCacheableOperation.builder().build());
            when(evictOperationFactory.create(any(Method.class), any(RedisCacheEvict.class), any(Object.class), any(Object[].class), any(String.class)))
                    .thenReturn(RedisCacheEvictOperation.builder().build());

            handler.doHandle(method, target, args);

            verify(redisCacheRegister).registerCacheableOperation(any(RedisCacheableOperation.class));
            verify(redisCacheRegister).registerCacheEvictOperation(any(RedisCacheEvictOperation.class));
        }

        @Test
        @DisplayName("doHandle registers cacheable operation with correct annotation")
        void doHandle_correctCacheableAnnotation() throws Exception {
            Method method = getMethod("combinedMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenReturn("key");
            when(cacheableOperationFactory.create(eq(method), any(RedisCacheable.class), eq(target), eq(args), eq("key")))
                    .thenReturn(RedisCacheableOperation.builder().build());
            when(evictOperationFactory.create(any(), any(), any(), any(), any()))
                    .thenReturn(RedisCacheEvictOperation.builder().build());

            handler.doHandle(method, target, args);

            verify(cacheableOperationFactory).create(eq(method), any(RedisCacheable.class), eq(target), eq(args), eq("key"));
        }

        @Test
        @DisplayName("doHandle registers evict operation with correct annotation")
        void doHandle_correctEvictAnnotation() throws Exception {
            Method method = getMethod("combinedMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenReturn("key");
            when(cacheableOperationFactory.create(any(), any(), any(), any(), any()))
                    .thenReturn(RedisCacheableOperation.builder().build());
            when(evictOperationFactory.create(eq(method), any(RedisCacheEvict.class), eq(target), eq(args), eq("key")))
                    .thenReturn(RedisCacheEvictOperation.builder().build());

            handler.doHandle(method, target, args);

            verify(evictOperationFactory).create(eq(method), any(RedisCacheEvict.class), eq(target), eq(args), eq("key"));
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
            when(cacheableOperationFactory.create(any(), any(), any(), any(), any()))
                    .thenReturn(RedisCacheableOperation.builder().build());

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, times(2)).registerCacheableOperation(any(RedisCacheableOperation.class));
        }

        @Test
        @DisplayName("doHandle registers multiple evict operations for multiple @RedisCacheEvict")
        void doHandle_withMultipleEvict_registersAll() throws Exception {
            Method method = getMethod("multipleEvictMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenReturn("key");
            when(evictOperationFactory.create(any(), any(), any(), any(), any()))
                    .thenReturn(RedisCacheEvictOperation.builder().build());

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, times(2)).registerCacheEvictOperation(any(RedisCacheEvictOperation.class));
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

            verify(redisCacheRegister, never()).registerCacheableOperation(any());
            verify(redisCacheRegister, never()).registerCacheEvictOperation(any());
        }

        @Test
        @DisplayName("doHandle handles cacheable factory exception gracefully")
        void doHandle_withCacheableFactoryException_doesNotThrow() throws Exception {
            // Use multipleCacheableMethod which only has cacheable annotations
            Method method = getMethod("multipleCacheableMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenReturn("key");
            when(cacheableOperationFactory.create(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Factory error"));

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, never()).registerCacheableOperation(any());
            verify(redisCacheRegister, never()).registerCacheEvictOperation(any());
        }

        @Test
        @DisplayName("doHandle handles evict factory exception gracefully")
        void doHandle_withEvictFactoryException_doesNotThrow() throws Exception {
            // Use multipleEvictMethod which only has evict annotations
            Method method = getMethod("multipleEvictMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenReturn("key");
            when(evictOperationFactory.create(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Factory error"));

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, never()).registerCacheEvictOperation(any());
        }
    }
}
