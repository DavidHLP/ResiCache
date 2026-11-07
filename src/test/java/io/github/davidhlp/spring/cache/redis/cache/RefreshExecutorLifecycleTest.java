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

    @Test
    void gracefulShutdown_drainsAcceptedQueuedWorkWithoutCancellation() {
        ControlledExecutor pool = new ControlledExecutor();
        ConcurrentHashMap<String, CompletableFuture<Void>> tasks = new ConcurrentHashMap<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolEarlyExpirationExecutor executor = new ThreadPoolEarlyExpirationExecutor(pool, tasks, registry);
        AtomicInteger executions = new AtomicInteger();
        try {
            executor.submit("first", executions::incrementAndGet);
            executor.submit("second", executions::incrementAndGet);
            List<CompletableFuture<Void>> accepted = new ArrayList<>(tasks.values());
            executor.shutdown();
            assertThat(executions).hasValue(2);
            assertThat(accepted).allSatisfy(future -> assertThat(future).isCompleted());
            assertThat(tasks).isEmpty();
            assertThat(registry.get("prerefresh.completed").counter().count()).isEqualTo(2);
            assertThat(registry.get("prerefresh.cancelled").counter().count()).isZero();
        } finally {
            executor.shutdown();
            registry.close();
        }
    }

    @Test
    void forcedShutdown_cancelsQueuedAndRunningFutures() throws Exception {
        verifyForcedShutdown(false);
    }

    @Test
    void interruptedShutdown_cancelsFuturesAndRestoresInterrupt() throws Exception {
        verifyForcedShutdown(true);
    }

    private void verifyForcedShutdown(boolean interrupted) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseIo = new CountDownLatch(1);
        AtomicBoolean ioFinished = new AtomicBoolean();
        AtomicBoolean queuedRan = new AtomicBoolean();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                return isTerminated(); // Force the timeout branch while work is still running.
            }
        };
        ConcurrentHashMap<String, CompletableFuture<Void>> tasks = new ConcurrentHashMap<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolEarlyExpirationExecutor executor = new ThreadPoolEarlyExpirationExecutor(pool, tasks, registry);
        try {
            executor.submit("running", () -> {
                started.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        released = releaseIo.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        // Model I/O that ignores the worker's shutdownNow interrupt.
                    }
                }
                ioFinished.set(true);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit("queued", () -> queuedRan.set(true));
            CompletableFuture<Void> running = tasks.get("running");
            CompletableFuture<Void> queued = tasks.get("queued");
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            executor.shutdown();
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
            Thread.interrupted();
            assertThat(running).isCancelled();
            assertThat(queued).isCancelled();
            assertThat(tasks).isEmpty();
            assertThat(pool.getQueue()).isEmpty();
            assertThat(ioFinished).isFalse();
            assertThat(queuedRan).isFalse();
            assertThat(registry.get("prerefresh.submitted").counter().count()).isEqualTo(2);
            assertThat(registry.get("prerefresh.completed").counter().count()).isEqualTo(2);
            assertThat(registry.get("prerefresh.cancelled").counter().count()).isEqualTo(2);
            assertThat(registry.get("prerefresh.outcome").tag("outcome", "cancelled").counter().count())
                    .isEqualTo(2);
            executor.shutdown();
            executor.submit("late", () -> queuedRan.set(true));
            assertThat(registry.get("prerefresh.outcome").tag("outcome", "rejected").counter().count())
                    .isEqualTo(1);
            assertThat(registry.get("prerefresh.cancelled").counter().count()).isEqualTo(2);
        } finally {
            Thread.interrupted();
            releaseIo.countDown();
            pool.shutdownNow();
            pool.close();
            registry.close();
        }
        assertThat(ioFinished).isTrue();
        assertThat(queuedRan).isFalse();
    }

    @Test
    void forcedShutdown_waitsForAcceptedSubmissionPublication() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        CountDownLatch stopping = new CountDownLatch(1);
        ControlledExecutor pool = new ControlledExecutor() {
            @Override
            public void execute(Runnable work) {
                super.execute(work);
                accepted.countDown();
                try {
                    assertThat(publish.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }

            @Override
            public void shutdown() {
                stopping.countDown();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return false;
            }
        };
        ConcurrentHashMap<String, CompletableFuture<Void>> tasks = new ConcurrentHashMap<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolEarlyExpirationExecutor executor = new ThreadPoolEarlyExpirationExecutor(pool, tasks, registry);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> submission = callers.submit(() -> executor.submit("key", () -> { }));
            assertThat(accepted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> shutdown = callers.submit(executor::shutdown);
            assertThat(stopping.await(5, TimeUnit.SECONDS)).isTrue();
            publish.countDown();
            submission.get(5, TimeUnit.SECONDS);
            shutdown.get(5, TimeUnit.SECONDS);
            assertThat(tasks).isEmpty();
            assertThat(registry.get("prerefresh.submitted").counter().count()).isEqualTo(1);
            assertThat(registry.get("prerefresh.completed").counter().count()).isEqualTo(1);
            assertThat(registry.get("prerefresh.cancelled").counter().count()).isEqualTo(1);
        } finally {
            publish.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            registry.close();
        }
    }

    private static class ControlledExecutor extends AbstractExecutorService {
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
