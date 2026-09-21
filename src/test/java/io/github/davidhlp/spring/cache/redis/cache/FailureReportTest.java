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
import io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 失败上报单点 {@link FailureReport}(key-privacy contract)。
 *
 * <p>规则本体只测一次(seam 级):一次失败 = 一条 WARN/ERROR + 一条配对 DEBUG;
 * WARN/ERROR 只含 cacheName / 内容指纹 / 异常类型链,不含 raw key 与异常 message;
 * 含 message 的完整堆栈只在 DEBUG。各调用点保留一条 thin 覆盖,证明「仍然上报、
 * 仍然恰好一次、仍然是同一级别、仍然输出同一指纹」,不逐站点枚举 message 文本。
 */
@DisplayName("FailureReport seam (key-privacy contract)")
class FailureReportTest {

    /** 必须不出现的原始 key(测试专用哨兵值)。 */
    private static final String SECRET_KEY = "secret-customer-key-42";
    private static final String CACHE = "privacy-cache";
    /** seam 级测试专用 logger 名 —— 不与生产类 logger 混淆。 */
    private static final String SEAM_LOGGER = "resicache.failure-report.seam";

    @Test
    @DisplayName("error/warn:恰好一条 WARN 或 ERROR + 一条带栈 DEBUG,高层日志不含 raw key 与异常 message")
    void errorAndWarn_emitSanctionedPair() {
        try (Capture capture = new Capture(SEAM_LOGGER)) {
            IllegalStateException failure =
                    new IllegalStateException("redis down for key " + SECRET_KEY);

            FailureReport.error(capture.logger, "Cache GET failed, kind=REDIS", CACHE, SECRET_KEY, failure);
            FailureReport.warn(capture.logger, "Async early-expiration failed", CACHE, SECRET_KEY, failure);

            assertThat(capture.events(Level.ERROR)).hasSize(1);
            assertThat(capture.events(Level.WARN)).hasSize(1);
            assertThat(capture.events(Level.DEBUG)).hasSize(2);
            assertThat(capture.highLevelEvents())
                    .as("WARN/ERROR 不携带堆栈(异常 message 可能内嵌 raw key)")
                    .allMatch(event -> event.getThrowableProxy() == null);
            assertThat(capture.highLevelText())
                    .contains("Cache GET failed, kind=REDIS")
                    .contains("Async early-expiration failed")
                    .contains("cacheName=" + CACHE)
                    .contains("keyFingerprint=" + FailureReport.fingerprint(SECRET_KEY))
                    .contains("cause=IllegalStateException")
                    .doesNotContain(SECRET_KEY)
                    .doesNotContain("redis down for key");
            assertThat(capture.events(Level.DEBUG))
                    .as("完整栈只在 DEBUG")
                    .allMatch(event -> event.getThrowableProxy() != null);
        }
    }

    @Test
    @DisplayName("无异常上下文:只输出 WARN,不伪造 DEBUG 配对")
    void warnWithoutFailure_emitsSingleWarn() {
        try (Capture capture = new Capture(SEAM_LOGGER)) {
            FailureReport.warn(capture.logger, "Failed to acquire distributed lock within 5s", null, SECRET_KEY);

            assertThat(capture.events(Level.WARN)).hasSize(1);
            assertThat(capture.events(Level.DEBUG)).isEmpty();
            assertThat(capture.formattedMessages(Level.WARN))
                    .containsExactly("Failed to acquire distributed lock within 5s: keyFingerprint="
                            + FailureReport.fingerprint(SECRET_KEY));
        }
    }

