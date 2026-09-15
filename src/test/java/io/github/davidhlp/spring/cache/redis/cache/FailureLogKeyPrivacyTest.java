package io.github.davidhlp.spring.cache.redis.cache;




import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 失败诊断 key 隐私回归(ADR-0001 §15)。
 *
 * <p>契约:WARN/ERROR 日志与 typed exception message <b>不得</b>出现 raw key;
 * 配置级低基数的 {@code cacheName} 或 {@link FailureDiagnostics#keyFingerprint} 关联令牌可保留。
 *
 * <p>本测试覆盖此前直接打印 raw key 的路径:分布式锁获取/释放、single-flight 角色失败、
 * 异步提前过期重试、链后置处理。每条路径用 logback {@link ListAppender} 捕获实际日志事件断言。
 */
@DisplayName("Failure Log Key Privacy Tests (ADR-0001 §15)")
class FailureLogKeyPrivacyTest {

    /** 必须不出现的原始 key(测试专用哨兵值)。 */
    private static final String SECRET_KEY = "secret-customer-key-42";

    private ListAppender<ILoggingEvent> attach(Class<?> loggerOwner) {
        return attach(loggerOwner.getName());
    }

    private ListAppender<ILoggingEvent> attach(String loggerName) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(Class<?> loggerOwner, ListAppender<ILoggingEvent> appender) {
        detach(loggerOwner.getName(), appender);
    }

    private void detach(String loggerName, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(loggerName)).detachAppender(appender);
    }

    /** 拼接全部 WARN/ERROR 事件的格式化消息。 */
    private String warnAndErrorText(ListAppender<ILoggingEvent> captured) {
        return captured.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("keyFingerprint:与 raw key 不同、稳定、null-safe,byte[] 与 String 同源")
    void keyFingerprint_isStableTokenNotRawKey() {
        assertThat(FailureDiagnostics.keyFingerprint(SECRET_KEY))
                .isNotEqualTo(SECRET_KEY)
                .isEqualTo(FailureDiagnostics.keyFingerprint(SECRET_KEY));
        assertThat(FailureDiagnostics.keyFingerprint((String) null)).isEqualTo("null");
        assertThat(FailureDiagnostics.keyFingerprint((byte[]) null)).isEqualTo("null");
        assertThat(FailureDiagnostics.keyFingerprint(SECRET_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(FailureDiagnostics.keyFingerprint(SECRET_KEY));
    }

    @Test
    @DisplayName("RefreshRetryPolicy:重试耗尽后 WARN/ERROR 不含 raw key")
    void refreshRetryPolicy_exhaustedRetries_omitsRawKey() {
        ListAppender<ILoggingEvent> captured = attach(RefreshRetryPolicy.class);
        try {
            RefreshRetryPolicy policy = new RefreshRetryPolicy();
            AtomicInteger attempts = new AtomicInteger();

            assertThatThrownBy(() -> policy.executeWithRetry(SECRET_KEY, () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("redis down");
            })).isInstanceOf(RuntimeException.class);

            assertThat(attempts.get()).isEqualTo(RefreshRetryPolicy.MAX_RETRY_COUNT);
            assertThat(warnAndErrorText(captured))
                    .as("WARN/ERROR 不得包含 raw key(ADR-0001 §15)")
                    .doesNotContain(SECRET_KEY)
                    .contains(FailureDiagnostics.keyFingerprint(SECRET_KEY));
        } finally {
            detach(RefreshRetryPolicy.class, captured);
        }
    }

    @Test
    @DisplayName("ChainEngine:post-process 失败 ERROR 不含 raw key(带 cacheName)")
    void chainEngine_postProcessFailure_omitsRawKey() {
        ListAppender<ILoggingEvent> captured = attach(ChainEngine.class);
        try {
            ChainEngine engine = new ChainEngine();
            CacheContext context = CacheContext.of(CacheInput.builder()
                    .operation(CacheOperation.GET)
                    .cacheName("privacy-cache")
                    .redisKey(SECRET_KEY)
                    .actualKey(SECRET_KEY)
                    .build());
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
                    throw new IllegalStateException("post-process boom");
                }
            };

            engine.execute(List.of(failing), context);

            assertThat(warnAndErrorText(captured))
                    .doesNotContain(SECRET_KEY)
                    .contains("privacy-cache");
        } finally {
            detach(ChainEngine.class, captured);
        }
    }

    @Test
    @DisplayName("DistributedLockManager:获取超时 WARN 与被中断 ERROR/异常消息不含 raw key 与 lockKey")
    void distributedLockManager_failures_omitRawKey() throws InterruptedException {
        ListAppender<ILoggingEvent> captured = attach(DistributedLockManager.class);
        try {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            RLock notAcquired = mock(RLock.class);
            when(notAcquired.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
            DistributedLockManager manager = managerWithLock(properties, notAcquired);

            assertThat(manager.tryAcquire(SECRET_KEY, 1)).isEmpty();

            assertThat(warnAndErrorText(captured))
                    .doesNotContain(SECRET_KEY)
                    .doesNotContain(manager.buildLockKey(SECRET_KEY))
                    .contains(FailureDiagnostics.keyFingerprint(SECRET_KEY));

            RLock interrupted = mock(RLock.class);
            when(interrupted.tryLock(anyLong(), anyLong(), any()))
                    .thenThrow(new InterruptedException("interrupted"));
            DistributedLockManager interruptedManager = managerWithLock(properties, interrupted);

            try {
                assertThatThrownBy(() -> interruptedManager.tryAcquire(SECRET_KEY, 1))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageNotContaining(SECRET_KEY);
            } finally {
                Thread.interrupted();
            }

            assertThat(warnAndErrorText(captured)).doesNotContain(SECRET_KEY);
        } finally {
            detach(DistributedLockManager.class, captured);
        }
    }

    private DistributedLockManager managerWithLock(RedisProCacheProperties properties, RLock lock) {
        DistributedLockManager template = new DistributedLockManager(mock(RedissonClient.class), properties);
        RedissonClient client = mock(RedissonClient.class);
        when(client.getLock(template.buildLockKey(SECRET_KEY))).thenReturn(lock);
        return new DistributedLockManager(client, properties);
    }

    @Test
    @DisplayName("SyncSupport:fail-fast 异常消息与 local-only 降级 WARN 不含 raw key")
    void syncSupport_failureDiagnostics_omitRawKey() {
        // leader 角色的 WARN 走 SyncRole$Leader 自己的 logger;startup WARN 走 SyncSupport。
        ListAppender<ILoggingEvent> captured = attach(SyncSupport.class);
        ListAppender<ILoggingEvent> leaderCaptured = attach(SyncRoleLeaderLogger.NAME);
        try {
            RedisProCacheProperties failFastProperties = new RedisProCacheProperties();
            SyncSupport failFast = new SyncSupport(new ArrayList<>(), failFastProperties);

            assertThatThrownBy(() -> failFast.executeSync(SECRET_KEY, () -> "v", 5))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(SECRET_KEY)
                    .hasMessageContaining(FailureDiagnostics.keyFingerprint(SECRET_KEY));

            RedisProCacheProperties localOnlyProperties = new RedisProCacheProperties();
            localOnlyProperties.getSyncLock().setLocalOnly(true);
            SyncSupport localOnly = new SyncSupport(new ArrayList<>(), localOnlyProperties);

            assertThat(localOnly.executeSync(SECRET_KEY, () -> "v", 5)).isEqualTo("v");

            assertThat(warnAndErrorText(captured)).doesNotContain(SECRET_KEY);
            assertThat(warnAndErrorText(leaderCaptured))
                    .as("local-only 降级 WARN 必须可关联但不含 raw key")
                    .doesNotContain(SECRET_KEY)
                    .contains(FailureDiagnostics.keyFingerprint(SECRET_KEY));
        } finally {
            detach(SyncSupport.class, captured);
            detach(SyncRoleLeaderLogger.NAME, leaderCaptured);
        }
    }

    @Test
    @DisplayName("SyncRole:锁管理器获取失败 WARN 不含 raw key")
    void syncRole_lockManagerAcquireFailure_omitsRawKey() {
        ListAppender<ILoggingEvent> captured = attach(SyncRoleLeaderLogger.NAME);
        try {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            LockManager refusing = new LockManager() {
                @Override
                public Optional<LockHandle> tryAcquire(String key, long timeoutSeconds) {
                    return Optional.empty();
                }

                @Override
                public int getOrder() {
                    return 0;
                }
            };
            SyncSupport support = new SyncSupport(new ArrayList<>(List.of(refusing)), properties);

            assertThatThrownBy(() -> support.executeSync(SECRET_KEY, () -> "v", 5))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining(SECRET_KEY);

            assertThat(warnAndErrorText(captured))
                    .as("获取锁失败的 WARN 必须被本测试捕获(否则断言空转)")
                    .contains("failed to acquire distributed lock")
                    .doesNotContain(SECRET_KEY);
        } finally {
            detach(SyncRoleLeaderLogger.NAME, captured);
        }
    }

    /** SyncRole.Leader 的 logger 名(嵌套类在包外不可直接引用,避免测试依赖其可见性)。 */
    private static final class SyncRoleLeaderLogger {
        static final String NAME = SyncRole.class.getName() + "$Leader";

        private SyncRoleLeaderLogger() {
        }
    }

    @Test
    @DisplayName("EarlyExpirationHandler:异步刷新失败 ERROR 带 cacheName 但不含 raw key")
    @SuppressWarnings("unchecked")
    void earlyExpirationHandler_asyncRefreshFailure_omitsRawKey() {
        ListAppender<ILoggingEvent> captured = attach(EarlyRefresh.class);
        try {
            ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
            when(valueOperations.get(any())).thenThrow(new IllegalStateException("redis down"));
            EarlyRefresh earlyRefresh = new EarlyRefresh(
                    mock(EarlyExpirationPolicy.class),
                    mock(ThreadPoolEarlyExpirationExecutor.class),
                    mock(RedisTemplate.class),
                    mock(CacheStatisticsCollector.class),
                    valueOperations);

            earlyRefresh.performAsyncRefresh(SECRET_KEY, "privacy-cache", null);

            assertThat(warnAndErrorText(captured))
                    .doesNotContain(SECRET_KEY)
                    .contains("privacy-cache");
        } finally {
            detach(EarlyRefresh.class, captured);
        }
    }
}
