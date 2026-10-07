package io.github.davidhlp.spring.cache.redis.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RefreshExecutorLifecycleTest {

    @Test
    void overlappingSameKey_deduplicatesThenAllowsCompletionAndCancellationResubmission() throws Exception {
        ControlledExecutor pool = new ControlledExecutor();
        ConcurrentHashMap<String, CompletableFuture<Void>> tasks = new ConcurrentHashMap<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolEarlyExpirationExecutor executor = new ThreadPoolEarlyExpirationExecutor(pool, tasks, registry);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        try {
            List<Future<?>> submissions = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                submissions.add(callers.submit(() -> {
                    ready.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    executor.submit("key", executions::incrementAndGet);
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<?> submission : submissions) {
                submission.get(5, TimeUnit.SECONDS);
            }
            assertThat(pool.pending).hasSize(1);
            assertThat(tasks).hasSize(1);
            assertThat(registry.get("prerefresh.submitted").counter().count()).isEqualTo(1);
            pool.runNext();
            assertThat(executions).hasValue(1);
            assertThat(tasks).isEmpty();

            executor.submit("key", executions::incrementAndGet);
            CompletableFuture<Void> cancelled = tasks.get("key");
            executor.cancel("key");
            assertThat(cancelled).isCancelled();
            executor.submit("key", executions::incrementAndGet);
            CompletableFuture<Void> replacement = tasks.get("key");
            pool.runNext(); // cancelled runnable must not execute or remove the replacement
            assertThat(tasks.get("key")).isSameAs(replacement);
            assertThat(executions).hasValue(1);
            pool.runNext();
            assertThat(executions).hasValue(2);
            assertThat(tasks).isEmpty();
            assertThat(registry.get("prerefresh.submitted").counter().count()).isEqualTo(3);
            assertThat(registry.get("prerefresh.completed").counter().count()).isEqualTo(3);
            assertThat(registry.get("prerefresh.cancelled").counter().count()).isEqualTo(1);
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            registry.close();
        }
    }

    @Test
    void delayedOldCompletion_cannotRemoveNewTask() throws Exception {
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch allowRemoval = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        ConcurrentHashMap<String, CompletableFuture<Void>> tasks = new ConcurrentHashMap<>() {
            @Override
            public boolean remove(Object key, Object value) {
                if (first.compareAndSet(true, false)) {
                    removing.countDown();
                    try {
                        assertThat(allowRemoval.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                }
                return super.remove(key, value);
            }
        };
        ControlledExecutor pool = new ControlledExecutor();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolEarlyExpirationExecutor executor = new ThreadPoolEarlyExpirationExecutor(pool, tasks, registry);
        ExecutorService completer = Executors.newSingleThreadExecutor();
        try {
            executor.submit("key", () -> { });
            CompletableFuture<Void> old = tasks.get("key");
            Future<?> completion = completer.submit(pool::runNext);
            assertThat(removing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(old).isDone();
            executor.submit("key", () -> { });
            CompletableFuture<Void> replacement = tasks.get("key");
            assertThat(replacement).isNotSameAs(old).isNotDone();
            allowRemoval.countDown();
            completion.get(5, TimeUnit.SECONDS);
            assertThat(tasks.get("key")).isSameAs(replacement);
            pool.runNext();
            assertThat(tasks).isEmpty();
            assertThat(registry.get("prerefresh.submitted").counter().count()).isEqualTo(2);
            assertThat(registry.get("prerefresh.completed").counter().count()).isEqualTo(2);
            assertThat(registry.get("prerefresh.cancelled").counter().count()).isZero();
        } finally {
            allowRemoval.countDown();
            completer.shutdownNow();
            assertThat(completer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();
            registry.close();
        }
    }

    private static final class ControlledExecutor extends AbstractExecutorService {
        private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
        private boolean shutdown;

        void runNext() {
            Runnable work = pending.poll();
            assertThat(work).isNotNull();
            work.run();
        }

        @Override
        public void execute(Runnable work) {
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            pending.add(work);
        }

        @Override
        public void shutdown() {
            shutdown = true;
            while (!pending.isEmpty()) {
                runNext();
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            List<Runnable> abandoned = new ArrayList<>(pending);
            pending.clear();
            shutdown = true;
            return abandoned;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && pending.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
