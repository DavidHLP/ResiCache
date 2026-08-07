package io.github.davidhlp.spring.cache.redis.annotation.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheAttributesProjector;
import io.github.davidhlp.spring.cache.redis.operation.OperationKind;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
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
 * Unit tests for EvictAnnotationHandler。
 *
 * <p>handler 直接持有 {@link RedisCacheAttributesProjector}(真实实例,非 mock)。operation
 * 由真实 projector + {@link RedisCacheEvictOperation#fromAttributes} 生成,测试验证
 * "真实投影路径产生真实 op"。projector + fromAttributes 各自有独立单测覆盖。
 *
 * <p>KeyGenerator 异常测试覆盖 registerOne try/catch 隔离。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvictAnnotationHandler Tests")
class EvictAnnotationHandlerTest {

    @Mock
    private RedisCacheRegister redisCacheRegister;

    @Mock
    private KeyGenerator keyGenerator;

    private final RedisCacheAttributesProjector projector = new RedisCacheAttributesProjector();

    private EvictAnnotationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new EvictAnnotationHandler(redisCacheRegister, keyGenerator, projector);
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

            when(keyGenerator.generate(target, method, args)).thenReturn(generatedKey);

            handler.doHandle(method, target, args);

            // operation 由真实 projector + fromAttributes 生成,验证 register 收到
            // RedisCacheEvictOperation + CACHE_EVICT kind
            verify(redisCacheRegister).register(
                    any(Method.class), any(Class.class),
                    any(RedisCacheEvictOperation.class), eq(OperationKind.CACHE_EVICT));
        }

        @Test
        @DisplayName("doHandle generates correct key using KeyGenerator")
        void doHandle_callsKeyGenerator() throws Exception {
            Method method = getMethod("evictMethod");
            Object target = new TestClass();
            Object[] args = new Object[]{"arg1", "arg2"};

            when(keyGenerator.generate(target, method, args)).thenReturn("test-key");

            handler.doHandle(method, target, args);

            verify(keyGenerator).generate(target, method, args);
        }
    }

    @Nested
    @DisplayName("Multiple Annotation Tests")
    class MultipleAnnotationTests {

        @Test
        @DisplayName("doHandle processes multiple annotations on different methods")
        void doHandle_withMultipleAnnotationsOnDifferentMethods_registersAll() throws Exception {
            Method method1 = getMethod("evictMethod");
            Method method2 = getMethod("evictAllMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(any(Object.class), any(Method.class), any(Object[].class))).thenReturn("key");

            handler.doHandle(method1, target, args);
            handler.doHandle(method2, target, args);

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
            Method method = getMethod("evictMethod");
            Object target = new TestClass();
            Object[] args = new Object[0];

            when(keyGenerator.generate(target, method, args)).thenThrow(new RuntimeException("Key generation failed"));

            handler.doHandle(method, target, args);

            verify(redisCacheRegister, never()).register(any(), any(), any(CacheOperation.class), any(OperationKind.class));
        }
    }
}
