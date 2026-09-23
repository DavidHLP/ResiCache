package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.cache.LoaderOrchestrator.BloomShortCircuited;
import io.github.davidhlp.spring.cache.redis.cache.LoaderOrchestrator.LoadFailed;
import io.github.davidhlp.spring.cache.redis.cache.LoaderOrchestrator.LoadOutcome;
import io.github.davidhlp.spring.cache.redis.cache.LoaderOrchestrator.Loaded;
import io.github.davidhlp.spring.cache.redis.cache.LoaderOrchestrator.LoadedWithWriteBackFailure;
import io.github.davidhlp.spring.cache.redis.cache.SyncLockTimeout.Resolved;
import io.github.davidhlp.spring.cache.redis.chain.model.CachePolicyView;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link LoaderOrchestrator} 单测 — 3 个 seam 测试集。
 *
 * <p>{@code isBloomShortCircuited} / {@code readThrough} 等 package-private seam 下沉到
 * LoaderOrchestrator,通过唯一实例入口 {@link LoaderOrchestrator#orchestrate} 四参方法间接覆盖 —
 * 每条 case 路径(bloom 短路 / sync 路由 / default 路由 / load 协议决策)
 * 用 {@link LoadOutcome} 各态断言。
 *
 * <p>测试装配与生产同形:每个用例经六参构造器绑定自身 callback
 * (redisKey / doubleCheck / putAfterLoad)后走四参 {@code orchestrate},
 * 本测试直接控制 cache-specific 行为,无 RedisProCache fixture 依赖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LoaderOrchestrator Tests")
class LoaderOrchestratorTest {

    @Mock
    private BloomSupport bloomSupport;

    @Mock
    private SyncSupport syncSupport;

    private BloomGate bloomGate;
    private SyncLockTimeout syncLockTimeout;
    private LoaderOrchestrator orchestrator;
    private String testRedisKey;

    @BeforeEach
    void setUp() {
        bloomGate = new BloomGate(bloomSupport);
        syncLockTimeout = new SyncLockTimeout(new RedisProCacheProperties());
        testRedisKey = "testCache::key1";
        orchestrator = bound(key -> null, (key, value) -> { });
    }

    private RedisCacheableOperation operation(boolean useBloom, boolean sync) {
        return RedisCacheableOperation.builder()
                .name("test-cache")
                .cacheNames("test-cache")
                .useBloomFilter(useBloom)
                .sync(sync)
                .build();
    }

    /** 构造绑定实例:redis key 派生自 {@link #testRedisKey} fixture。 */
    private LoaderOrchestrator bound(Function<Object, Cache.ValueWrapper> doubleCheckFn,
                                     BiConsumer<Object, Object> putAfterLoad) {
        return new LoaderOrchestrator(bloomGate, syncSupport, syncLockTimeout,
                key -> testRedisKey, doubleCheckFn, putAfterLoad);
    }

    // ==================== Bloom 短路路径 ====================

    @Nested
    @DisplayName("Bloom Short-Circuit Tests — isBloomShortCircuited 迁移")
    class BloomShortCircuitTests {

        @Test
        @DisplayName("null operation → return BloomShortCircuited never invoked (orchestrator proceeds to default path)")
        void nullOperation_proceedsToDefaultPath() {
            // operation null 时,orchestrator 跳过 bloom 短路走 default load 协议
            Callable<String> loader = () -> "value";

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache", loader, "key1", null);

            assertThat(((Loaded<String>) outcome).value()).isEqualTo("value");
        }

        @Test
        @DisplayName("bloom disabled on operation → proceeds to default path")
        void bloomDisabled_proceedsToDefaultPath() {
            RedisCacheableOperation op = operation(false, false);
            Callable<String> loader = () -> "value";

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache", loader, "key1", op);
            assertThat(outcome).isInstanceOf(Loaded.class);
        }

        @Test
        @DisplayName("bloom rejects (mightContain=false) → return BloomShortCircuited, loader/write-back never invoked")
        void bloomRejects_returnsBloomShortCircuited() {
            RedisCacheableOperation op = operation(true, false);
            when(bloomSupport.mightContain(eq("testCache"), anyString())).thenReturn(false);

            AtomicInteger putCalls = new AtomicInteger();
            Callable<String> loader = () -> {
                throw new AssertionError("loader should not be invoked on bloom short-circuit");
            };

            LoadOutcome<String> outcome = bound(key -> null, (key, value) -> putCalls.incrementAndGet())
                    .orchestrate("testCache", loader, "key1", op);
            assertThat(outcome).isInstanceOf(BloomShortCircuited.class);
            assertThat(putCalls.get())
                    .as("bloom 短路必须发生在 loader 与写回之前")
                    .isZero();
        }

        @Test
        @DisplayName("bloom accepts (mightContain=true) → proceeds to default path, no short-circuit")
        void bloomAccepts_proceedsToDefaultPath() {
            RedisCacheableOperation op = operation(true, false);
            when(bloomSupport.mightContain(eq("testCache"), anyString())).thenReturn(true);

            Callable<String> loader = () -> "value";

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache", loader, "key1", op);

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
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class),
                    any(SyncLockTimeout.Resolved.class)))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            Callable<String> loader = () -> "synced-value";

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache", loader, "key1", op);

            assertThat(((Loaded<String>) outcome).value()).isEqualTo("synced-value");
        }

        @Test
        @DisplayName("sync read resolves once and reuses one Resolved timeout through SyncSupport")
        void syncRead_resolvesTimeoutOnceAndReusesResolvedTimeout() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            properties.getSyncLock().setLocalOnly(true);
            RecordingSyncLockTimeout timeout = new RecordingSyncLockTimeout(properties);
            RecordingSyncSupport support = new RecordingSyncSupport(properties);
            LoaderOrchestrator underTest = new LoaderOrchestrator(
                    new BloomGate(bloomSupport), support, timeout,
                    key -> testRedisKey, key -> null, (key, value) -> { });

            LoadOutcome<String> outcome = underTest.orchestrate(
                    "testCache", () -> "synced-value", "key1", operation(false, true));

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("synced-value");
            assertThat(timeout.resolutionCount()).isEqualTo(1);
            assertThat(support.seenTimeout()).isSameAs(timeout.resolvedTimeout());
        }

    }

    // ==================== load 协议决策分支(sync 路径:锁内执行) ====================

    @Nested
    @DisplayName("readThrough Tests — sync 路径锁内执行")
    class ReadThroughLockedLoadTests {

        @Test
        @DisplayName("double-check hits → Loaded with cached value, loader never invoked")
        void doubleCheckHit_skipsLoader() {
            RedisCacheableOperation op = operation(false, true);
            Cache.ValueWrapper cached = () -> "cached-value";

            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class),
                    any(SyncLockTimeout.Resolved.class)))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            Callable<String> loader = () -> {
                throw new AssertionError("loader should not be invoked on cache hit");
            };

            LoadOutcome<String> outcome = bound(key -> cached, (key, value) -> {
                throw new AssertionError("put should not be invoked on cache hit");
            }).orchestrate("testCache", loader, "key1", op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("cached-value");
        }

        @Test
        @DisplayName("double-check miss + loader returns non-null → Loaded with new value, putAfterLoad invoked")
        void doubleCheckMiss_loaderReturnsValue_putsAfterLoad() {
            RedisCacheableOperation op = operation(false, true);
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class),
                    any(SyncLockTimeout.Resolved.class)))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            AtomicReference<Object> readKey = new AtomicReference<>();
            AtomicReference<Object> putKey = new AtomicReference<>();
            AtomicReference<Object> putValue = new AtomicReference<>();

            LoadOutcome<String> outcome = bound(
                    key -> {
                        readKey.set(key);
                        return null;                     // double-check miss
                    },
                    (key, value) -> {                    // putAfterLoad 记录
                        putKey.set(key);
                        putValue.set(value);
                    })
                    .orchestrate("testCache", () -> "loaded-value", "key1", op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("loaded-value");
            assertThat(readKey.get()).isEqualTo("key1");
            assertThat(putKey.get()).isEqualTo("key1");
            assertThat(putValue.get()).isEqualTo("loaded-value");
        }

        @Test
        @DisplayName("double-check miss + loader returns null → Loaded with null, putAfterLoad still invoked (null-value caching)")
        void doubleCheckMiss_loaderReturnsNull_putsNull() {
            RedisCacheableOperation op = operation(false, true);
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class),
                    any(SyncLockTimeout.Resolved.class)))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            AtomicInteger putCalls = new AtomicInteger();

            LoadOutcome<String> outcome = bound(key -> null, (key, value) -> putCalls.incrementAndGet())
                    .orchestrate("testCache", () -> null, "key1", op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isNull();
            assertThat(putCalls.get()).isEqualTo(1);  // null 也写回
        }

        @Test
        @DisplayName("loader throws → LoadFailed with Cache.ValueRetrievalException")
        void loaderThrows_wrapsInValueRetrievalException() {
            RedisCacheableOperation op = operation(false, true);
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class),
                    any(SyncLockTimeout.Resolved.class)))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            Callable<String> loader = () -> {
                throw new RuntimeException("loader failed");
            };

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache", loader, "key1", op);

            assertThat(outcome).isInstanceOf(LoadFailed.class);
            Throwable cause = ((LoadFailed<String>) outcome).cause();
            assertThat(cause).isInstanceOf(Cache.ValueRetrievalException.class);
            assertThat(cause.getCause()).hasMessage("loader failed");
        }

        @Test
        @DisplayName("write-back fails after loader success → LoadedWithWriteBackFailure carrying value + cause")
        void writeBackFails_returnsLoadedWithWriteBackFailure() {
            RedisCacheableOperation op = operation(false, true);
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class),
                    any(SyncLockTimeout.Resolved.class)))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            RuntimeException putBoom = new RuntimeException("redis put failed");

            LoadOutcome<String> outcome = bound(key -> null, (key, value) -> {
                throw putBoom;                       // putAfterLoad 写回失败
            }).orchestrate("testCache", () -> "loaded-value", "key1", op);

            assertThat(outcome).isInstanceOf(LoadedWithWriteBackFailure.class);
            LoadedWithWriteBackFailure<String> wbf = (LoadedWithWriteBackFailure<String>) outcome;
            assertThat(wbf.value())
                    .as("loader 成功值必须保留并返回(写回失败不覆盖)")
                    .isEqualTo("loaded-value");
            assertThat(wbf.cause()).isSameAs(putBoom);
        }

        @Test
        @DisplayName("write-back fails on null loader value → value preserved, still LoadedWithWriteBackFailure")
        void writeBackFails_nullLoadedValue_preserved() {
            RedisCacheableOperation op = operation(false, true);
            when(syncSupport.executeSync(anyString(), any(java.util.function.Supplier.class),
                    any(SyncLockTimeout.Resolved.class)))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<String> supplier = inv.getArgument(1);
                        return supplier.get();
                    });

            RuntimeException putBoom = new RuntimeException("redis put failed");

            LoadOutcome<String> outcome = bound(key -> null, (key, value) -> {
                throw putBoom;
            }).orchestrate("testCache", () -> null, "key1", op);

            assertThat(outcome).isInstanceOf(LoadedWithWriteBackFailure.class);
            LoadedWithWriteBackFailure<String> wbf = (LoadedWithWriteBackFailure<String>) outcome;
            assertThat(wbf.value()).isNull();
            assertThat(wbf.cause()).isSameAs(putBoom);
        }
    }


    // ==================== Default load path(与 sync 路径同一 load 协议) ====================

    @Nested
    @DisplayName("Default Load Path Tests — 同一 load 协议,无分布式锁")
    class DefaultLoadPathTests {

        @Test
        @DisplayName("bound callback constructor exposes a small production call surface")
        void boundCallbacks_productionEntryUsesOnlyLoaderAndKey() {
            LoaderOrchestrator boundOrchestrator = bound(key -> null, (key, value) -> { });

            LoadOutcome<String> outcome = boundOrchestrator.orchestrate(
                    "testCache", () -> "loaded-value", "key1", operation(false, false));

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("loaded-value");
        }

        @Test
        @DisplayName("ValueWrapper 非 null 且值为 null → 命中:不调用 loader、不写回")
        void cachedNullValueWrapperHit_skipsLoaderAndWriteBack() {
            RedisCacheableOperation op = operation(false, false);
            Cache.ValueWrapper nullWrapper = () -> null;
            Callable<String> loader = () -> {
                throw new AssertionError("loader must not run on null-value cache hit");
            };

            LoadOutcome<String> outcome = bound(key -> nullWrapper, (key, value) -> {
                throw new AssertionError("put must not run on null-value cache hit");
            }).orchestrate("testCache", loader, "key1", op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value())
                    .as("wrapper 命中判定不能用业务值是否为 null 代替")
                    .isNull();
        }

        @Test
        @DisplayName("double-check hits → default path returns cached value, loader never invoked")
        void doubleCheckHit_defaultPathSkipsLoader() {
            RedisCacheableOperation op = operation(false, false);
            Cache.ValueWrapper cached = () -> "cached-value";

            LoadOutcome<String> outcome = bound(key -> cached, (key, value) -> {
                throw new AssertionError("put must not run on cache hit");
            }).orchestrate("testCache", () -> {
                throw new AssertionError("loader must not run on cache hit");
            }, "key1", op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("cached-value");
        }

        @Test
        @DisplayName("double-check miss → loader runs, value written back through putAfterLoad")
        void doubleCheckMiss_defaultPathLoadsAndWritesBack() {
            RedisCacheableOperation op = operation(false, false);
            AtomicReference<Object> writtenKey = new AtomicReference<>();
            AtomicReference<Object> written = new AtomicReference<>();

            LoadOutcome<String> outcome = bound(key -> null, (key, value) -> {
                writtenKey.set(key);
                written.set(value);
            }).orchestrate("testCache", () -> "loaded-value", "key1", op);

            assertThat(outcome).isInstanceOf(Loaded.class);
            assertThat(((Loaded<String>) outcome).value()).isEqualTo("loaded-value");
            assertThat(writtenKey.get()).isEqualTo("key1");
            assertThat(written.get())
                    .as("默认载荷路径的写回必须走 putAfterLoad(带 put metrics 的 override)")
                    .isEqualTo("loaded-value");
        }

        @Test
        @DisplayName("loader throws → LoadFailed wrapping Cache.ValueRetrievalException")
        void loaderThrows_defaultPathReturnsLoadFailed() {
            RedisCacheableOperation op = operation(false, false);
            Callable<String> loader = () -> {
                throw new RuntimeException("default failed");
            };

            LoadOutcome<String> outcome = orchestrator.orchestrate(
                    "testCache", loader, "key1", op);

            assertThat(outcome).isInstanceOf(LoadFailed.class);
            Throwable cause = ((LoadFailed<String>) outcome).cause();
            assertThat(cause).isInstanceOf(Cache.ValueRetrievalException.class);
            assertThat(cause.getCause()).hasMessage("default failed");
        }

        @Test
        @DisplayName("写回抛 IllegalArgumentException(配置错误) → LoadFailed 原样携带,不降级为写回容错")
        void writeBackConfigurationError_propagates() {
            RedisCacheableOperation op = operation(false, false);
            IllegalArgumentException misconfiguration = new IllegalArgumentException(
                    "Cache 'testCache' does not allow 'null' values");

            LoadOutcome<String> outcome = bound(key -> null, (key, value) -> {
                throw misconfiguration;
            }).orchestrate("testCache", () -> null, "key1", op);

            assertThat(outcome)
                    .as("null 缓存未启用是配置错误,必须走 LoadFailed(调用方上抛),不是写回容错")
                    .isInstanceOf(LoadFailed.class);
            assertThat(((LoadFailed<String>) outcome).cause()).isSameAs(misconfiguration);
        }

        @Test
        @DisplayName("write-back fails after loader success → LoadedWithWriteBackFailure (same default path)")
        void writeBackFails_defaultPathReturnsLoadedWithWriteBackFailure() {
            RedisCacheableOperation op = operation(false, false);
            RuntimeException putBoom = new RuntimeException("redis put failed");

            LoadOutcome<String> outcome = bound(key -> null, (key, value) -> {
                throw putBoom;
            }).orchestrate("testCache", () -> "loaded-value", "key1", op);

            assertThat(outcome).isInstanceOf(LoadedWithWriteBackFailure.class);
            LoadedWithWriteBackFailure<String> wbf = (LoadedWithWriteBackFailure<String>) outcome;
            assertThat(wbf.value())
                    .as("loader 成功值必须保留并返回(写回失败不覆盖)")
                    .isEqualTo("loaded-value");
            assertThat(wbf.cause()).isSameAs(putBoom);
        }

    }

    // ==================== 构造绑定(六参构造器)校验与跨调用状态 ====================

    @Nested
    @DisplayName("Constructor Binding Tests — 必需回调校验 / 无请求级缓存状态")
    class ConstructorBindingTests {

        private final Function<Object, String> keyFn = key -> testRedisKey;
        private final Function<Object, Cache.ValueWrapper> checkFn = key -> null;
        private final BiConsumer<Object, Object> putFn = (key, value) -> { };

        @Test
        @DisplayName("redisKeyFn 为 null → 构造期抛 NPE,消息指明参数名")
        void nullRedisKeyFn_rejectedAtConstruction() {
            assertThatThrownBy(() -> new LoaderOrchestrator(
                    null, null, null, null, checkFn, putFn))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("redisKeyFn");
        }

        @Test
        @DisplayName("doubleCheckFn 为 null → 构造期抛 NPE,消息指明参数名")
        void nullDoubleCheckFn_rejectedAtConstruction() {
            assertThatThrownBy(() -> new LoaderOrchestrator(
                    null, null, null, keyFn, null, putFn))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("doubleCheckFn");
        }

        @Test
        @DisplayName("putAfterLoad 为 null → 构造期抛 NPE,消息指明参数名")
        void nullPutAfterLoad_rejectedAtConstruction() {
            assertThatThrownBy(() -> new LoaderOrchestrator(
                    null, null, null, keyFn, checkFn, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("putAfterLoad");
        }

        @Test
        @DisplayName("保护协作依赖为 null → 装配错误,构造期抛 NPE(bloomGate)")
        void nullProtectionDeps_rejectedAtConstruction() {
            assertThatThrownBy(() -> new LoaderOrchestrator(null, null, null, keyFn, checkFn, putFn))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("bloomGate");
        }

        @Test
        @DisplayName("同一实例连续 orchestrate 两个 key → 读与写回各自收到正确 key,不缓存请求状态")
        void sameInstanceServesConsecutiveCallsWithDistinctKeys() {
            List<Object> readKeys = new ArrayList<>();
            List<Object> putKeys = new ArrayList<>();
            List<Object> putValues = new ArrayList<>();
            AtomicInteger loads = new AtomicInteger();
            LoaderOrchestrator boundOrchestrator = bound(
                    key -> {
                        readKeys.add(key);
                        return null;
                    },
                    (key, value) -> {
                        putKeys.add(key);
                        putValues.add(value);
                    });
            Callable<String> loader = () -> "v" + loads.incrementAndGet();
            RedisCacheableOperation op = operation(false, false);

            LoadOutcome<String> first = boundOrchestrator.orchestrate("testCache", loader, "keyA", op);
            LoadOutcome<String> second = boundOrchestrator.orchestrate("testCache", loader, "keyB", op);

            assertThat(((Loaded<String>) first).value()).isEqualTo("v1");
            assertThat(((Loaded<String>) second).value()).isEqualTo("v2");
            assertThat(readKeys).containsExactly("keyA", "keyB");
            assertThat(putKeys).containsExactly("keyA", "keyB");
            assertThat(putValues).containsExactly("v1", "v2");
        }
    }

    private static final class RecordingSyncLockTimeout extends SyncLockTimeout {
        private final Resolved resolvedTimeout = Resolved.fromSeconds(37);
        private int resolutionCount;

        private RecordingSyncLockTimeout(RedisProCacheProperties properties) {
            super(properties);
        }

        Resolved resolve(CachePolicyView.Source operation) {
            resolutionCount++;
            return resolvedTimeout;
        }

        private int resolutionCount() {
            return resolutionCount;
        }

        private Resolved resolvedTimeout() {
            return resolvedTimeout;
        }
    }

    private static final class RecordingSyncSupport extends SyncSupport {
        private Resolved seenTimeout;

        private RecordingSyncSupport(RedisProCacheProperties properties) {
            super(java.util.List.of(), properties);
        }

        @Override
        <T> T executeSync(String key, Supplier<T> loader, Resolved timeout) {
            seenTimeout = timeout;
            return super.executeSync(key, loader, timeout);
        }

        private Resolved seenTimeout() {
            return seenTimeout;
        }
    }

}
