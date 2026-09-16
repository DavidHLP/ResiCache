package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SyncRole lifecycle tests")
class SyncRoleTest {

    @Test
    @DisplayName("leader publishes, follower joins, then complete and cleanup run in order")
    void leaderLifecycle_publishesFollowerJoinsCompletesAndCleansUp() throws Exception {
        RecordingState state = new RecordingState();
        SyncRegistration registration = state.publish("lifecycle-key");
        RedisProCacheProperties properties = localOnlyProperties();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            SyncRole.Leader<String> leader = new SyncRole.Leader<>(
                    "lifecycle-key",
                    SyncLockTimeout.Resolved.fromSeconds(5),
                    () -> {
                        loaderStarted.countDown();
                        await(releaseLoader);
                        return "VALUE";
                    },
                    registration,
                    List.of(),
                    properties,
                    state);
            Future<String> leaderResult = executor.submit(leader::run);
            assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> followerResult = executor.submit(
                    new SyncRole.Follower<String>("lifecycle-key", registration.future(),
                            SyncLockTimeout.Resolved.fromSeconds(5))::run);
            assertThat(state.completionObserved.getCount())
                    .as("completion remains pending while loader is held").isEqualTo(1L);
            releaseLoader.countDown();

            assertThat(leaderResult.get(5, TimeUnit.SECONDS)).isEqualTo("VALUE");
            assertThat(followerResult.get(5, TimeUnit.SECONDS)).isEqualTo("VALUE");
            assertThat(state.events).containsSubsequence("publish", "complete", "cleanup");
            assertThat(state.events.indexOf("complete"))
                    .isLessThan(state.events.indexOf("cleanup"));
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("exclusive role executes work without publishing or cleaning a single-flight registration")
    void exclusiveRole_doesNotClaimSingleFlightRegistration() {
        RecordingState state = new RecordingState();
        RedisProCacheProperties properties = localOnlyProperties();

        String result = new SyncRole.Exclusive<>(
                "exclusive-key",
                SyncLockTimeout.Resolved.fromSeconds(5),
                () -> "EXCLUSIVE",
                List.of(),
                properties,
                state).run();

        assertThat(result).isEqualTo("EXCLUSIVE");
        assertThat(state.events).doesNotContain("publish", "complete", "cleanup");
    }

    private static RedisProCacheProperties localOnlyProperties() {
        RedisProCacheProperties properties = new RedisProCacheProperties();
        properties.getSyncLock().setLocalOnly(true);
        return properties;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch did not count down within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting latch", e);
        }
    }

    private static final class RecordingState implements SyncStateAccess {
        private final List<String> events = new ArrayList<>();
        private final CountDownLatch completionObserved = new CountDownLatch(1);
        private final CompletableFuture<Object> future = new CompletableFuture<>();
        private final SyncRegistration registration = new SyncRegistration("lifecycle-key", future, true);

        @Override
        public boolean isReentrant(String key) {
            return false;
        }

        @Override
        public void enter(String key) {
            events.add("enter");
        }

        @Override
        public void exit(String key) {
            events.add("exit");
        }

        @Override
        public SyncRegistration publish(String key) {
            events.add("publish");
            return registration;
        }

        @Override
        public void complete(SyncRegistration published, Object value, Throwable failure) {
            events.add("complete");
            completionObserved.countDown();
            if (failure == null) {
                published.future().complete(value);
            } else {
                published.future().completeExceptionally(failure);
            }
        }

        @Override
        public void cleanup(SyncRegistration published) {
            events.add("cleanup");
        }

        @Override
        public <T> T executeLocalOnly(String key, SyncLockTimeout.Resolved timeout,
                                      Supplier<T> work) {
            return work.get();
        }
    }
}
