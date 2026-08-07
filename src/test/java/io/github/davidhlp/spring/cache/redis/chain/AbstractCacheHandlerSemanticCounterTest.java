package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约测试 — {@link AbstractCacheHandler} 语义 counter 模板方法 seam。
 * 覆盖 4 个不变量：
 *
 * <ul>
 *   <li>子类未 override {@code semanticCounter()} → 注册 no-op（counter 不注册）</li>
 *   <li>子类 override 返回非 null → 注册 counter（按元数据 name + description）</li>
 *   <li>registry 缺失 → no-op（counter 字段保持 null，safeIncrement 无效）</li>
 *   <li>safeIncrementSemantic() 在 counter 未注册时 no-op（null-safe）</li>
 * </ul>
 */
class AbstractCacheHandlerSemanticCounterTest {

    /** 简单测试 handler：未 override semanticCounter（默认 null）。 */
    static final class NoSemanticHandler extends AbstractCacheHandler {
        @Override
        protected boolean shouldHandle(CacheContext context) {
            return false;
        }
        @Override
        protected HandlerResult doHandle(CacheContext context) {
            return HandlerResult.continueChain();
        }
    }

    /** 简单测试 handler：override semanticCounter 返回固定元数据。 */
    static final class WithSemanticHandler extends AbstractCacheHandler {
        static final String COUNTER_NAME = "resicache.handler.test.semantic";
        static final String COUNTER_DESC = "test semantic counter for semantic-counter contract";

        @Override
        protected CounterMetadata semanticCounter() {
            return new CounterMetadata(COUNTER_NAME, COUNTER_DESC);
        }

        @Override
        protected boolean shouldHandle(CacheContext context) {
            return false;
        }
        @Override
        protected HandlerResult doHandle(CacheContext context) {
            return HandlerResult.continueChain();
        }

        /** 测试用：暴露基类的 null-safe 自增 helper 给测试断言。 */
        void incrementForTest() {
            safeIncrementSemantic();
        }
    }

    @Nested
    @DisplayName("attachMeterRegistry 行为")
    class AttachMeterRegistryBehavior {

        @Test
        @DisplayName("null registry → no-op（counter 字段保持 null）")
        void nullRegistry_isNoOp() {
            NoSemanticHandler h = new NoSemanticHandler();
            h.attachMeterRegistry(null);
            // 不抛异常 = 通过；语义：无副作用
        }

        @Test
        @DisplayName("子类未 override semanticCounter → counter 不注册")
        void noOverride_noCounterRegistered() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            NoSemanticHandler h = new NoSemanticHandler();
            h.attachMeterRegistry(registry);

            // 验证：registry 没有任何 counter 被注册
            assertThat(registry.getMeters()).isEmpty();
        }

        @Test
        @DisplayName("子类 override semanticCounter → counter 按元数据 name+description 注册")
        void withOverride_counterRegistered() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            WithSemanticHandler h = new WithSemanticHandler();
            h.attachMeterRegistry(registry);

            Counter counter = registry.get(WithSemanticHandler.COUNTER_NAME).counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(0.0);
            assertThat(counter.getId().getDescription()).isEqualTo(WithSemanticHandler.COUNTER_DESC);
        }

        @Test
        @DisplayName("重复 attach 同一 registry → 幂等（同名 register 返同一实例）")
        void idempotent_attachSameRegistry() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            WithSemanticHandler h = new WithSemanticHandler();
            h.attachMeterRegistry(registry);
            h.attachMeterRegistry(registry);

            // Micrometer 语义：同名 register 返同一实例，count 累加仍为 0（未自增）
            Counter counter = registry.get(WithSemanticHandler.COUNTER_NAME).counter();
            assertThat(counter.count()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("safeIncrementSemantic 行为")
    class SafeIncrementSemanticBehavior {

        @Test
        @DisplayName("counter 已注册 → 自增生效（count == 1）")
        void incrementWorks_afterRegistration() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            WithSemanticHandler h = new WithSemanticHandler();
            h.attachMeterRegistry(registry);

            h.incrementForTest();
            h.incrementForTest();
            h.incrementForTest();

            assertThat(registry.get(WithSemanticHandler.COUNTER_NAME).counter().count())
                    .isEqualTo(3.0);
        }

        @Test
        @DisplayName("counter 未注册（子类未 override） → no-op 不抛异常")
        void noOp_whenHandlerHasNoSemanticCounter() {
            NoSemanticHandler h = new NoSemanticHandler();
            // 不调 attachMeterRegistry，counter 字段保持 null
            h.safeIncrementSemantic();  // 必须不抛 NPE
            h.safeIncrementSemantic();
            h.safeIncrementSemantic();
        }

        @Test
        @DisplayName("counter 未注册（attachMeterRegistry 未调） → safeIncrementSemantic no-op")
        void noOp_whenRegistryNeverAttached() {
            WithSemanticHandler h = new WithSemanticHandler();
            // 注意：未调 attachMeterRegistry，semanticCounter 字段为 null
            h.incrementForTest();  // 必须不抛 NPE
        }
    }

    @Nested
    @DisplayName("Javadoc 描述验证")
    class JavadocContract {

        @Test
        @DisplayName("CounterMetadata record 暴露 name + description 访问器")
        void counterMetadataRecordExposesAccessors() {
            AbstractCacheHandler.CounterMetadata md =
                    new AbstractCacheHandler.CounterMetadata("name", "description");
            assertThat(md.name()).isEqualTo("name");
            assertThat(md.description()).isEqualTo("description");
        }
    }
}
