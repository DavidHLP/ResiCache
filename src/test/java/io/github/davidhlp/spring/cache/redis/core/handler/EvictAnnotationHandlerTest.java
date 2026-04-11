package io.github.davidhlp.spring.cache.redis.core.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.core.factory.EvictOperationFactory;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheEvictOperation;
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
 * Unit tests for EvictAnnotationHandler.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvictAnnotationHandler Tests")
class EvictAnnotationHandlerTest {

    @Mock
    private RedisCacheRegister redisCacheRegister;

    @Mock
    private KeyGenerator keyGenerator;

    @Mock
    private EvictOperationFactory evictOperationFactory;

    private EvictAnnotationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new EvictAnnotationHandler(redisCacheRegister, keyGenerator, evictOperationFactory);
    }

    private Method getMethod(String name) throws NoSuchMethodException {
        return TestClass.class.getMethod(name);
    }

    // Test class with annotated methods
    private static class TestClass {
        @RedisCacheEvict(cacheNames = "testCache")
        public void evictMethod() {
        }

        @RedisCacheEvict(cacheNames = "anotherCache", allEntries = true)
        public void evictAllMethod() {
        }

        public void noAnnotation() {
        }
    }

    @Nested
    @DisplayName("canHandle() Tests")
    class CanHandleTests {

        @Test
        @DisplayName("canHandle returns true when method has @RedisCacheEvict annotation")
        void canHandle_withRedisCacheEvict_returnsTrue() throws NoSuchMethodException {
            Method method = getMethod("evictMethod");

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
        @DisplayName("doHandle registers evict operation with correct parameters")
        void doHandle_withValidAnnotation_registersOperation() throws Exception {
            Method method = getMethod("evictMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];
            String generatedKey = "generated-key";
            RedisCacheEvictOperation operation = RedisCacheEvictOperation.builder()
                    .name("evictMethod")
                    .key(generatedKey)
                    .cacheNames("testCache")
                    .build();

            when(keyGenerator.generate(target, method, args)).thenReturn(generatedKey);
            when(evictOperationFactory.create(eq(method), any(RedisCacheEvict.class), eq(target), eq(args), eq(generatedKey)))
                    .thenReturn(operation);

            handler.doHandle(method, target, args);

            verify(redisCacheRegister).registerCacheEvictOperation(operation);
        }

        @Test
        @DisplayName("doHandle generates correct key using KeyGenerator")
        void doHandle_callsKeyGenerator() throws Exception {
            Method method = getMethod("evictMethod");
            Object target = new TestClass();
            Object[] args = new Object[]{"arg1", "arg2"};

            when(keyGenerator.generate(target, method, args)).thenReturn("test-key");
            when(evictOperationFactory.create(any(), any(), any(), any(), any()))
                    .thenReturn(RedisCacheEvictOperation.builder().build());

            handler.doHandle(method, target, args);

            verify(keyGenerator).generate(target, method, args);
        }

        @Test
        @DisplayName("doHandle uses factory to create operation")
        void doHandle_usesFactoryToCreateOperation() throws Exception {
            Method method = getMethod("evictMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];
            String generatedKey = "test-key";

            when(keyGenerator.generate(target, method, args)).thenReturn(generatedKey);
            when(evictOperationFactory.create(eq(method), any(RedisCacheEvict.class), eq(target), eq(args), eq(generatedKey)))
                    .thenReturn(RedisCacheEvictOperation.builder().build());

            handler.doHandle(method, target, args);

            verify(evictOperationFactory).create(eq(method), any(RedisCacheEvict.class), eq(target), eq(args), eq(generatedKey));
        }
    }

    @Nested
    @DisplayName("Multiple Annotation Tests")
    class MultipleAnnotationTests {

        @Test
        @DisplayName("doHandle processes multiple annotations on different methods")
        void doHandle_withMultipleAnnotationsOnDifferentMethods_registersAll() throws Exception {
            // Test that different methods with @RedisCacheEvict each get processed
            Method method1 = getMethod("evictMethod");
            Method method2 = getMethod("evictAllMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(any(Object.class), any(Method.class), any(Object[].class))).thenReturn("key");
            when(evictOperationFactory.create(any(Method.class), any(RedisCacheEvict.class), any(Object.class), any(Object[].class), any(String.class)))
                    .thenReturn(RedisCacheEvictOperation.builder().build());

            handler.doHandle(method1, target, args);
            handler.doHandle(method2, target, args);

            verify(redisCacheRegister, times(2)).registerCacheEvictOperation(any(RedisCacheEvictOperation.class));
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("doHandle handles KeyGenerator exception gracefully")
        void doHandle_withKeyGeneratorException_doesNotThrow() throws Exception {
            Method method = getMethod("evictMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenThrow(new RuntimeException("Key generation failed"));

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, never()).registerCacheEvictOperation(any());
        }

        @Test
        @DisplayName("doHandle handles factory exception gracefully")
        void doHandle_withFactoryException_doesNotThrow() throws Exception {
            Method method = getMethod("evictMethod");
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
