package io.github.davidhlp.spring.cache.redis.chain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ObserverRegistry 契约测试 — 验证泛型 observer 列表管理的单一 seam(ADR-0016).
 *
 * <p>本测试只覆盖 registry 自身行为:add / snapshot / forEach / size / 线程安全.
 * 引擎层(ChainEngine / AnnotationChainEngine)的委派正确性由各自测试覆盖
 * (ChainEngineTest.addNullObserver_throws / AnnotationChainEngineTest.addObserver_null_throws).
 */
@DisplayName("ObserverRegistry 契约")
class ObserverRegistryTest {

    private ObserverRegistry<String> registry;

    @BeforeEach
    void setUp() {
        registry = new ObserverRegistry<>();
    }

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("add(observer) — 注册后 size 加 1")
        void addIncreasesSize() {
            assertThat(registry.size()).isZero();
            registry.add("h1");
            assertThat(registry.size()).isEqualTo(1);
            registry.add("h2");
            assertThat(registry.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("add(null) — 抛 IllegalArgumentException")
        void addNullThrows() {
            assertThatThrownBy(() -> registry.add(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("observer must not be null");
        }

        @Test
        @DisplayName("add 重复同名 observer — 不去重(由调用方负责)")
        void addDuplicateAllowsDups() {
            registry.add("dup");
            registry.add("dup");
            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.snapshot()).containsExactly("dup", "dup");
        }
    }

    @Nested
    @DisplayName("snapshot")
    class Snapshot {

        @Test
        @DisplayName("snapshot() — 返回当前状态的不可变副本")
        void snapshotIsImmutable() {
            registry.add("a");
            registry.add("b");
            List<String> snap1 = registry.snapshot();
            assertThat(snap1).containsExactly("a", "b");
            // 后续 add 不影响已返回的快照(参考 List.copyOf 语义)
            registry.add("c");
            assertThat(snap1).containsExactly("a", "b");
            // 新 snapshot 反映最新状态
            assertThat(registry.snapshot()).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("snapshot() — 空 registry 返回空 list")
        void snapshotOfEmpty() {
            assertThat(registry.snapshot()).isEmpty();
        }
    }

    @Nested
    @DisplayName("forEach")
    class ForEach {

        @Test
        @DisplayName("forEach(action) — 遍历每个 observer,顺序与 add 顺序一致")
        void forEachPreservesOrder() {
            registry.add("first");
            registry.add("second");
            registry.add("third");

            List<String> visited = new ArrayList<>();
            registry.forEach(visited::add);

            assertThat(visited).containsExactly("first", "second", "third");
        }

        @Test
        @DisplayName("forEach(action) — 空 registry 不触发 action")
        void forEachOnEmpty() {
            AtomicInteger calls = new AtomicInteger();
            registry.forEach(o -> calls.incrementAndGet());
            assertThat(calls).hasValue(0);
        }
    }

    @Nested
    @DisplayName("线程安全")
    class Concurrency {

        @Test
        @DisplayName("add / forEach 并发 — 不抛 ConcurrentModificationException")
        void concurrentAddAndForEach() throws InterruptedException {
            int writerCount = 4;
            int readerCount = 4;
            int iterationsPerWriter = 500;
            int iterationsPerReader = 500;

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(writerCount + readerCount);
            AtomicInteger readerFailures = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(writerCount + readerCount);

            for (int w = 0; w < writerCount; w++) {
                final int writerId = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterationsPerWriter; i++) {
                            registry.add("w" + writerId + "-i" + i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            for (int r = 0; r < readerCount; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterationsPerReader; i++) {
                            try {
                                registry.forEach(o -> {
                                    // 读取 observer (此处只校验不抛异常)
                                    assert o != null;
                                });
                            } catch (RuntimeException ex) {
                                readerFailures.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            // COW 弱一致性迭代保证不抛 CME; reader 失败数应为 0
            assertThat(readerFailures).hasValue(0);
            // 总 add 数应等于 writerCount * iterationsPerWriter
            assertThat(registry.size()).isEqualTo(writerCount * iterationsPerWriter);
        }
    }
}
