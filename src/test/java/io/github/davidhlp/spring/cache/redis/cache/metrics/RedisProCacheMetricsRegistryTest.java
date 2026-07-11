package io.github.davidhlp.spring.cache.redis.cache.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RedisProCacheMetricsRegistry} 单元测试 — 提案 ADR-0065 写侧 seam 契约验证.
 *
 * <p>本测试独立验证 registry seam 的 6 大契约:
 * <ol>
 *   <li><b>构造期注册</b> — 6 个 metric(3 Timer + 4 Counter)在构造期一次性注册,带 cache tag
 *       和正确描述;{@code MeterRegistry} 缺失时全 6 字段为 null(全 no-op 路径)</li>
 *   <li><b>recordGet timing</b> — 计时 + 返回值透传;null timer 时直接执行 body 不计时</li>
 *   <li><b>recordHit / recordMiss</b> — Counter null-safe 自增;null counter 时静默 no-op</li>
 *   <li><b>recordPut / recordEvict</b> — 计时 + 写/淘汰 counter 自增;null timer 时直接执行 body
 *       但仍自增 counter(行为与原 {@code RedisProCache} 字节等价)</li>
 *   <li><b>recordClear</b> — 计时;无 counter(batch 操作语义)</li>
 *   <li><b>metrics() 快照</b> — 不可变 {@link CacheMetrics} 读取;null counter 对应 0L</li>
 * </ol>
 *
 * <p><b>deletion test 验证</b>:本测试全部聚焦 {@link RedisProCacheMetricsRegistry} 自身,
 * 不依赖 {@link RedisProCache} — 若 seam 删掉,这些测试仍可作为 {@code RedisProCache} 内联 metric
 * 代码的契约锚点存在,验证其行为对齐原 {@code RedisProCache} 散落样板。
 */
@DisplayName("RedisProCacheMetricsRegistry Tests")
class RedisProCacheMetricsRegistryTest {

    private static final String CACHE_NAME = "userCache";
    private static final String CACHE_TAG = "cache";

