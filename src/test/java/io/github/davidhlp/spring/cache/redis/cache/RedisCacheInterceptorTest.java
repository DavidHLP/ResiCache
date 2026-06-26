package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.handler.CachePutAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CacheableAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachingAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.EvictAnnotationHandler;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheableOperation;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RedisCacheInterceptor Tests")
class RedisCacheInterceptorTest {

    @Mock
    private CacheableAnnotationHandler cacheableHandler;

    @Mock
    private EvictAnnotationHandler evictHandler;

    @Mock
    private CachingAnnotationHandler cachingHandler;

    @Mock
    private CachePutAnnotationHandler cachePutHandler;

    @Mock
    private MethodInvocation invocation;

    private RedisCacheInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // Setup chain: cacheableHandler -> evictHandler -> cachingHandler -> cachePutHandler
        when(cacheableHandler.setNext(evictHandler)).thenReturn(evictHandler);
        when(evictHandler.setNext(cachingHandler)).thenReturn(cachingHandler);
        when(cachingHandler.setNext(cachePutHandler)).thenReturn(cachePutHandler);

        interceptor = new RedisCacheInterceptor(
                cacheableHandler, evictHandler, cachingHandler, cachePutHandler);
    }

    private Method getMethod(String name) throws NoSuchMethodException {
        return TestClass.class.getMethod(name);
    }

    private static class TestClass {
        public void noAnnotation() {}

        @io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable(cacheNames = "testCache", ttl = 60)
        public void cachedMethod() {}
    }

    @Nested
    @DisplayName("Handler Chain Construction Tests")
    class HandlerChainTests {

        @Test
        @DisplayName("interceptor builds handler chain correctly")
        void interceptor_buildsHandlerChain() {
            verify(cacheableHandler).setNext(evictHandler);
            verify(evictHandler).setNext(cachingHandler);
            verify(cachingHandler).setNext(cachePutHandler);
        }
    }

    @Nested
    @DisplayName("invoke() Tests")
    class InvokeTests {

        @Test
        @DisplayName("invoke proceeds when no cache operations")
        void invoke_withNoCacheOperations_proceeds() throws Throwable {
            Method method = getMethod("noAnnotation");
            TestClass target = new TestClass();
            Object[] args = new Object[]{};

            when(invocation.getMethod()).thenReturn(method);
            when(invocation.getThis()).thenReturn(target);
            when(invocation.getArguments()).thenReturn(args);
            when(cacheableHandler.handle(method, target, args)).thenReturn(java.util.Collections.emptyList());
            when(invocation.proceed()).thenReturn("result");

            Object result = interceptor.invoke(invocation);

            assertThat(result).isEqualTo("result");
            verify(invocation).proceed();
        }

        @Test
        @DisplayName("invoke proceeds when condition is truthy")
        void invoke_withTruthyCondition_proceeds() throws Throwable {
            Method method = getMethod("cachedMethod");
            TestClass target = new TestClass();
            Object[] args = new Object[]{};
            CacheableOperation cacheOperation = mock(CacheableOperation.class);

            when(invocation.getMethod()).thenReturn(method);
            when(invocation.getThis()).thenReturn(target);
            when(invocation.getArguments()).thenReturn(args);
            when(cacheableHandler.handle(method, target, args)).thenReturn(List.of(cacheOperation));
            when(cacheOperation.getCondition()).thenReturn("true");
            when(invocation.proceed()).thenReturn("result");

            Object result = interceptor.invoke(invocation);

            assertThat(result).isEqualTo("result");
            verify(invocation).proceed();
        }
    }

    @Nested
    @DisplayName("invoke() no-op Tests")
    class InvokeNoOpTests {

        @Test
        @DisplayName("invoke proceeds to target when no cache operations present")
        void invoke_withNoOperations_proceedsToTarget() throws Throwable {
            // RedisCacheInterceptor 无 evaluateCondition 方法(condition/unless 由 Spring 原生处理);
            // 此处验证无 operation 时 invoke 正常委托给目标方法,并返回其结果
            Method method = getMethod("noAnnotation");
            TestClass target = new TestClass();
            Object[] args = new Object[]{};

            when(invocation.getMethod()).thenReturn(method);
            when(invocation.getThis()).thenReturn(target);
            when(invocation.getArguments()).thenReturn(args);
            when(cacheableHandler.handle(method, target, args)).thenReturn(java.util.Collections.emptyList());
            when(invocation.proceed()).thenReturn("result");

            Object result = interceptor.invoke(invocation);

            assertThat(result).isEqualTo("result");
            verify(invocation).proceed();
        }
    }

    @Nested
    @DisplayName("handleCacheAnnotations() Tests")
    class HandleCacheAnnotationsTests {

        @Test
        @DisplayName("handleCacheAnnotations delegates to handler chain")
        void handleCacheAnnotations_delegatesToHandlerChain() throws Throwable {
            Method method = getMethod("cachedMethod");
            TestClass target = new TestClass();
            Object[] args = new Object[]{};
            List<CacheOperation> expectedOperations = List.of(mock(CacheableOperation.class));

            when(invocation.getMethod()).thenReturn(method);
            when(invocation.getThis()).thenReturn(target);
            when(invocation.getArguments()).thenReturn(args);
            when(cacheableHandler.handle(method, target, args)).thenReturn(expectedOperations);
            when(invocation.proceed()).thenReturn("result");

            interceptor.invoke(invocation);

            verify(cacheableHandler).handle(method, target, args);
        }
    }

    @Nested
    @DisplayName("Reactive Type Detection Tests")
    class ReactiveTypeDetectionTests {

        @Test
        @DisplayName("isReactiveType returns true for Mono")
        void isReactiveType_mono_returnsTrue() {
            assertThat(RedisCacheInterceptor.isReactiveType("reactor.core.publisher.Mono")).isTrue();
        }

        @Test
        @DisplayName("isReactiveType returns true for Flux")
        void isReactiveType_flux_returnsTrue() {
            assertThat(RedisCacheInterceptor.isReactiveType("reactor.core.publisher.Flux")).isTrue();
        }

        @Test
        @DisplayName("isReactiveType returns false for non-reactive types")
        void isReactiveType_nonReactive_returnsFalse() {
            assertThat(RedisCacheInterceptor.isReactiveType("java.lang.String")).isFalse();
            assertThat(RedisCacheInterceptor.isReactiveType("java.lang.Integer")).isFalse();
            assertThat(RedisCacheInterceptor.isReactiveType("java.lang.Object")).isFalse();
        }
    }
}
