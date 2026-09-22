package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 链的跨 handler 契约守卫 —— handler 身份取值 + slot 间的顺序要求。
 *
 * <p>身份(order / disableName / tag)自 {@link HandlerOrder} 一次声明,由
 * {@link HandlerIdentity} 解析;本测试钉住冻结值:metric {@code handler} tag 与
 * {@code resi-cache.disabled-handlers} / protection 开关的 kebab 名称。
 *
 * <p>三条过去只存在于注释里的 slot 要求,现在的守卫:
 * <ol>
 *   <li>{@code TtlHandler} 必须先于 {@code ActualCacheHandler}(产出 {@code TtlDecision} 供其读取)
 *       → {@link #ttlSlotPrecedesActualCacheSlot()}</li>
 *   <li>{@code ActualCacheHandler} 必须运行在最后(消费 {@code PrefetchDecision})
 *       → {@link #actualCacheSlotIsLastSlot()};行为侧证据是
 *       {@code EarlyExpirationHandlerIntegrationTest} 里经真实工厂装配链断言 sync 跳过收敛为 MISS
 *       —— 消费者若被排到生产者之前,该断言即红</li>
 *   <li>{@code EarlyExpirationHandler} 在其 slot 上不得 {@code skipAll()}(引擎把 SKIP_ALL 映射成
 *       success,与 sync 刷新的 miss 语义相悖)→ 同一集成断言 +
 *       {@code EarlyExpirationHandlerIntegrationTest.doHandle_nullMode_defaultsToSync} 直接断言
 *       sync 路径的 {@code FlowControl.CONTINUE}</li>
 * </ol>
 */
@DisplayName("handler identity values and cross-slot ordering contract")
class HandlerIdentityContractTest {

    /** 六个标准 slot,按链上装配顺序排列。 */
    private static final List<Class<? extends CacheHandler>> STANDARD_HANDLERS_IN_CHAIN_ORDER = List.of(
            BloomFilterHandler.class,
            SyncLockHandler.class,
            EarlyExpirationHandler.class,
            TtlHandler.class,
            NullValueHandler.class,
            ActualCacheHandler.class);

    @Test
    @DisplayName("标准 handler 的 order / disableName / metric tag 与冻结值一致")
    void standardSlots_identityMatchesFrozenValues() {
        assertThat(STANDARD_HANDLERS_IN_CHAIN_ORDER.stream().map(HandlerIdentity::of).toList())
                .extracting(HandlerIdentity::order, HandlerIdentity::disableName, HandlerIdentity::tag)
                .containsExactly(
                        tuple(100, "bloom-filter", "BloomFilterHandler"),
                        tuple(200, "sync-lock", "SyncLockHandler"),
                        tuple(250, "early-expiration", "EarlyExpirationHandler"),
                        tuple(300, "ttl", "TtlHandler"),
                        tuple(400, "null-value", "NullValueHandler"),
                        tuple(500, "actual-cache", "ActualCacheHandler"));
    }

    @Test
    @DisplayName("TTL slot 先于 ActualCache slot(后者读取 TtlDecision)")
    void ttlSlotPrecedesActualCacheSlot() {
        assertThat(HandlerIdentity.of(TtlHandler.class).order())
                .as("工厂按 HandlerIdentity#order 排序,此序即装配序")
                .isLessThan(HandlerIdentity.of(ActualCacheHandler.class).order());
    }

    @Test
    @DisplayName("ActualCache 是最后一个 slot(它消费链上所有决策)")
    void actualCacheSlotIsLastSlot() {
        int actualCache = HandlerIdentity.of(ActualCacheHandler.class).order();

        assertThat(actualCache).isEqualTo(HandlerOrder.ACTUAL_CACHE.getOrder());
        assertThat(Arrays.stream(HandlerOrder.values())
                .mapToInt(HandlerOrder::getOrder).max().orElseThrow())
                .as("新 slot 不得排在 ActualCache 之后")
                .isEqualTo(actualCache);
    }
}
