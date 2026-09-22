package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HandlerResult 与 ChainEngine 之间的 SPI 协议边界测试。
 */
@DisplayName("HandlerResult Protocol Tests")
class HandlerResultProtocolTest {

    @Test
    @DisplayName("引擎拒绝 null decision 并指名违规 handler")
    void engine_rejectsNullDecisionWithNamedHandler() {
        HandlerResult invalidResult = mock(HandlerResult.class);
        when(invalidResult.decision()).thenReturn(null);
        CacheHandler offendingHandler = context -> invalidResult;

        assertThatThrownBy(() -> new ChainEngine()
                .execute(List.of(offendingHandler), testContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null decision")
                .hasMessageContaining(offendingHandler.getClass().getName());
    }

    private static CacheContext testContext() {
        return CacheContext.of(CacheInput.builder()
                .operation(CacheOperation.GET)
                .cacheName("test-cache")
                .redisKey("test:key")
                .actualKey("test:key")
                .build());
    }
}