    @Test
    @DisplayName("上下文按可用字段组装;不可识别的 key 形态不渲染")
    void contextAssembly_omitsAbsentFields() {
        Object opaqueKey = new Object() {
            @Override
            public String toString() {
                return SECRET_KEY;
            }
        };
        try (Capture capture = new Capture(SEAM_LOGGER)) {
            FailureReport.warn(capture.logger, "cache-only", CACHE, null);
            FailureReport.warn(capture.logger, "key-only", null, SECRET_KEY);
            FailureReport.warn(capture.logger, "no-context", null, null);
            FailureReport.warn(capture.logger, "opaque-key", null, opaqueKey);

            assertThat(capture.formattedMessages(Level.WARN)).containsExactly(
                    "cache-only: cacheName=" + CACHE,
                    "key-only: keyFingerprint=" + FailureReport.fingerprint(SECRET_KEY),
                    "no-context",
                    "opaque-key");
        }
    }

    @Test
    @DisplayName("fingerprint:与 raw key 不同、稳定、null-safe,byte[] 按字节哈希")
    void fingerprint_isStableTokenNotRawKey() {
        assertThat(FailureReport.fingerprint(SECRET_KEY))
                .isNotEqualTo(SECRET_KEY)
                .isEqualTo(FailureReport.fingerprint(SECRET_KEY));
        assertThat(FailureReport.fingerprint((String) null)).isEqualTo("null");
        assertThat(FailureReport.fingerprint((byte[]) null)).isEqualTo("null");

        byte[] bytes = SECRET_KEY.getBytes(StandardCharsets.UTF_8);
        assertThat(FailureReport.fingerprint(bytes))
                .as("字节形态按字节哈希(不经过 UTF-8 解码,避免非法序列碰撞)")
                .isNotEqualTo(SECRET_KEY)
                .isEqualTo(FailureReport.fingerprint(bytes));
        assertThat(FailureReport.fingerprint(new byte[] {(byte) 0xFF, (byte) 0xFE}))
                .isNotEqualTo(FailureReport.fingerprint(new byte[] {(byte) 0xFE, (byte) 0xFF}));
    }

    @Test
    @DisplayName("站点 BloomSupport:三个 fail-open 失败点各恰好一条 ERROR + 一条 DEBUG,不含 raw key")
    void bloomSupportFailOpen_reportsThroughSeam() {
        BloomIFilter broken = new BloomIFilter() {
            @Override
            public void add(String cacheName, String key) {
                throw new IllegalStateException("bloom add boom for key " + SECRET_KEY);
            }

            @Override
            public boolean mightContain(String cacheName, String key) {
                throw new IllegalStateException("bloom check boom for key " + SECRET_KEY);
            }

            @Override
            public void clear(String cacheName) {
                throw new IllegalStateException("bloom clear boom for key " + SECRET_KEY);
            }
        };
        try (Capture capture = new Capture(BloomSupport.class.getName())) {
            BloomSupport support = new BloomSupport(broken);

            assertThat(support.mightContain(CACHE, SECRET_KEY))
                    .as("fail-open 行为必须保留")
                    .isTrue();
            support.add(CACHE, SECRET_KEY);
            support.clear(CACHE);

            assertThat(capture.events(Level.ERROR)).hasSize(3);
            assertThat(capture.events(Level.DEBUG)).hasSize(3);
            assertThat(capture.highLevelText())
                    .contains("Bloom filter mightContain failed, defaulting to may-contain")
                    .contains("Bloom filter add failed")
                    .contains("Bloom filter clear failed")
                    .contains("cacheName=" + CACHE)
                    .doesNotContain(SECRET_KEY);
        }
    }

