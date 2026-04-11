package io.github.davidhlp.spring.cache.redis.core.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.core.factory.CacheableOperationFactory;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
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
 * Unit tests for CacheableAnnotationHandler.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CacheableAnnotationHandler Tests")
class CacheableAnnotationHandlerTest {

    @Mock
    private RedisCacheRegister redisCacheRegister;

    @Mock
    private KeyGenerator keyGenerator;

    @Mock
    private CacheableOperationFactory cacheableOperationFactory;

    private CacheableAnnotationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CacheableAnnotationHandler(redisCacheRegister, keyGenerator, cacheableOperationFactory);
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
            RedisCacheableOperation operation = RedisCacheableOperation.builder()
                    .name("cachedMethod")
                    .key(generatedKey)
                    .cacheNames("testCache")
                    .build();

            when(keyGenerator.generate(target, method, args)).thenReturn(generatedKey);
            when(cacheableOperationFactory.create(eq(method), any(RedisCacheable.class), eq(target), eq(args), eq(generatedKey)))
                    .thenReturn(operation);

            handler.doHandle(method, target, args);

            verify(redisCacheRegister).registerCacheableOperation(operation);
        }

        @Test
        @DisplayName("doHandle generates correct key using KeyGenerator")
        void doHandle_callsKeyGenerator() throws Exception {
            Method method = getMethod("cachedMethod");
            Object target = new TestClass();
            Object[] args = new Object[]{"arg1", "arg2"};

            when(keyGenerator.generate(target, method, args)).thenReturn("test-key");
            when(cacheableOperationFactory.create(any(), any(), any(), any(), any()))
                    .thenReturn(RedisCacheableOperation.builder().build());

            handler.doHandle(method, target, args);

            verify(keyGenerator).generate(target, method, args);
        }

        @Test
        @DisplayName("doHandle uses factory to create operation")
        void doHandle_usesFactoryToCreateOperation() throws Exception {
            Method method = getMethod("cachedMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];
            String generatedKey = "test-key";

            when(keyGenerator.generate(target, method, args)).thenReturn(generatedKey);
            when(cacheableOperationFactory.create(eq(method), any(RedisCacheable.class), eq(target), eq(args), eq(generatedKey)))
                    .thenReturn(RedisCacheableOperation.builder().build());

            handler.doHandle(method, target, args);

            verify(cacheableOperationFactory).create(eq(method), any(RedisCacheable.class), eq(target), eq(args), eq(generatedKey));
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

            verify(redisCacheRegister, never()).registerCacheableOperation(any());
        }

        @Test
        @DisplayName("doHandle handles factory exception gracefully")
        void doHandle_withFactoryException_doesNotThrow() throws Exception {
            Method method = getMethod("cachedMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenReturn("key");
            when(cacheableOperationFactory.create(any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Factory error"));

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, never()).registerCacheableOperation(any());
        }
    }
}
