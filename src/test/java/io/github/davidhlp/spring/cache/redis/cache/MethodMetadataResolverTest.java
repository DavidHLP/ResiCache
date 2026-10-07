package io.github.davidhlp.spring.cache.redis.cache;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MethodMetadataResolverTest {

    @Test
    void capturedContext_crossesWorkerAndRestoresWorkerState() throws Exception {
        DefaultMethodMetadataResolver resolver = new DefaultMethodMetadataResolver();
        Method method = Fixture.class.getMethod("load");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (ScopedActivation ignored = resolver.activate(method, Fixture.class)) {
            MDC.put("traceId", "caller-trace");
            MethodSnapshot snapshot = resolver.capture();
            Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
            String workerMethod = executor.submit(() -> resolver.runWithSnapshot(
                    snapshot,
                    mdcSnapshot,
                    () -> resolver.currentMethod().getName() + ":" + MDC.get("traceId"))).get(5, TimeUnit.SECONDS);

            assertThat(workerMethod).isEqualTo("load:caller-trace");
            String workerState = executor.submit(
                    () -> String.valueOf(resolver.currentMethod()) + ":" + MDC.get("traceId")).get(5, TimeUnit.SECONDS);
            assertThat(workerState).isEqualTo("null:null");
            assertThat(resolver.currentMethod()).isEqualTo(method);
            assertThat(MDC.get("traceId")).isEqualTo("caller-trace");
        } finally {
            MDC.clear();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(resolver.currentMethod()).isNull();
    }

    @Test
    void nestedActivation_restoresPreviousContextInLifoOrder() throws Exception {
        DefaultMethodMetadataResolver resolver = new DefaultMethodMetadataResolver();
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

    @Test
    void snapshots_restoreExistingWorkerStateAfterSuccessAndFailure() throws Exception {
        DefaultMethodMetadataResolver resolver = new DefaultMethodMetadataResolver();
        Method outer = Fixture.class.getMethod("outer");
        Method inner = Fixture.class.getMethod("inner");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                try (ScopedActivation activation = resolver.activate(outer, Fixture.class)) {
                    MDC.setContextMap(Map.of("traceId", "worker", "workerOnly", "yes"));
                    MethodSnapshot previous = resolver.capture();
                    MethodSnapshot captured = MethodSnapshot.of(inner, Fixture.class);
                    assertThat(resolver.runWithSnapshot(captured, Map.of("traceId", "caller"), () -> {
                        assertThat(resolver.currentMethod()).isEqualTo(inner);
                        assertThat(MDC.getCopyOfContextMap()).isEqualTo(Map.of("traceId", "caller"));
                        return "result";
                    })).isEqualTo("result");
                    assertThat(resolver.capture()).isSameAs(previous);
                    assertThat(MDC.getCopyOfContextMap()).isEqualTo(Map.of("traceId", "worker", "workerOnly", "yes"));
                    IllegalStateException failure = new IllegalStateException("work failed");
                    assertThatThrownBy(() -> resolver.runWithSnapshot(captured, Map.of("traceId", "caller"), () -> {
                        assertThat(resolver.currentMethod()).isEqualTo(inner);
                        assertThat(MDC.get("traceId")).isEqualTo("caller");
                        throw failure;
                    })).isSameAs(failure);
                    assertThat(resolver.capture()).isSameAs(previous);
                    assertThat(MDC.getCopyOfContextMap()).isEqualTo(Map.of("traceId", "worker", "workerOnly", "yes"));
                    for (Map<String, String> empty : java.util.Arrays.asList(null, Map.<String, String>of())) {
                        resolver.runWithSnapshot(null, empty, () -> {
                            // Empty metadata creates no new activation; empty MDC clears during work.
                            assertThat(resolver.capture()).isSameAs(previous);
                            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
                            return null;
                        });
                        assertThat(resolver.capture()).isSameAs(previous);
                        assertThat(MDC.get("traceId")).isEqualTo("worker");
                    }
                    resolver.runWithSnapshot(captured, Map.of("traceId", "nested"), () -> {
                        resolver.runWithSnapshot(previous, Map.of(), () -> {
                            assertThat(resolver.currentMethod()).isEqualTo(outer);
                            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
                            return null;
                        });
                        assertThat(resolver.currentMethod()).isEqualTo(inner);
                        assertThat(MDC.get("traceId")).isEqualTo("nested");
                        return null;
                    });
                } finally {
                    MDC.clear();
                }
                assertThat(resolver.capture()).isNull();
                return null;
            }).get(5, TimeUnit.SECONDS);
            worker.submit(() -> {
                assertThat(resolver.capture()).isNull();
                assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            }).get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void repeatedClose_doesNotOverwriteLaterActivation() throws Exception {
        DefaultMethodMetadataResolver resolver = new DefaultMethodMetadataResolver();
        Method outer = Fixture.class.getMethod("outer");
        Method inner = Fixture.class.getMethod("inner");
        try (ScopedActivation ignored = resolver.activate(outer, Fixture.class)) {
            ScopedActivation scope = resolver.activate(inner, Fixture.class);
            scope.close();
            assertThat(resolver.currentMethod()).isEqualTo(outer);
            try (ScopedActivation next = resolver.activate(inner, Fixture.class)) {
                scope.close();
                assertThat(resolver.currentMethod()).isEqualTo(inner);
            }
        }
        assertThat(resolver.capture()).isNull();
    }

    static final class Fixture {
        public void load() { }
        public void outer() { }
        public void inner() { }
    }

}