    @Test
    @DisplayName("站点 RefreshRetryPolicy:重试次数不变,WARN/ERROR 只输出指纹")
    void refreshRetryPolicy_exhaustedRetries_reportFingerprint() {
        try (Capture capture = new Capture(RefreshRetryPolicy.class.getName())) {
            RefreshRetryPolicy policy = new RefreshRetryPolicy();
            AtomicInteger attempts = new AtomicInteger();

            assertThatThrownBy(() -> policy.executeWithRetry(SECRET_KEY, () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("redis down for key " + SECRET_KEY);
            })).isInstanceOf(RuntimeException.class);

            assertThat(attempts.get()).isEqualTo(RefreshRetryPolicy.MAX_RETRY_COUNT);
            assertThat(capture.events(Level.ERROR)).hasSize(1);
            assertThat(capture.highLevelText())
                    .contains("keyFingerprint=" + FailureReport.fingerprint(SECRET_KEY))
                    .doesNotContain(SECRET_KEY);
        }
    }

    @Test
    @DisplayName("站点 SyncSupport:local-only 降级 WARN 可关联且不含 raw key")
    void syncSupportLocalOnly_reportsFingerprint() {
        RedisProCacheProperties properties = new RedisProCacheProperties();
        properties.getSyncLock().setLocalOnly(true);
        // 降级 WARN 走角色自己的 logger(Leader 持 key 上下文),不是 SyncSupport 的 logger。
        try (Capture capture = new Capture(SyncRole.class.getName() + "$Leader")) {
            SyncSupport support = new SyncSupport(new ArrayList<>(), properties);

            assertThat(support.executeSync(SECRET_KEY, () -> "v", 5)).isEqualTo("v");

            assertThat(capture.events(Level.WARN)).isNotEmpty();
            assertThat(capture.highLevelText())
                    .contains("protection.degraded=local-only")
                    .contains("keyFingerprint=" + FailureReport.fingerprint(SECRET_KEY))
                    .doesNotContain(SECRET_KEY);
        }
    }

    @Test
    @DisplayName("站点 ChainEngine:observer 失败 ERROR 只渲染异常类型链,栈留在 DEBUG")
    void chainEngineObserverFailure_reportsThroughSeam() {
        ChainEngine engine = new ChainEngine();
        engine.addObserver(new ChainObserver() {
            @Override
            public Object onChainStart(CacheContext context) {
                throw new IllegalStateException("observer boom for key " + SECRET_KEY);
            }
        });
        CacheContext context = CacheContext.of(CacheInput.builder()
                .operation(CacheOperation.GET)
                .cacheName(CACHE)
                .redisKey(SECRET_KEY)
                .actualKey(SECRET_KEY)
                .build());

        try (Capture capture = new Capture(ChainEngine.class.getName())) {
            engine.execute(List.of(new CacheHandler() {
                @Override
                public HandlerResult handle(CacheContext ctx) {
                    return HandlerResult.terminate(CacheResult.success());
                }
            }), context);

            assertThat(capture.highLevelText())
                    .contains("onChainStart failed", "cause=IllegalStateException")
                    .doesNotContain(SECRET_KEY)
                    .doesNotContain("observer boom");
            assertThat(capture.events(Level.DEBUG))
                    .as("observer 失败的完整栈保留在 DEBUG")
                    .anyMatch(event -> event.getThrowableProxy() != null
                            && event.getFormattedMessage().contains("onChainStart"));
        }
    }

    /**
     * 临时捕获某个 logger 全部级别的事件。测试 logger 被提升到 DEBUG —— 否则
     * 「栈保留在 DEBUG」的契约不可断言;关闭时还原原 level 并摘除 appender。
     */
    private static final class Capture implements AutoCloseable {

        private final Logger logger;
        private final Level previousLevel;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        Capture(String loggerName) {
            this.logger = (Logger) LoggerFactory.getLogger(loggerName);
            this.previousLevel = logger.getLevel();
            this.appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
        }

        List<ILoggingEvent> events(Level level) {
            return appender.list.stream().filter(event -> event.getLevel() == level).toList();
        }

        List<String> formattedMessages(Level level) {
            return events(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        List<ILoggingEvent> highLevelEvents() {
            return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
        }

        /** WARN/ERROR 的全部渲染文本(格式化消息 + throwable message 链)。 */
        String highLevelText() {
            StringBuilder sb = new StringBuilder();
            for (ILoggingEvent event : highLevelEvents()) {
                sb.append(event.getFormattedMessage()).append('\n');
                for (ch.qos.logback.classic.spi.IThrowableProxy proxy = event.getThrowableProxy();
                     proxy != null;
                     proxy = proxy.getCause()) {
                    sb.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
                }
            }
            return sb.toString();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }
}