    private MeterRegistry meterRegistry;
    private RedisProCacheMetricsRegistry registry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        registry = new RedisProCacheMetricsRegistry(meterRegistry, CACHE_NAME);
    }

    // ==================== 构造期注册 ====================

    @Nested
    @DisplayName("Constructor Registration Tests")
    class ConstructorRegistrationTests {

        @Test
        @DisplayName("null MeterRegistry → registry 仍可构造,所有 record 走 no-op 路径")
        void nullMeterRegistry_constructsEmptyRegistry() {
            RedisProCacheMetricsRegistry emptyRegistry =
                    new RedisProCacheMetricsRegistry(null, CACHE_NAME);

            // metrics() 快照:全部 0L(null counter → 0L 语义)
            CacheMetrics snapshot = emptyRegistry.metrics();
            assertThat(snapshot.hitCount()).isZero();
            assertThat(snapshot.missCount()).isZero();
            assertThat(snapshot.putCount()).isZero();
            assertThat(snapshot.evictCount()).isZero();
            // cacheName 仍可读(用于调试 / 日志)
            assertThat(emptyRegistry.cacheName()).isEqualTo(CACHE_NAME);
        }

        @Test
        @DisplayName("non-null registry 注册 3 Timer + 4 Counter,均带 cache tag")
        void nonNullRegistry_registersAllMetricsWithTag() {
            // 3 Timer:get / put / evict
            Timer getTimer = meterRegistry.find("resicache.cache.get")
                    .tag(CACHE_TAG, CACHE_NAME).timer();
            Timer putTimer = meterRegistry.find("resicache.cache.put")
                    .tag(CACHE_TAG, CACHE_NAME).timer();
            Timer evictTimer = meterRegistry.find("resicache.cache.evict")
                    .tag(CACHE_TAG, CACHE_NAME).timer();
            assertThat(getTimer).as("getTimer registered").isNotNull();
            assertThat(putTimer).as("putTimer registered").isNotNull();
            assertThat(evictTimer).as("evictTimer registered").isNotNull();

            // 4 Counter:hit / miss / put.count / evict.count
            Counter hitCounter = meterRegistry.find("resicache.cache.hit")
                    .tag(CACHE_TAG, CACHE_NAME).counter();
            Counter missCounter = meterRegistry.find("resicache.cache.miss")
                    .tag(CACHE_TAG, CACHE_NAME).counter();
            Counter putCounter = meterRegistry.find("resicache.cache.put.count")
                    .tag(CACHE_TAG, CACHE_NAME).counter();
            Counter evictCounter = meterRegistry.find("resicache.cache.evict.count")
                    .tag(CACHE_TAG, CACHE_NAME).counter();
            assertThat(hitCounter).as("hitCounter registered").isNotNull();
            assertThat(missCounter).as("missCounter registered").isNotNull();
            assertThat(putCounter).as("putCounter registered").isNotNull();
            assertThat(evictCounter).as("evictCounter registered").isNotNull();
        }

        @Test
        @DisplayName("每个 metric 携带 description(用于 Micrometer exposition)")
        void metricsHaveDescriptions() {
            Timer getTimer = meterRegistry.find("resicache.cache.get")
                    .tag(CACHE_TAG, CACHE_NAME).timer();
            Counter hitCounter = meterRegistry.find("resicache.cache.hit")
                    .tag(CACHE_TAG, CACHE_NAME).counter();

            assertThat(getTimer.getId().getDescription()).isEqualTo("Time spent getting cache entries");
            assertThat(hitCounter.getId().getDescription()).isEqualTo("Cache hit count");
        }

        @Test
        @DisplayName("同名构造不会重复注册 — Micrometer 幂等返回同一实例")
        void doubleConstruction_isIdempotent() {
            // 第二次构造同名 registry → Micrometer 复用既有 metric(同名同 tag 幂等)
            RedisProCacheMetricsRegistry registry2 =
                    new RedisProCacheMetricsRegistry(meterRegistry, CACHE_NAME);

            // 6 metric 仍各 1 个(无重复)
            assertThat(meterRegistry.getMeters().stream()
                    .filter(m -> m.getId().getTag(CACHE_TAG) != null
                            && CACHE_NAME.equals(m.getId().getTag(CACHE_TAG)))
                    .count()).isEqualTo(7L);
            // registry2 引用与 registry 引用不同的 Counter 实例 — 但底层 Micrometer 同一
            // (registry2 持有的 Counter 引用也指向相同 Micrometer 实例,行为等价)
            assertThat(registry2.metrics().hitCount()).isZero();
        }
    }

    // ==================== recordGet ====================

    @Nested
    @DisplayName("recordGet Tests")
    class RecordGetTests {

        @Test
        @DisplayName("non-null timer:body 执行 + timer count 自增 + 返回值透传")
        void nonNullTimer_recordsAndReturns() {
            Timer timer = meterRegistry.find("resicache.cache.get")
                    .tag(CACHE_TAG, CACHE_NAME).timer();
            AtomicInteger invocations = new AtomicInteger();

            String result = registry.recordGet(() -> {
                invocations.incrementAndGet();
                return "result-value";
            });

            assertThat(result).isEqualTo("result-value");
            assertThat(invocations.get()).isEqualTo(1);
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("null registry (empty registry):直接执行 body,无 NPE,无 timer 记录")
        void nullRegistry_executesBodySilently() {
            RedisProCacheMetricsRegistry emptyRegistry =
                    new RedisProCacheMetricsRegistry(null, CACHE_NAME);
            AtomicInteger invocations = new AtomicInteger();

            String result = emptyRegistry.recordGet(() -> {
                invocations.incrementAndGet();
                return "ok";
            });

            assertThat(result).isEqualTo("ok");
            assertThat(invocations.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("body 抛异常 — 异常透传,timer 仍记录(原 try-finally 字节级等价)")
        void bodyException_propagatesAndRecords() {
            Timer timer = meterRegistry.find("resicache.cache.get")
                    .tag(CACHE_TAG, CACHE_NAME).timer();

            assertThatThrownBy(() -> registry.recordGet(() -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");

            assertThat(timer.count()).isEqualTo(1);
        }
    }

    // ==================== recordHit / recordMiss ====================

    @Nested
    @DisplayName("recordHit / recordMiss Tests")
    class HitMissCounterTests {

        @Test
        @DisplayName("recordHit 自增 hit counter;recordMiss 自增 miss counter(互不干扰)")
        void hitMissIncrementDistinctCounters() {
            Counter hit = meterRegistry.find("resicache.cache.hit")
                    .tag(CACHE_TAG, CACHE_NAME).counter();
            Counter miss = meterRegistry.find("resicache.cache.miss")
                    .tag(CACHE_TAG, CACHE_NAME).counter();

            registry.recordHit();
            registry.recordHit();
            registry.recordMiss();

            assertThat(hit.count()).isEqualTo(2.0);
            assertThat(miss.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("null registry — recordHit / recordMiss 静默 no-op,不抛 NPE")
        void nullRegistry_hitMissNoOp() {
            RedisProCacheMetricsRegistry emptyRegistry =
                    new RedisProCacheMetricsRegistry(null, CACHE_NAME);

            // 必须不抛 NPE
            emptyRegistry.recordHit();
            emptyRegistry.recordMiss();
            emptyRegistry.recordHit();
        }
    }

    // ==================== recordPut / recordEvict ====================

    @Nested
    @DisplayName("recordPut / recordEvict Tests")
    class RecordPutEvictTests {

        @Test
        @DisplayName("recordPut:body 执行 + putTimer count 自增 + putCounter 自增")
        void recordPut_recordsTimerAndCounter() {
            Timer putTimer = meterRegistry.find("resicache.cache.put")
                    .tag(CACHE_TAG, CACHE_NAME).timer();
            Counter putCounter = meterRegistry.find("resicache.cache.put.count")
                    .tag(CACHE_TAG, CACHE_NAME).counter();
            AtomicInteger invocations = new AtomicInteger();

            registry.recordPut(() -> invocations.incrementAndGet());

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(putTimer.count()).isEqualTo(1);
            assertThat(putCounter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("recordEvict:body 执行 + evictTimer count 自增 + evictCounter 自增")
        void recordEvict_recordsTimerAndCounter() {
            Timer evictTimer = meterRegistry.find("resicache.cache.evict")
                    .tag(CACHE_TAG, CACHE_NAME).timer();
            Counter evictCounter = meterRegistry.find("resicache.cache.evict.count")
                    .tag(CACHE_TAG, CACHE_NAME).counter();
            AtomicInteger invocations = new AtomicInteger();

            registry.recordEvict(() -> invocations.incrementAndGet());

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(evictTimer.count()).isEqualTo(1);
            assertThat(evictCounter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("recordPut body 抛异常 — 异常透传,timer + counter 仍记录(原 try-finally 字节级等价)")
        void recordPut_bodyException_propagatesAndRecords() {
            Timer putTimer = meterRegistry.find("resicache.cache.put")
                    .tag(CACHE_TAG, CACHE_NAME).timer();
            Counter putCounter = meterRegistry.find("resicache.cache.put.count")
                    .tag(CACHE_TAG, CACHE_NAME).counter();

            assertThatThrownBy(() -> registry.recordPut(() -> {
                throw new RuntimeException("put boom");
            })).isInstanceOf(RuntimeException.class)
                    .hasMessage("put boom");

            assertThat(putTimer.count()).isEqualTo(1);
            assertThat(putCounter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("null registry — recordPut/recordEvict 走 fallback 路径(body 执行 + counter 仍尝试自增,null counter no-op)")
        void nullRegistry_putEvictFallback() {
            RedisProCacheMetricsRegistry emptyRegistry =
                    new RedisProCacheMetricsRegistry(null, CACHE_NAME);
            AtomicInteger invocations = new AtomicInteger();

            // body 必然执行
            emptyRegistry.recordPut(() -> invocations.incrementAndGet());
            emptyRegistry.recordEvict(() -> invocations.incrementAndGet());

            assertThat(invocations.get()).isEqualTo(2);
        }
    }

    // ==================== recordClear ====================

    @Nested
    @DisplayName("recordClear Tests")
    class RecordClearTests {

        @Test
        @DisplayName("recordClear:body 执行 + evictTimer count 自增(无 counter,batch 操作)")
        void recordClear_recordsTimerOnly() {
            Timer evictTimer = meterRegistry.find("resicache.cache.evict")
                    .tag(CACHE_TAG, CACHE_NAME).timer();
            Counter evictCounter = meterRegistry.find("resicache.cache.evict.count")
                    .tag(CACHE_TAG, CACHE_NAME).counter();
            AtomicInteger invocations = new AtomicInteger();

            registry.recordClear(() -> invocations.incrementAndGet());

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(evictTimer.count()).isEqualTo(1);
            // clear 不自增 evict counter(语义区分:evict counter 只计 per-key 删除,不计 batch clear)
            assertThat(evictCounter.count()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("null registry — recordClear 直接执行 body,无 timer 记录")
        void nullRegistry_clearSilent() {
            RedisProCacheMetricsRegistry emptyRegistry =
                    new RedisProCacheMetricsRegistry(null, CACHE_NAME);
            AtomicInteger invocations = new AtomicInteger();

            emptyRegistry.recordClear(() -> invocations.incrementAndGet());

            assertThat(invocations.get()).isEqualTo(1);
        }
    }

    // ==================== metrics() 快照 ====================

    @Nested
    @DisplayName("metrics() snapshot Tests")
    class MetricsSnapshotTests {

        @Test
        @DisplayName("fresh registry → 全部 4 counter 为 0L,hitRate() = 0.0")
        void freshRegistry_zeroSnapshot() {
            CacheMetrics snapshot = registry.metrics();

            assertThat(snapshot.hitCount()).isZero();
            assertThat(snapshot.missCount()).isZero();
            assertThat(snapshot.putCount()).isZero();
            assertThat(snapshot.evictCount()).isZero();
            assertThat(snapshot.hitRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("recordHit × 2 + recordMiss × 1 → hitRate = 2/3 ≈ 0.6667")
        void hitMissRatio_isCalculated() {
            registry.recordHit();
            registry.recordHit();
            registry.recordMiss();

            CacheMetrics snapshot = registry.metrics();

            assertThat(snapshot.hitCount()).isEqualTo(2L);
            assertThat(snapshot.missCount()).isEqualTo(1L);
            assertThat(snapshot.hitRate()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("recordPut + recordEvict 反映在 put/evict count,不影响 hit/miss")
        void putEvictCounterIsolated() {
            registry.recordPut(() -> { });
            registry.recordPut(() -> { });
            registry.recordEvict(() -> { });

            CacheMetrics snapshot = registry.metrics();

            assertThat(snapshot.putCount()).isEqualTo(2L);
            assertThat(snapshot.evictCount()).isEqualTo(1L);
            assertThat(snapshot.hitCount()).isZero();
            assertThat(snapshot.missCount()).isZero();
        }

        @Test
        @DisplayName("null registry — metrics() 仍返回 0 快照(null counter → 0L 语义)")
        void nullRegistry_zeroSnapshot() {
            RedisProCacheMetricsRegistry emptyRegistry =
                    new RedisProCacheMetricsRegistry(null, CACHE_NAME);

            CacheMetrics snapshot = emptyRegistry.metrics();

            assertThat(snapshot.hitCount()).isZero();
            assertThat(snapshot.missCount()).isZero();
            assertThat(snapshot.putCount()).isZero();
            assertThat(snapshot.evictCount()).isZero();
        }

        @Test
        @DisplayName("metrics() 不可变 — 修改原 counter 后,旧 snapshot 数值不变(引用语义)")
        void metricsSnapshot_isImmutable() {
            registry.recordHit();
            CacheMetrics before = registry.metrics();

            registry.recordHit();  // 自增 hit
            registry.recordHit();

            // 旧 snapshot 仍为初次读取时的数值(2L — 第二次 recordHit 前)
            assertThat(before.hitCount()).isEqualTo(1L);
            // 新 snapshot 反映最新值
            assertThat(registry.metrics().hitCount()).isEqualTo(3L);
        }
    }

    // ==================== 集成场景 ====================

    @Nested
    @DisplayName("Integration scenario")
    class IntegrationScenario {

        @Test
        @DisplayName("典型 cache 生命周期:1 hit + 1 miss + 1 put + 1 evict → snapshot 全反映")
        void typicalCacheLifecycle_reflectedInSnapshot() {
            // get hit(假设 cache 命中)
            registry.recordGet(() -> "hit-value");
            // get miss(假设 cache 未命中)
            registry.recordGet(() -> null);
            // put(回填 miss)
            registry.recordPut(() -> { });
            // evict(淘汰某个 key)
            registry.recordEvict(() -> { });

            CacheMetrics snapshot = registry.metrics();

            // hit counter 未自增(本测试只调 recordGet,hit/miss 由调用方记)
            // 注:recordGet 仅 record timer,不 record hit/miss 计数 — 此为 seam 设计
            // (hit/miss 判定依赖 cache 返回值类型,seam 不感知)
            assertThat(snapshot.hitCount()).isZero();
            assertThat(snapshot.missCount()).isZero();
            assertThat(snapshot.putCount()).isEqualTo(1L);
            assertThat(snapshot.evictCount()).isEqualTo(1L);

            // explicit hit/miss(模拟调用方根据返回值的判定)
            registry.recordHit();
            registry.recordMiss();

            CacheMetrics afterHitMiss = registry.metrics();
            assertThat(afterHitMiss.hitCount()).isEqualTo(1L);
            assertThat(afterHitMiss.missCount()).isEqualTo(1L);
            assertThat(afterHitMiss.hitRate()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("多 cache 名称 → metric 按 cache tag 隔离,registry 各自独立")
        void multipleCacheNames_isolatedByTag() {
            MeterRegistry sharedRegistry = new SimpleMeterRegistry();
            RedisProCacheMetricsRegistry userCache =
                    new RedisProCacheMetricsRegistry(sharedRegistry, "userCache");
            RedisProCacheMetricsRegistry orderCache =
                    new RedisProCacheMetricsRegistry(sharedRegistry, "orderCache");

            // userCache 自增 hit,orderCache 自增 miss
            userCache.recordHit();
            userCache.recordHit();
            orderCache.recordMiss();

            // sharedRegistry 应有 14 个 metric(2 cache × 7 metric)
            assertThat(sharedRegistry.getMeters()).hasSize(14);

            // userCache 快照:hit=2, miss=0
            CacheMetrics userSnapshot = userCache.metrics();
            assertThat(userSnapshot.hitCount()).isEqualTo(2L);
            assertThat(userSnapshot.missCount()).isZero();

            // orderCache 快照:hit=0, miss=1
            CacheMetrics orderSnapshot = orderCache.metrics();
            assertThat(orderSnapshot.hitCount()).isZero();
            assertThat(orderSnapshot.missCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Tag 携带 cache 名,可在 Micrometer 中按 cache 维度切片")
        void metricsExposeCacheTag() {
            // 验证 tag 值是 CACHE_NAME 而非 null/empty
            Timer getTimer = meterRegistry.find("resicache.cache.get").timer();
            assertThat(getTimer).isNotNull();
            String cacheTagValue = getTimer.getId().getTag(CACHE_TAG);
            assertThat(cacheTagValue).isNotNull();
            assertThat(cacheTagValue).isEqualTo(CACHE_NAME);
        }
    }
}
