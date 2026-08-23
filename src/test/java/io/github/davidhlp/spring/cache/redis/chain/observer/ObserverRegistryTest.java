package io.github.davidhlp.spring.cache.redis.chain.observer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ObserverRegistry 契约测试 — 验证 observer 列表管理的单一 seam.
 *
 * <p>本测试只覆盖 registry 自身行为:add / snapshot / size / 线程安全.
 * 引擎层(ChainEngine)的委派正确性由 {@code ChainEngineTest.addNullObserver_throws} 覆盖.
 *
 * <p>Wave 3 TASK-018:ObserverRegistry 特化为 {@link ChainObserver} 后,测试用
 * 匿名 ChainObserver 实例(全部 default no-op 方法)替代旧的 {@code String} 泛型占位。
 */
@DisplayName("ObserverRegistry 契约")
class ObserverRegistryTest {

    private ObserverRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ObserverRegistry();
    }

    /** 便捷工厂:产出一个全 default no-op 的 ChainObserver 实例. */
    private static ChainObserver observer() {
        return new ChainObserver() {
        };
    }

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("add(observer) — 注册后 size 加 1")
        void addIncreasesSize() {
            assertThat(registry.size()).isZero();
            registry.add(observer());
            assertThat(registry.size()).isEqualTo(1);
            registry.add(observer());
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
            ChainObserver a = observer();
            ChainObserver b = observer();
            registry.add(a);
            registry.add(b);
            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.snapshot()).containsExactly(a, b);
        }
    }

    @Nested
    @DisplayName("snapshot")
    class Snapshot {

        @Test
        @DisplayName("snapshot() — 返回当前状态的不可变副本")
        void snapshotIsImmutable() {
            ChainObserver a = observer();
            ChainObserver b = observer();
            ChainObserver c = observer();
            registry.add(a);
            registry.add(b);
            List<ChainObserver> snap1 = registry.snapshot();
            assertThat(snap1).containsExactly(a, b);
            // 后续 add 不影响已返回的快照(参考 List.copyOf 语义)
            registry.add(c);
            assertThat(snap1).containsExactly(a, b);
            // 新 snapshot 反映最新状态
            assertThat(registry.snapshot()).containsExactly(a, b, c);
        }

        @Test
        @DisplayName("snapshot() — 空 registry 返回空 list")
        void snapshotOfEmpty() {
            assertThat(registry.snapshot()).isEmpty();
        }
    }

    @Nested
    @DisplayName("线程安全")
    class Concurrency {

        @Test
        @DisplayName("add / snapshot 并发 — 不抛 ConcurrentModificationException")
        void concurrentAddAndSnapshot() throws InterruptedException {
            int writerCount = 4;
            int readerCount = 4;
            int iterationsPerWriter = 500;
            int iterationsPerReader = 500;

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(writerCount + readerCount);
            AtomicInteger readerFailures = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(writerCount + readerCount);

            for (int w = 0; w < writerCount; w++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterationsPerWriter; i++) {
                            registry.add(observer());
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
                                // snapshot() 是 registry 真正剩余的遍历表面:
                                // List.copyOf(CopyOnWriteArrayList) 在并发 add 下不抛 CME
                                for (ChainObserver o : registry.snapshot()) {
                                    assert o != null;
                                }
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

            // COW 弱一致性 + List.copyOf 保证不抛 CME; reader 失败数应为 0
            assertThat(readerFailures).hasValue(0);
            // 总 add 数应等于 writerCount * iterationsPerWriter
            assertThat(registry.size()).isEqualTo(writerCount * iterationsPerWriter);
        }
    }
}
