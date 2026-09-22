package io.github.davidhlp.spring.cache.redis.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationPhase;
import io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 各失败站点的 key 隐私 thin 覆盖 —— 每个站点断言「一条 WARN/ERROR 里不出现被放进上下文的
 * raw key」。
 *
 * <p>规则本体(配对、指纹、类型链渲染)只由 {@code FailureReportTest} 在 seam 级测一次;本类
 * 不重复枚举 message 文本,只保证每个曾经泄露过 raw key 的站点保留一条会红的守卫 ——
 * 站点把 raw key 拼进自由文本参数时,这里失败。
 *
 * <p>覆盖站点:{@code RedisBloomIFilter}(add/check/delete)、{@code DistributedLockManager}
 * (获取超时 / 中断 / 释放重试与耗尽 / 重试期中断)、{@code ChainEngine} 后置处理(执行与判定)、
 * {@code EarlyRefresh} 异步刷新、{@code SyncRoleLockExecutor} 锁获取与释放、
 * {@code SerializationMigrationEngine} 前向与回滚。
 */
@DisplayName("failure-log key privacy per site")
class FailureLogKeyPrivacyTest {

    /** 必须不出现在 WARN/ERROR 中的原始 key 哨兵。 */
    private static final String SECRET_KEY = "secret-customer-key-42";
    private static final String CACHE = "privacy-cache";
    /** {@code SyncRole.Leader} 的 logger 名(嵌套类在包外不可直接引用)。 */
    private static final String SYNC_ROLE_LEADER_LOGGER = SyncRole.class.getName() + "$Leader";


    @Test
    @DisplayName("RedisBloomIFilter:add/check/delete 失败各一条 ERROR,均不含 raw key")
    @SuppressWarnings("unchecked")
    void redisBloomFilter_failures_omitRawKey() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenThrow(new IllegalStateException("bloom redis boom for key " + SECRET_KEY));
        when(redisTemplate.delete(anyString()))
                .thenThrow(new IllegalStateException("bloom delete boom for key " + SECRET_KEY));

