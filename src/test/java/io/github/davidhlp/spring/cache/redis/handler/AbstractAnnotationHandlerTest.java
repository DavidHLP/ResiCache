package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.factory.OperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for
 * {@link AbstractAnnotationHandler#registerAll(Method, Object, Object[], Annotation[], Function, OperationFactory, AbstractAnnotationHandler.RegisterAction, String)}.
 *
 * <p>These tests pin down the seam's behaviour so that the four concrete handlers
 * (Cacheable / CachePut / Evict / Caching) can rely on it without each having to
 * retest the for-loop / null-check / exception-isolation logic. The integration
 * tests on the concrete handlers verify the wiring (factory + register method-ref);
 * the contract tests here verify the loop's runtime guarantees.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractAnnotationHandler.registerAll() Contract Tests")
class AbstractAnnotationHandlerTest {

    @Mock
    private RedisCacheRegister redisCacheRegister;

    @Mock
    private KeyGenerator keyGenerator;

    @Mock
    private OperationFactory<TestAnnotation, CacheOperation> factory;

    /** Operations captured by the local RegisterAction for assertion. */
    private final List<CacheOperation> capturedOps = new ArrayList<>();

    /** Local RegisterAction — avoids depending on a real RedisCacheRegister method shape. */
    private final AbstractAnnotationHandler.RegisterAction<CacheOperation> recorder =
            (m, t, op) -> capturedOps.add(op);

    private TestableHandler handler;
    private TestSubject target;
    private Object[] args;

    @BeforeEach
    void setUp() {
        capturedOps.clear();
        handler = new TestableHandler(redisCacheRegister, keyGenerator);
        target = new TestSubject();
        args = new Object[0];
    }

    @Nested
    @DisplayName("Empty / null input")
    class EmptyInput {

        @Test
        @DisplayName("registerAll returns empty list for null array")
        void registerAll_nullArray_returnsEmpty() throws Exception {
            Method method = TestSubject.class.getMethod("noAnno");
            List<CacheOperation> result = handler.exposedRegisterAll(
                    method, target, args,
                    null, TestAnnotation::key,
                    factory, recorder, "test");

            assertThat(result).isNotNull().isEmpty();
            assertThat(capturedOps).isEmpty();
            verify(factory, never()).create(any(), any(), anyString());
        }

        @Test
        @DisplayName("registerAll returns empty list for empty array")
        void registerAll_emptyArray_returnsEmpty() throws Exception {
            Method method = TestSubject.class.getMethod("noAnno");
            List<CacheOperation> result = handler.exposedRegisterAll(
                    method, target, args,
                    new TestAnnotation[0], TestAnnotation::key,
                    factory, recorder, "test");

            assertThat(result).isNotNull().isEmpty();
            assertThat(capturedOps).isEmpty();
            verify(factory, never()).create(any(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("Single element")
    class SingleElement {

        @Test
        @DisplayName("registerAll registers exactly one operation for a single annotation")
        void registerAll_singleElement_registersOne() throws Exception {
            Method method = TestSubject.class.getMethod("noAnno");
            TestAnnotation anno = makeAnno("k1");
            TestAnnotation[] annos = { anno };
            CacheOperation op1 = mockOperation();
            // keyExpression "k1" is non-empty, so KeyGenerator is NOT called.
            when(factory.create(method, anno, "k1")).thenReturn(op1);

            List<CacheOperation> result = handler.exposedRegisterAll(
                    method, target, args,
                    annos, TestAnnotation::key,
                    factory, recorder, "test");

            assertThat(result).hasSize(1).containsExactly(op1);
            assertThat(capturedOps).containsExactly(op1);
            verify(keyGenerator, never()).generate(any(), any(), any());
        }

        @Test
        @DisplayName("registerAll uses keyExtractor to derive key expression per element (skips KeyGenerator)")
        void registerAll_usesKeyExtractor() throws Exception {
            Method method = TestSubject.class.getMethod("noAnno");
            TestAnnotation anno = makeAnno("spel:#id");
            TestAnnotation[] annos = { anno };
            CacheOperation op1 = mockOperation();
            when(factory.create(method, anno, "spel:#id")).thenReturn(op1);

            handler.exposedRegisterAll(method, target, args,
                    annos, TestAnnotation::key,
                    factory, recorder, "test");

            verify(keyGenerator, never()).generate(any(), any(), any());
            verify(factory).create(method, anno, "spel:#id");
        }

        @Test
        @DisplayName("registerAll falls back to KeyGenerator when key expression is empty")
        void registerAll_emptyKey_fallsBackToKeyGenerator() throws Exception {
            Method method = TestSubject.class.getMethod("noAnno");
            TestAnnotation anno = makeAnno("");
            TestAnnotation[] annos = { anno };
            CacheOperation op1 = mockOperation();
            when(keyGenerator.generate(target, method, args)).thenReturn("generated-key");
            when(factory.create(method, anno, "generated-key")).thenReturn(op1);

            handler.exposedRegisterAll(method, target, args,
                    annos, TestAnnotation::key,
                    factory, recorder, "test");

            verify(keyGenerator).generate(target, method, args);
            assertThat(capturedOps).containsExactly(op1);
        }
    }

    @Nested
    @DisplayName("Multiple elements")
    class MultipleElements {

        @Test
        @DisplayName("registerAll registers all elements in input order")
        void registerAll_multipleElements_registersAllInOrder() throws Exception {
            Method method = TestSubject.class.getMethod("noAnno");
            TestAnnotation a1 = makeAnno("k1");
            TestAnnotation a2 = makeAnno("k2");
            TestAnnotation a3 = makeAnno("k3");
            TestAnnotation[] annos = { a1, a2, a3 };
            CacheOperation op1 = mockOperation();
            CacheOperation op2 = mockOperation();
            CacheOperation op3 = mockOperation();
            // All keys are non-empty, so KeyGenerator is NOT called.
            when(factory.create(method, a1, "k1")).thenReturn(op1);
            when(factory.create(method, a2, "k2")).thenReturn(op2);
            when(factory.create(method, a3, "k3")).thenReturn(op3);

            List<CacheOperation> result = handler.exposedRegisterAll(
                    method, target, args,
                    annos, TestAnnotation::key,
                    factory, recorder, "test");

            assertThat(result).containsExactly(op1, op2, op3);
            assertThat(capturedOps).containsExactly(op1, op2, op3);
            verify(factory, times(1)).create(method, a1, "k1");
            verify(factory, times(1)).create(method, a2, "k2");
            verify(factory, times(1)).create(method, a3, "k3");
            verify(keyGenerator, never()).generate(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Per-element exception isolation")
    class ExceptionIsolation {

        @Test
        @DisplayName("When one element's factory throws, the remaining elements still register")
        void registerAll_partialFailure_continuesWithRemaining() throws Exception {
            Method method = TestSubject.class.getMethod("noAnno");
            TestAnnotation a1 = makeAnno("k1");  // succeeds
            TestAnnotation a2 = makeAnno("k2");  // throws
            TestAnnotation a3 = makeAnno("k3");  // succeeds
            TestAnnotation[] annos = { a1, a2, a3 };
            CacheOperation op1 = mockOperation();
            CacheOperation op3 = mockOperation();
            // All keys are non-empty, so KeyGenerator is NOT called.
            when(factory.create(method, a1, "k1")).thenReturn(op1);
            when(factory.create(method, a2, "k2"))
                    .thenThrow(new RuntimeException("factory boom"));
            when(factory.create(method, a3, "k3")).thenReturn(op3);

            List<CacheOperation> result = handler.exposedRegisterAll(
                    method, target, args,
                    annos, TestAnnotation::key,
                    factory, recorder, "test");

            // Per-annotation exception isolation: only op1 and op3 end up in result.
            assertThat(result).hasSize(2).containsExactly(op1, op3);
            assertThat(capturedOps).containsExactly(op1, op3);
        }

        @Test
        @DisplayName("When all elements fail, registerAll returns empty list and does not throw")
        void registerAll_allFail_returnsEmpty() throws Exception {
            Method method = TestSubject.class.getMethod("noAnno");
            TestAnnotation a1 = makeAnno("k1");
            TestAnnotation a2 = makeAnno("k2");
            TestAnnotation[] annos = { a1, a2 };
            when(factory.create(method, a1, "k1"))
                    .thenThrow(new RuntimeException("factory boom"));
            when(factory.create(method, a2, "k2"))
                    .thenThrow(new RuntimeException("factory boom"));

            List<CacheOperation> result = handler.exposedRegisterAll(
                    method, target, args,
                    annos, TestAnnotation::key,
                    factory, recorder, "test");

            assertThat(result).isEmpty();
            assertThat(capturedOps).isEmpty();
        }
    }

    // ============================ test scaffolding ============================

    /** A test-only annotation with a {@code key()} attribute, for exercising the key extractor. */
    private @interface TestAnnotation {
        String key() default "";
    }

    /** Test subject with a method we can reflect on. */
    private static final class TestSubject {
        public void noAnno() {
        }
    }

    /**
     * Concrete subclass that exposes the protected {@code registerAll} method so
     * the test can drive it directly without going through a concrete handler's
     * doHandle. canHandle always returns true; doHandle is a no-op.
     */
    private static final class TestableHandler extends AbstractAnnotationHandler {
        TestableHandler(RedisCacheRegister register, KeyGenerator keyGen) {
            super(register, keyGen);
        }

        @Override
        protected boolean canHandle(Method method) {
            return true;
        }

        @Override
        protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
            return List.of();
        }

        <A extends Annotation, O extends CacheOperation> List<CacheOperation> exposedRegisterAll(
                Method method, Object target, Object[] args,
                A[] annotations, Function<A, String> keyExtractor,
                OperationFactory<A, O> factory, RegisterAction<O> action, String logTag) {
            return registerAll(method, target, args, annotations, keyExtractor, factory, action, logTag);
        }
    }

    /** Test helper: create a mock CacheOperation for assertion. */
    private static CacheOperation mockOperation() {
        return Mockito.mock(CacheOperation.class);
    }

    /** Test helper: create a TestAnnotation instance with the given key. */
    private static TestAnnotation makeAnno(String key) {
        return new TestAnnotation() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return TestAnnotation.class;
            }
        };
    }
}
