package io.github.davidhlp.spring.cache.redis.cache;




import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Bloom 失败日志 key 隐私契约(ADR-06)。
 *
 * <p>Bloom add/check/clear 失败时,ERROR 日志不得包含 raw key(测试 key 必须不出现);
 * cacheName(配置级低基数)可保留。用 Logback {@link ListAppender} 捕获日志事件断言。
 */
@DisplayName("Bloom Failure Log Key Privacy Tests")
class BloomFailureLogKeyPrivacyTest {

    private static final String SECRET_KEY = "secret-customer-key-42";
    private static final String CACHE = "cache";

    private ListAppender<ILoggingEvent> attach(String loggerName) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    @DisplayName("BloomSupport add 失败 ERROR 日志不含 raw key")
    void bloomSupport_addFailure_logOmitsRawKey() {
        ListAppender<ILoggingEvent> captured = attach(BloomSupport.class.getName());
        try {
            RedisBloomIFilter failingFilter = mock(RedisBloomIFilter.class);
            doThrow(new RuntimeException("redis down")).when(failingFilter).add(CACHE, SECRET_KEY);
            BloomSupport support = new BloomSupport(failingFilter);

            support.add(CACHE, SECRET_KEY);

            assertThat(captured.list)
                    .as("至少有一条 ERROR")
                    .isNotEmpty();
            String allLogs = captured.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", String::concat);
            assertThat(allLogs)
                    .as("ERROR 日志不得包含 raw key(ADR-06 key 隐私)")
                    .doesNotContain(SECRET_KEY);
        } finally {
            ((Logger) LoggerFactory.getLogger(BloomSupport.class.getName())).detachAppender(captured);
        }
    }

    @Test
    @DisplayName("RedisBloomIFilter check 失败 ERROR 日志不含 raw key")
    void redisBloomFilter_checkFailure_logOmitsRawKey() {
        ListAppender<ILoggingEvent> captured = attach(RedisBloomIFilter.class.getName());
        try {
            @SuppressWarnings("unchecked")
            RedisTemplate<String, ?> template = mock(RedisTemplate.class);
            // 触发 mightContain 内部异常路径 → ERROR 日志
            RedisBloomIFilter filter = new RedisBloomIFilter(
                    template,
                    new BloomFilterConfig("bf:", 1024, 3, 100),
                    new MessageDigestBloomHashStrategy(),
                    null);
            filter.init();
            doThrow(new RuntimeException("redis down"))
                    .when(template).executePipelined(
                            org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.RedisCallback<Object>>any());

            filter.mightContain(CACHE, SECRET_KEY);

            assertThat(captured.list)
                    .as("至少有一条 ERROR")
                    .isNotEmpty();
            String allLogs = captured.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", String::concat);
            assertThat(allLogs)
                    .as("ERROR 日志不得包含 raw key(ADR-06 key 隐私)")
                    .doesNotContain(SECRET_KEY);
        } finally {
            ((Logger) LoggerFactory.getLogger(RedisBloomIFilter.class.getName())).detachAppender(captured);
        }
    }
}