        try (Capture capture = new Capture(RedisBloomIFilter.class)) {
            RedisBloomIFilter filter = new RedisBloomIFilter(
                    redisTemplate, new BloomFilterConfig("bf:", 4096, 3, 64),
                    DisabledMetricsRegistry.INSTANCE);
            filter.init();

            filter.add(CACHE, SECRET_KEY);
            assertThat(filter.mightContain(CACHE, SECRET_KEY))
                    .as("check 失败必须 fail-open")
                    .isTrue();
            filter.clear(CACHE);

            assertThat(capture.warnErrorText())
                    .contains("Bloom filter add failed")
                    .contains("Bloom filter check failed")
                    .contains("Bloom filter delete failed")
                    .contains("cacheName=" + CACHE)
                    .doesNotContain(SECRET_KEY);
        }
    }


    @Test
    @DisplayName("DistributedLockManager:获取超时 WARN 不含 raw key,只含指纹")
    void distributedLockManager_acquireTimeout_omitsRawKey() throws InterruptedException {
        RedisProCacheProperties properties = new RedisProCacheProperties();
        RLock notAcquired = mock(RLock.class);
        when(notAcquired.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        try (Capture capture = new Capture(DistributedLockManager.class)) {
            assertThat(managerWithLock(properties, notAcquired).tryAcquire(SECRET_KEY, 1)).isEmpty();

            assertThat(capture.warnErrorText())
                    .contains("Failed to acquire distributed lock")
                    .contains("keyFingerprint=" + FailureReport.fingerprint(SECRET_KEY))
                    .doesNotContain(SECRET_KEY)
                    .doesNotContain(properties.getSyncLock().getPrefix() + SECRET_KEY);
        }
    }

    @Test
    @DisplayName("DistributedLockManager:等待被中断 ERROR 与异常消息不含 raw key")
    void distributedLockManager_interrupted_omitsRawKey() throws InterruptedException {
        RedisProCacheProperties properties = new RedisProCacheProperties();
        RLock interrupted = mock(RLock.class);
        when(interrupted.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("interrupted while holding " + SECRET_KEY));
        DistributedLockManager manager = managerWithLock(properties, interrupted);

        try (Capture capture = new Capture(DistributedLockManager.class)) {
            try {
                assertThatThrownBy(() -> manager.tryAcquire(SECRET_KEY, 1))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageNotContaining(SECRET_KEY);
            } finally {
                Thread.interrupted();
            }

            assertThat(capture.warnErrorText())
                    .contains("Interrupted while waiting for distributed lock")
                    .doesNotContain(SECRET_KEY);
        }
    }

    @Test
    @DisplayName("DistributedLockManager:释放重试 WARN 与耗尽 ERROR 不含 raw key 与异常 message")
    void distributedLockManager_releaseFailures_omitRawKey() throws InterruptedException {
        try (Capture capture = new Capture(DistributedLockManager.class)) {
            RLock neverUnlocks = heldLock();
            doThrow(new IllegalStateException("unlock failed for key " + SECRET_KEY))
                    .when(neverUnlocks).unlock();

            lockHandleOf(neverUnlocks).close();

            assertThat(capture.warnErrorText())
                    .contains("Failed to release distributed lock on attempt 1")
                    .contains("Failed to release distributed lock after ")
                    .doesNotContain(SECRET_KEY)
                    .doesNotContain("unlock failed for key");
        }
    }

    @Test
    @DisplayName("DistributedLockManager:重试等待期被中断 ERROR 不含 raw key")
    void distributedLockManager_interruptedDuringRetry_omitsRawKey() throws InterruptedException {
        try (Capture capture = new Capture(DistributedLockManager.class)) {
            RLock neverUnlocks = heldLock();
            doThrow(new IllegalStateException("unlock failed for key " + SECRET_KEY))
                    .when(neverUnlocks).unlock();
            LockManager.LockHandle handle = lockHandleOf(neverUnlocks);

            Thread.currentThread().interrupt();
            try {
                handle.close();
            } finally {
                Thread.interrupted();
            }

            assertThat(capture.warnErrorText())
                    .contains("Interrupted while retrying lock release")
                    .doesNotContain(SECRET_KEY);
        }
    }

    private RLock heldLock() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        return lock;
    }

    private LockManager.LockHandle lockHandleOf(RLock lock) throws InterruptedException {
        return managerWithLock(new RedisProCacheProperties(), lock)
                .tryAcquire(SECRET_KEY, 1).orElseThrow();
    }

    private DistributedLockManager managerWithLock(RedisProCacheProperties properties, RLock lock) {
        String lockKey = new DistributedLockManager(mock(RedissonClient.class), properties)
                .buildLockKey(SECRET_KEY);
        RedissonClient client = mock(RedissonClient.class);
        when(client.getLock(lockKey)).thenReturn(lock);
        return new DistributedLockManager(client, properties);
    }


    @Test
    @DisplayName("ChainEngine:后置处理执行失败 ERROR 带 cacheName 但不含 raw key")
    void chainEngine_postProcessFailure_omitsRawKey() {
        CacheHandler failing = new CacheHandler() {
            @Override
            public HandlerResult handle(CacheContext ctx) {
                return HandlerResult.continueChain();
            }

            @Override
            public boolean requiresPostProcess(CacheContext ctx) {
                return true;
            }

            @Override
            public void afterChainExecution(CacheContext ctx, CacheResult result) {
                throw new IllegalStateException("post-process boom for key " + SECRET_KEY);
            }
        };

        try (Capture capture = new Capture(ChainEngine.class)) {
            new ChainEngine().execute(List.of(failing), context());

            assertThat(capture.warnErrorText())
                    .contains("Post-processing failed for")
                    .contains(CACHE)
                    .doesNotContain(SECRET_KEY)
                    .doesNotContain("post-process boom");
        }
    }

    @Test
    @DisplayName("ChainEngine:后置处理判定失败也不泄露 raw key")
    void chainEngine_postProcessPredicateFailure_omitsRawKey() {
        CacheHandler failing = new CacheHandler() {
            @Override
            public HandlerResult handle(CacheContext ctx) {
                return HandlerResult.continueChain();
            }

            @Override
            public boolean requiresPostProcess(CacheContext ctx) {
                throw new IllegalStateException("predicate boom for key " + SECRET_KEY);
            }
        };

        try (Capture capture = new Capture(ChainEngine.class)) {
            assertThat(new ChainEngine().execute(List.of(failing), context()).isSuccess()).isTrue();

            assertThat(capture.warnErrorText())
                    .contains(CACHE)
                    .doesNotContain(SECRET_KEY)
                    .doesNotContain("predicate boom");
        }
    }

    private CacheContext context() {
        return CacheContext.of(CacheInput.builder()
                .operation(CacheOperation.GET)
                .cacheName(CACHE)
                .redisKey(SECRET_KEY)
                .actualKey(SECRET_KEY)
                .build());
    }


    @Test
    @DisplayName("EarlyRefresh:异步刷新失败 ERROR 带 cacheName 但不含 raw key")
    @SuppressWarnings("unchecked")
    void earlyRefresh_asyncRefreshFailure_omitsRawKey() {
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(valueOperations.get(any()))
                .thenThrow(new IllegalStateException("redis down for key " + SECRET_KEY));
        EarlyRefresh earlyRefresh = new EarlyRefresh(
                Clock.systemUTC(),
                mock(ThreadPoolEarlyExpirationExecutor.class),
                mock(RedisTemplate.class),
                valueOperations);

        try (Capture capture = new Capture(EarlyRefresh.class)) {
            earlyRefresh.performAsyncRefresh(SECRET_KEY, CACHE, null);

            assertThat(capture.warnErrorText())
                    .contains("Async early-expiration failed")
                    .contains(CACHE)
                    .doesNotContain(SECRET_KEY)
                    .doesNotContain("redis down for key");
        }
    }


    @Test
    @DisplayName("SyncRoleLockExecutor:锁获取失败 WARN 与异常消息不含 raw key")
    void syncRoleLockExecutor_acquireFailure_omitsRawKey() {
        SyncSupport support = new SyncSupport(
                new ArrayList<>(List.of(refusingLockManager())), new RedisProCacheProperties());

        try (Capture capture = new Capture(SYNC_ROLE_LEADER_LOGGER)) {
            assertThatThrownBy(() -> support.executeSync(SECRET_KEY, () -> "v", 5))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining(SECRET_KEY);

            assertThat(capture.warnErrorText())
                    .contains("failed to acquire distributed lock")
                    .doesNotContain(SECRET_KEY);
        }
    }

    @Test
    @DisplayName("SyncRoleLockExecutor:锁释放失败 ERROR 不含 raw key 与异常 message")
    void syncRoleLockExecutor_releaseFailure_omitsRawKey() {
        LockManager releasingFailure = new LockManager() {
            @Override
            public Optional<LockHandle> tryAcquire(String key, long timeoutSeconds) {
                return Optional.of(() -> {
                    throw new IllegalStateException("release failed for key " + SECRET_KEY);
                });
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
        SyncSupport support = new SyncSupport(List.of(releasingFailure), new RedisProCacheProperties());

        try (Capture capture = new Capture(SYNC_ROLE_LEADER_LOGGER)) {
            assertThat(support.executeSync(SECRET_KEY, () -> "v", 5)).isEqualTo("v");

            assertThat(capture.warnErrorText())
                    .contains("Failed to release distributed lock")
                    .doesNotContain(SECRET_KEY)
                    .doesNotContain("release failed for key");
        }
    }

    private LockManager refusingLockManager() {
        return new LockManager() {
            @Override
            public Optional<LockHandle> tryAcquire(String key, long timeoutSeconds) {
                return Optional.empty();
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
    }


    @Test
    @DisplayName("SerializationMigrationEngine:前向 rejected key WARN 只含指纹")
    void serializationMigration_forwardRejectedKey_omitsRawKey() {
        try (Capture capture = new Capture(SerializationMigrationEngine.class)) {
            assertThat(engineWithFailingKeyRead(SerializationMigrationPhase.CUTOVER).migrate().failed())
                    .isEqualTo(1);

            assertThat(capture.warnErrorText())
                    .contains("Serialization migration rejected key")
                    .contains("keyFingerprint=" + FailureReport.fingerprint(keyBytes()))
                    .doesNotContain(SECRET_KEY);
        }
    }

    @Test
    @DisplayName("SerializationMigrationEngine:回滚 rejected key WARN 不含 raw key 与备份后缀拼接")
    void serializationMigration_rollbackRejectedKey_omitsRawKey() {
        try (Capture capture = new Capture(SerializationMigrationEngine.class)) {
            assertThat(engineWithFailingKeyRead(SerializationMigrationPhase.ROLLBACK).migrate().failed())
                    .isEqualTo(1);

            assertThat(capture.warnErrorText())
                    .contains("Serialization rollback rejected key")
                    .doesNotContain(SECRET_KEY);
        }
    }

    /**
     * 迁移引擎的 key 是 {@code byte[]},泄露形态是 raw key 或 raw key+后缀。让
     * {@code stringCommands().get(key)} 抛异常即命中两个站点各自的 catch。
     */
    private SerializationMigrationEngine engineWithFailingKeyRead(SerializationMigrationPhase phase) {
        RedisProCacheProperties properties = new RedisProCacheProperties();
        SerializationMigrationProperties migration = properties.getSerializer().getMigration();
        migration.setPattern("privacy:*");
        migration.setBatchSize(20);
        migration.setMaxKeys(20);
        migration.setDryRun(false);
        migration.setPhase(phase);

        @SuppressWarnings("unchecked")
        Cursor<byte[]> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(keyBytes());

        org.springframework.data.redis.connection.RedisKeyCommands keyCommands =
                mock(org.springframework.data.redis.connection.RedisKeyCommands.class);
        // scan 有两个重载(ScanOptions / 更具体的 KeyScanOptions),须显式限定参数类型,
        // 否则 when(...) 会绑到默认的 KeyScanOptions 重载上。
        when(keyCommands.scan(any(org.springframework.data.redis.core.ScanOptions.class)))
                .thenReturn(cursor);
        org.springframework.data.redis.connection.RedisStringCommands stringCommands =
                mock(org.springframework.data.redis.connection.RedisStringCommands.class);
        when(stringCommands.get(any())).thenThrow(
                new IllegalStateException("legacy read failed for key " + SECRET_KEY));

        RedisConnection connection = mock(RedisConnection.class);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(connection.stringCommands()).thenReturn(stringCommands);

        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        return new SerializationMigrationEngine(
                factory, new ObjectMapper(), properties, new SecureJacksonSerializerFactory(),
                ResolvedMetrics.resolve(null, new MockEnvironment()));
    }

    private byte[] keyBytes() {
        return (CACHE + ":migrated").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 捕获某个 logger 的 WARN/ERROR 文本 —— 格式化消息 + throwable message 链(异常 message
     * 可能内嵌 raw key,故一并断言)。
     */
    private static final class Capture implements AutoCloseable {

        private final Logger logger;
        private final Level previousLevel;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        Capture(Class<?> owner) {
            this((Logger) LoggerFactory.getLogger(owner));
        }

        Capture(String loggerName) {
            this((Logger) LoggerFactory.getLogger(loggerName));
        }

        private Capture(Logger logger) {
            this.logger = logger;
            this.previousLevel = logger.getLevel();
            appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
        }

        String warnErrorText() {
            StringBuilder text = new StringBuilder();
            for (ILoggingEvent event : appender.list) {
                if (!event.getLevel().isGreaterOrEqual(Level.WARN)) {
                    continue;
                }
                text.append(event.getFormattedMessage()).append('\n');
                for (IThrowableProxy proxy = event.getThrowableProxy();
                     proxy != null;
                     proxy = proxy.getCause()) {
                    text.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
                }
            }
            return text.toString();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }
}
