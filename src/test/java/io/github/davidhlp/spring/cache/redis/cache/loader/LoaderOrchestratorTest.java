package io.github.davidhlp.spring.cache.redis.cache.loader;

import io.github.davidhlp.spring.cache.redis.cache.loader.LoaderOrchestrator.BloomShortCircuited;
import io.github.davidhlp.spring.cache.redis.cache.loader.LoaderOrchestrator.LoadFailed;
import io.github.davidhlp.spring.cache.redis.cache.loader.LoaderOrchestrator.LoadOutcome;
import io.github.davidhlp.spring.cache.redis.cache.loader.LoaderOrchestrator.Loaded;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomGate;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomSupport;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncLockTimeout;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LoaderOrchestrator} 单测 — 3 个 seam 测试集。
 *
 * <p>{@code isBloomShortCircuited} / {@code loadValue} / {@code performLockedLoad}
 * 3 个 package-private seam 下沉到 LoaderOrchestrator,通过 {@link LoaderOrchestrator#orchestrate}
 * 公开方法间接覆盖 — 每条 case 路径(bloom 短路 / sync 路由 / default 路由 / 锁内 3 决策)
 * 用 {@link LoadOutcome} 三态断言。
 *
 * <p>测试 seam 形态:orchestrator 接受 {@link RedisCache} 引用 + 4 个 callback
 * (redisKey / doubleCheck / putAfterLoad / defaultLoad),本测试用 Mockito mock RedisCache
 * + 自定义 callback 控制 cache-specific 行为,无 RedisProCache fixture 依赖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LoaderOrchestrator Tests")
class LoaderOrchestratorTest {

    @Mock
    private RedisCacheWriter cacheWriter;

    @Mock
    private RedisCache cache;

    @Mock
    private BloomSupport bloomSupport;

    @Mock
    private SyncSupport syncSupport;

    private RedisCacheConfiguration cacheConfiguration;
    private LoaderOrchestrator orchestrator;
    private String testRedisKey;

    @BeforeEach
    void setUp() {
        cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig();
        orchestrator = new LoaderOrchestrator(
                new BloomGate(bloomSupport),
                syncSupport,
                new SyncLockTimeout(new io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties()));
        testRedisKey = "testCache::key1";
        when(cache.getName()).thenReturn("testCache");
    }

    private RedisCacheableOperation operation(boolean useBloom, boolean sync) {
        return RedisCacheableOperation.builder()
                .name("test-cache")
                .cacheNames("test-cache")
                .useBloomFilter(useBloom)
                .sync(sync)
                .build();
    }

    // ==================== Bloom 短路路径 ====================

    @Nested
    @DisplayName("Bloom Short-Circuit Tests — isBloomShortCircuited 迁移")
    class BloomShortCircuitTests {

        @Test
        @DisplayName("null operation → return BloomShortCircuited never invoked (orchestrator proceeds to default path)")
        void nullOperation_proceedsToDefaultPath() {
            // operation null 时,orchestrator 跳过 bloom 短路走 default load
            Callable<String> loader = () -> "value";
            when(cache.get(eq("key1"), any(Callable.class))).thenReturn("value");

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> cache.get(k),         // double-check
                    (k, v) -> {},              // putAfterLoad
                    (k, l) -> (String) cache.get(k, l),
                    loader,
                    "key1",
                    null);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("value");
            verify(bloomSupport, never()).mightContain(anyString(), anyString());
        }

        @Test
        @DisplayName("bloom disabled on operation → proceeds to default path")
        void bloomDisabled_proceedsToDefaultPath() {
            RedisCacheableOperation op = operation(false, false);
            Callable<String> loader = () -> "value";
            when(cache.get(eq("key1"), any(Callable.class))).thenReturn("value");

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> null,
                    (k, v) -> {},
                    (k, l) -> (String) cache.get(k, l),
                    loader,
                    "key1",
                    op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            verify(bloomSupport, never()).mightContain(anyString(), anyString());
        }

        @Test
        @DisplayName("bloom rejects (mightContain=false) → return BloomShortCircuited, loader never invoked")
        void bloomRejects_returnsBloomShortCircuited() {
            RedisCacheableOperation op = operation(true, false);
            when(bloomSupport.mightContain(eq("testCache"), anyString())).thenReturn(false);

            Callable<String> loader = () -> {
                throw new AssertionError("loader should not be invoked on bloom short-circuit");
            };

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> null,
                    (k, v) -> {},
                    (k, l) -> { throw new AssertionError("defaultLoad should not be invoked"); },
                    loader,
                    "key1",
                    op);

            assertThat(outcome).isInstanceOf(BloomShortCircuited.class);
            verify(cache, never()).get(anyString(), any(Callable.class));
        }

        @Test
        @DisplayName("bloom accepts (mightContain=true) → proceeds to default path, no short-circuit")
        void bloomAccepts_proceedsToDefaultPath() {
            RedisCacheableOperation op = operation(true, false);
            when(bloomSupport.mightContain(eq("testCache"), anyString())).thenReturn(true);
            when(cache.get(eq("key1"), any(Callable.class))).thenReturn("value");

            Callable<String> loader = () -> "value";

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> null,
                    (k, v) -> {},
                    (k, l) -> (String) cache.get(k, l),
                    loader,
                    "key1",
                    op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("value");
        }
    }

    // ==================== Sync 路径(loadValue → executeSyncLoad 迁移) ====================

    @Nested
    @DisplayName("Sync Path Routing Tests — loadValue 迁移")
    class SyncPathRoutingTests {

        @Test
        @DisplayName("sync enabled + syncSupport available → routes to syncSupport.executeSync, returns Loaded")
        void syncEnabled_routesToSyncSupport() {
            RedisCacheableOperation op = operation(false, true);
            when(bloomSupport.mightContain(anyString(), anyString())).thenReturn(true);

            // syncSupport.executeSync 模拟「调 supplier 后返回值」
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class), anyLong()))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            Callable<String> loader = () -> "synced-value";

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> null,
                    (k, v) -> {},
                    (k, l) -> { throw new AssertionError("defaultLoad should not be invoked"); },
                    loader,
                    "key1",
                    op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("synced-value");
            verify(syncSupport).executeSync(anyString(), any(java.util.function.Supplier.class), anyLong());
        }
    }

    // ==================== 锁内 performLockedLoad 3 决策分支 ====================

    @Nested
    @DisplayName("performLockedLoad Tests — single-flight seam 迁移")
    class PerformLockedLoadTests {

        @Test
        @DisplayName("double-check hits → Loaded with cached value, loader never invoked")
        void doubleCheckHit_skipsLoader() {
            RedisCacheableOperation op = operation(false, true);
            Cache.ValueWrapper cached = () -> "cached-value";

            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class), anyLong()))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            Callable<String> loader = () -> {
                throw new AssertionError("loader should not be invoked on cache hit");
            };

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> cached,                    // double-check 命中
                    (k, v) -> {
                        throw new AssertionError("put should not be invoked on cache hit");
                    },
                    (k, l) -> { throw new AssertionError("defaultLoad should not be invoked"); },
                    loader,
                    "key1",
                    op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("cached-value");
        }

        @Test
        @DisplayName("double-check miss + loader returns non-null → Loaded with new value, putAfterLoad invoked")
        void doubleCheckMiss_loaderReturnsValue_putsAfterLoad() {
            RedisCacheableOperation op = operation(false, true);
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class), anyLong()))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            java.util.concurrent.atomic.AtomicReference<Object> putKey = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Object> putValue = new java.util.concurrent.atomic.AtomicReference<>();

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> null,                       // double-check miss
                    (k, v) -> {                      // putAfterLoad 记录
                        putKey.set(k);
                        putValue.set(v);
                    },
                    (k, l) -> { throw new AssertionError("defaultLoad should not be invoked"); },
                    () -> "loaded-value",
                    "key1",
                    op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("loaded-value");
            assertThat(putKey.get()).isEqualTo("key1");
            assertThat(putValue.get()).isEqualTo("loaded-value");
        }

        @Test
        @DisplayName("double-check miss + loader returns null → Loaded with null, putAfterLoad still invoked (null-value caching)")
        void doubleCheckMiss_loaderReturnsNull_putsNull() {
            RedisCacheableOperation op = operation(false, true);
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class), anyLong()))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            java.util.concurrent.atomic.AtomicInteger putCalls = new java.util.concurrent.atomic.AtomicInteger();

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> null,
                    (k, v) -> putCalls.incrementAndGet(),
                    (k, l) -> { throw new AssertionError("defaultLoad should not be invoked"); },
                    () -> null,
                    "key1",
                    op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isNull();
            assertThat(putCalls.get()).isEqualTo(1);  // null 也写回
        }

        @Test
        @DisplayName("loader throws → LoadFailed with Cache.ValueRetrievalException")
        void loaderThrows_wrapsInValueRetrievalException() {
            RedisCacheableOperation op = operation(false, true);
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class), anyLong()))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            Callable<String> loader = () -> {
                throw new RuntimeException("loader failed");
            };

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> null,
                    (k, v) -> {},
                    (k, l) -> { throw new AssertionError("defaultLoad should not be invoked"); },
                    loader,
                    "key1",
                    op);

            assertThat(outcome).isInstanceOf(LoadFailed.class);
            Throwable cause = ((LoadFailed<String>) outcome).cause();
            assertThat(cause).isInstanceOf(Cache.ValueRetrievalException.class);
            assertThat(cause.getCause()).hasMessage("loader failed");
        }
    }

    // ==================== Default load path ====================

    @Nested
    @DisplayName("Default Load Path Tests")
    class DefaultLoadPathTests {

        @Test
        @DisplayName("default path returns Loaded with defaultLoadFn result")
        void defaultPath_returnsLoaded() {
            RedisCacheableOperation op = operation(false, false);
            Callable<String> loader = () -> "default-value";

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> null,
                    (k, v) -> {},
                    (k, l) -> {
                        try {
                            return l.call();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    loader,
                    "key1",
                    op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("default-value");
        }

        @Test
        @DisplayName("default path throws → LoadFailed with cause")
        void defaultPath_throws_returnsLoadFailed() {
            RedisCacheableOperation op = operation(false, false);
            Callable<String> loader = () -> "value";
            RuntimeException boom = new RuntimeException("default failed");

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache",
                    key -> testRedisKey,
                    k -> null,
                    (k, v) -> {},
                    (k, l) -> { throw boom; },
                    loader,
                    "key1",
                    op);

            assertThat(outcome).isInstanceOf(LoadFailed.class);
            assertThat(((LoadFailed<String>) outcome).cause()).isSameAs(boom);
        }
    }
}
