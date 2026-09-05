package io.github.davidhlp.spring.cache.redis.cache;





import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.expression.AnnotatedElementKey;
import static org.assertj.core.api.Assertions.assertThat;

class MethodMetadataResolverTest {

    @Test
    void capturedContext_crossesWorkerAndRestoresWorkerState() throws Exception {
        TestResolver resolver = new TestResolver();
        Method method = Fixture.class.getMethod("load");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (ScopedActivation ignored = resolver.activate(method, Fixture.class)) {
            MDC.put("traceId", "caller-trace");
            MethodSnapshot snapshot = resolver.capture();
            Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
            String workerMethod = executor.submit(() -> resolver.runWithSnapshot(
                    snapshot,
                    mdcSnapshot,
                    () -> resolver.currentMethod().getName() + ":" + MDC.get("traceId"))).get();

            assertThat(workerMethod).isEqualTo("load:caller-trace");
            String workerState = executor.submit(
                    () -> String.valueOf(resolver.currentMethod()) + ":" + MDC.get("traceId")).get();
            assertThat(workerState).isEqualTo("null:null");
            assertThat(resolver.currentMethod()).isEqualTo(method);
            assertThat(MDC.get("traceId")).isEqualTo("caller-trace");
        } finally {
            MDC.clear();
            executor.shutdownNow();
        }

        assertThat(resolver.currentMethod()).isNull();
    }

    @Test
    void nestedActivation_restoresPreviousContextInLifoOrder() throws Exception {
        TestResolver resolver = new TestResolver();
        Method outer = Fixture.class.getMethod("outer");
        Method inner = Fixture.class.getMethod("inner");

        try (ScopedActivation ignored = resolver.activate(outer, Fixture.class)) {
            MethodSnapshot outerSnapshot = resolver.capture();
            try (ScopedActivation ignoredInner = resolver.activate(inner, Fixture.class)) {
                assertThat(resolver.currentMethod()).isEqualTo(inner);
                resolver.runWithSnapshot(outerSnapshot, () -> {
                    assertThat(resolver.currentMethod()).isEqualTo(outer);
                    return null;
                });
                assertThat(resolver.currentMethod()).isEqualTo(inner);
            }
            assertThat(resolver.currentMethod()).isEqualTo(outer);
        }

        assertThat(resolver.currentMethod()).isNull();
    }

    static final class Fixture {
        public void load() { }
        public void outer() { }
        public void inner() { }
    }

    private static final class TestResolver implements MethodMetadataResolver {
        private final ThreadLocal<MethodSnapshot> current = new ThreadLocal<>();

        @Override
        public AnnotatedElementKey currentKey() {
            MethodSnapshot snapshot = current.get();
            return snapshot == null ? null : snapshot.annotatedElementKey();
        }

        @Override
        public Method currentMethod() {
            MethodSnapshot snapshot = current.get();
            return snapshot == null ? null : snapshot.method();
        }

        @Override
        public Class<?> currentTargetClass() {
            MethodSnapshot snapshot = current.get();
            return snapshot == null ? null : snapshot.targetClass();
        }

        @Override
        public MethodSnapshot currentContext() {
            return current.get();
        }

        @Override
        public ScopedActivation activate(Method method, Class<?> targetClass) {
            MethodSnapshot previous = current.get();
            current.set(MethodSnapshot.of(method, targetClass));
            return new ScopedActivation(() -> {
                if (previous == null) {
                    current.remove();
                } else {
                    current.set(previous);
                }
            });
        }
    }
}
