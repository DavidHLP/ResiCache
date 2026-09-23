package io.github.davidhlp.spring.cache.redis.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * main 上 {@code NullValueEncoder} 的 null 决策 DEBUG 行恢复契约。
 *
 * <p>措辞与级别冻结:{@code "Returning null value in standard format: cacheName={}, key={}"}。
 * 决策谓词由 {@link CacheValueCodec#isNullDecision} 单一持有,本测试从 call site(持上下文)
 * 断言实际输出,回归"重构后该行消失"的可观测变化。
 */
@DisplayName("null-return DEBUG line")
class ActualCacheNullReturnLogTest {

    private static final String CACHE = "null-value-cache";
    private static final String REDIS_KEY = "null-value-cache:key";

    @Test
    @DisplayName("缓存命中但 payload 为 null:输出 main 的原措辞 DEBUG 行")
    void cachedNullHit_emitsMainDebugLine() {
        ActualCacheHandler handler = handlerReturning(CachedValue.forTest(null, 60, 1L, 1L, false));
        CacheContext context = getContext();

        assertThat(capturedDebug(handler, context))
                .containsExactly("Returning null value in standard format: cacheName="
                        + CACHE + ", key=" + REDIS_KEY);
    }

    @Test
    @DisplayName("NullValue.INSTANCE 命中:同一决策,同一行")
    void nullValueInstanceHit_emitsSameLine() {
        ActualCacheHandler handler =
                handlerReturning(CachedValue.forTest(NullValue.INSTANCE, 60, 1L, 1L, false));

        assertThat(capturedDebug(handler, getContext()))
                .containsExactly("Returning null value in standard format: cacheName="
                        + CACHE + ", key=" + REDIS_KEY);
    }

    @Test
    @DisplayName("普通值命中:不得输出该行")
    void nonNullHit_doesNotEmitTheLine() {
        ActualCacheHandler handler = handlerReturning(CachedValue.forTest("payload", 60, 1L, 1L, false));

        assertThat(capturedDebug(handler, getContext()))
                .as("写一条不该有的日志同样是可观测变化")
                .noneMatch(message -> message.contains("Returning null value in standard format"));
    }

    /** 走 GET 路径,捕获 {@link ActualCacheHandler} 的 DEBUG 输出。 */
    private List<String> capturedDebug(ActualCacheHandler handler, CacheContext context) {
        Logger logger = (Logger) LoggerFactory.getLogger(ActualCacheHandler.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            handler.handle(context);
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.DEBUG)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("Returning null value in standard format"))
                    .toList();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    private ActualCacheHandler handlerReturning(CachedValue stored) {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(valueOperations.get(REDIS_KEY)).thenReturn(stored);
        return new ActualCacheHandler(
                mock(RedisTemplate.class),
                valueOperations,
                new CacheValueCodec(new ObjectMapper()),
                mock(RefreshCancellation.class),
                mock(CacheErrorHandler.class));
    }

    private CacheContext getContext() {
        return CacheContext.of(CacheInput.builder()
                .operation(CacheOperation.GET)
                .cacheName(CACHE)
                .redisKey(REDIS_KEY)
                .actualKey("key")
                .build());
    }
}
