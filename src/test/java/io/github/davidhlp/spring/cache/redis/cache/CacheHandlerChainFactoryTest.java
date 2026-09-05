package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import io.github.davidhlp.spring.cache.redis.chain.HandlerPriority;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CacheHandlerChainFactory 单元测试
 *
 * <p>测试责任链工厂的创建和组装功能
 */
@DisplayName("CacheHandlerChainFactory Tests")
class CacheHandlerChainFactoryTest {

    private RedisProCacheProperties properties;
    private CacheHandlerChainFactory factory;

    @BeforeEach
    void setUp() {
        properties = mock(RedisProCacheProperties.class);
        factory = new CacheHandlerChainFactory(Collections.emptyList(), properties, null, new ChainEngine());
    }

    @Nested
    @DisplayName("createChain")
    class CreateChainTests {

        @Test
        @DisplayName("creates empty chain when no handlers provided")
        void createChain_noHandlers_createsEmptyChain() {
            factory = new CacheHandlerChainFactory(Collections.emptyList(), properties, null, new ChainEngine());

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isZero();
        }

        @Test
        @DisplayName("adds all handlers to chain")
        void createChain_multipleHandlers_addsAllToChain() {
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler(),
                    new YetAnotherTestHandler()
            );
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("handlers are sorted by @HandlerPriority annotation")
        void createChain_withPriorities_sortsCorrectly() {
            List<CacheHandler> handlers = List.of(
                    new ActualCacheTestHandler(),
                    new BloomFilterTestHandler(),
                    new SyncLockTestHandler()
            );
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            CacheHandlerChain chain = factory.createChain();

            // Order should be: BLOOM_FILTER(100), SYNC_LOCK(200), ACTUAL_CACHE(500)
            assertThat(chain.getHandlerNames()).containsExactly(
                    "BloomFilterTestHandler", "SyncLockTestHandler", "ActualCacheTestHandler");
        }

        @Test
        @DisplayName("handlers without annotation get Integer.MAX_VALUE priority")
        void createChain_noAnnotation_getsMaxPriority() {
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new PriorityTestHandler()
            );
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            CacheHandlerChain chain = factory.createChain();

            // PriorityTestHandler has explicit order, TestCacheHandler has MAX_VALUE
            List<String> names = chain.getHandlerNames();
            assertThat(names.get(names.size() - 1)).isEqualTo("TestCacheHandler");
        }

        @Test
        @DisplayName("handlers are linked correctly in chain order")
        void createChain_multipleHandlers_linksCorrectly() {
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler()
            );
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("disabled handlers")
    class DisabledHandlersTests {

        @Test
        @DisplayName("filters out disabled handlers from global config")
        void createChain_disabledHandlersGlobally_filtersOut() {
            // TestCacheHandler -> "test-cache" (Handler removed, camelCase converted)
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler()
            );
            when(properties.getDisabledHandlers()).thenReturn(List.of("test-cache"));
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isEqualTo(1);
            assertThat(chain.getHandlerNames()).containsExactly("AnotherTestHandler");
        }

        @Test
        @DisplayName("handles kebab-case and class name mapping")
        void createChain_kebabCaseMapping_worksCorrectly() {
            // TestCacheHandler -> "test-cache", AnotherTestHandler -> "another-test"
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler()
            );
            when(properties.getDisabledHandlers()).thenReturn(List.of("test-cache"));
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isEqualTo(1);
            assertThat(chain.getHandlerNames()).containsExactly("AnotherTestHandler");
        }

        @Test
        @DisplayName("empty disabled handlers list keeps all handlers")
        void createChain_emptyDisabledList_keepsAllHandlers() {
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler()
            );
            when(properties.getDisabledHandlers()).thenReturn(Collections.emptyList());
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("only disabled handlers results in empty chain")
        void createChain_allDisabled_resultsInEmptyChain() {
            // TestCacheHandler -> "test-cache", AnotherTestHandler -> "another-test"
            List<CacheHandler> handlers = List.of(
                    new TestCacheHandler(),
                    new AnotherTestHandler()
            );
            when(properties.getDisabledHandlers()).thenReturn(List.of("test-cache", "another-test"));
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            CacheHandlerChain chain = factory.createChain();

            assertThat(chain.size()).isZero();
        }
    }

    @Nested
    @DisplayName("protection 启动时静态开关语义 (RM-003)")
    class ProtectionKillSwitchTests {

        @Test
        @DisplayName("protection.enabled=false 保留 TTL 与 ActualCache(不禁用基础 TTL)")
        void protectionDisabled_preservesTtlAndActualCache() {
            RedisProCacheProperties.ProtectionProperties protection =
                    new RedisProCacheProperties.ProtectionProperties();
            protection.setEnabled(false);
            when(properties.getProtection()).thenReturn(protection);

            List<CacheHandler> handlers = List.of(
                    new BloomFilterHandler(), new SyncLockHandler(), new EarlyExpirationHandler(),
                    new TtlHandler(), new NullValueHandler(), new ActualCacheHandler());
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            List<String> names = factory.createChain().getHandlerNames();

            // B1:TtlHandler 兼担基础 TTL 计算,禁用会导致 ActualCacheHandler 写无 TTL 永久缓存 → 必须保留
            assertThat(names).contains("TtlHandler", "ActualCacheHandler");
            assertThat(names).doesNotContain("BloomFilterHandler", "SyncLockHandler",
                    "EarlyExpirationHandler", "NullValueHandler");
        }

        @Test
        @DisplayName("protection.enabled=true(default) 保留全部 handler")
        void protectionEnabled_keepsAll() {
            when(properties.getProtection()).thenReturn(
                    new RedisProCacheProperties.ProtectionProperties());

            List<CacheHandler> handlers = List.of(
                    new BloomFilterHandler(), new TtlHandler(), new ActualCacheHandler());
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            assertThat(factory.createChain().getHandlerNames())
                    .contains("BloomFilterHandler", "TtlHandler", "ActualCacheHandler");
        }

        @Test
        @DisplayName("总开关 true + 分项全 null → 全部防护 handler 保留(null 继承)")
        void protectionEnabled_perToggleNulls_inheritAndKeepAll() {
            RedisProCacheProperties.ProtectionProperties protection =
                    new RedisProCacheProperties.ProtectionProperties();
            protection.setBloomFilterEnabled(null);
            protection.setSyncLockEnabled(null);
            protection.setEarlyExpirationEnabled(null);
            protection.setNullValueEnabled(null);

            List<String> names = chainNames(protection);

            assertThat(names).contains("BloomFilterHandler", "SyncLockHandler",
                    "EarlyExpirationHandler", "NullValueHandler", "TtlHandler", "ActualCacheHandler");
        }

        @Test
        @DisplayName("总开关 true + 分项 false → 只关闭对应机制")
        void protectionEnabled_perToggleFalse_disablesOnlyThatMechanism() {
            RedisProCacheProperties.ProtectionProperties protection =
                    new RedisProCacheProperties.ProtectionProperties();
            protection.setSyncLockEnabled(false);

            List<String> names = chainNames(protection);

            assertThat(names).contains("BloomFilterHandler", "TtlHandler", "ActualCacheHandler");
            assertThat(names).doesNotContain("SyncLockHandler");
        }

        @Test
        @DisplayName("总开关 true + 分项 true → 保持开启(显式 true 不改变行为)")
        void protectionEnabled_perToggleTrue_keepsAll() {
            RedisProCacheProperties.ProtectionProperties protection =
                    new RedisProCacheProperties.ProtectionProperties();
            protection.setBloomFilterEnabled(true);
            protection.setSyncLockEnabled(true);
            protection.setEarlyExpirationEnabled(true);
            protection.setNullValueEnabled(true);

            List<String> names = chainNames(protection);

            assertThat(names).contains("BloomFilterHandler", "SyncLockHandler",
                    "EarlyExpirationHandler", "NullValueHandler", "TtlHandler", "ActualCacheHandler");
        }

        @Test
        @DisplayName("总开关 false + 分项 true → 仍关闭(分项不能重新启用)")
        void protectionDisabled_perToggleTrue_cannotReEnable() {
            RedisProCacheProperties.ProtectionProperties protection =
                    new RedisProCacheProperties.ProtectionProperties();
            protection.setEnabled(false);
            protection.setBloomFilterEnabled(true);
            protection.setSyncLockEnabled(true);
            protection.setEarlyExpirationEnabled(true);
            protection.setNullValueEnabled(true);

            List<String> names = chainNames(protection);

            assertThat(names).contains("TtlHandler", "ActualCacheHandler");
            assertThat(names).doesNotContain("BloomFilterHandler", "SyncLockHandler",
                    "EarlyExpirationHandler", "NullValueHandler");
        }

        @Test
        @DisplayName("混合配置 → 只禁用显式 false 的机制,其余保留")
        void protectionEnabled_mixedToggles_disableOnlyExplicitFalse() {
            RedisProCacheProperties.ProtectionProperties protection =
                    new RedisProCacheProperties.ProtectionProperties();
            protection.setBloomFilterEnabled(false);
            protection.setSyncLockEnabled(true);
            protection.setEarlyExpirationEnabled(null);
            protection.setNullValueEnabled(false);

            List<String> names = chainNames(protection);

            assertThat(names).contains("SyncLockHandler", "EarlyExpirationHandler",
                    "TtlHandler", "ActualCacheHandler");
            assertThat(names).doesNotContain("BloomFilterHandler", "NullValueHandler");
        }

        @Test
        @DisplayName("createChain 后修改 properties 不影响已缓存链(单例)")
        void createChain_cachedChain_ignoresPropertyMutation() {
            RedisProCacheProperties.ProtectionProperties protection =
                    new RedisProCacheProperties.ProtectionProperties();
            protection.setBloomFilterEnabled(false);
            List<String> first = chainNames(protection);
            CacheHandlerChain cached = factory.createChain();

            // 链构建后翻转配置:总开关关闭 + 分项重新启用
            protection.setEnabled(false);
            protection.setBloomFilterEnabled(true);

            assertThat(factory.createChain()).isSameAs(cached);
            assertThat(cached.getHandlerNames()).isEqualTo(first);
        }

        private List<String> chainNames(RedisProCacheProperties.ProtectionProperties protection) {
            when(properties.getProtection()).thenReturn(protection);
            when(properties.getDisabledHandlers()).thenReturn(Collections.emptyList());
            List<CacheHandler> handlers = List.of(new BloomFilterHandler(), new SyncLockHandler(),
                    new EarlyExpirationHandler(), new TtlHandler(), new NullValueHandler(),
                    new ActualCacheHandler());
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());
            return factory.createChain().getHandlerNames();
        }

        @Test
        @DisplayName("disableName 派生自 @HandlerPriority 注解,与类名解耦(H1/I3 回归)")
        void protectionDisabled_disableNameFromAnnotation_notClassName() {
            // 故意使用与真实 handler 完全不同的类名,仅靠 @HandlerPriority(BLOOM_FILTER)
            // 关联 disableName="bloom-filter"。证明禁用契约来自枚举注解(单一事实源),
            // 而非类名派生——handler 类重命名不会让 protection 短路静默失效。
            RedisProCacheProperties.ProtectionProperties protection =
                    new RedisProCacheProperties.ProtectionProperties();
            protection.setEnabled(false);
            when(properties.getProtection()).thenReturn(protection);

            List<CacheHandler> handlers = List.of(
                    new OddlyNamedBloomHandler(), new TtlHandler(), new ActualCacheHandler());
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            List<String> names = factory.createChain().getHandlerNames();

            assertThat(names).contains("TtlHandler", "ActualCacheHandler");
            assertThat(names).doesNotContain("OddlyNamedBloomHandler");
        }

        @Test
        @DisplayName("全局 disabled-handlers 也通过注解 disableName 匹配(类名解耦)")
        void globalDisabled_disableNameFromAnnotation_notClassName() {
            // 类名不匹配任何已知模式,但 @HandlerPriority(SYNC_LOCK) → disableName="sync-lock"
            when(properties.getDisabledHandlers()).thenReturn(List.of("sync-lock"));

            List<CacheHandler> handlers = List.of(
                    new WeirdlyNamedLockHandler(), new TtlHandler());
            factory = new CacheHandlerChainFactory(handlers, properties, null, new ChainEngine());

            List<String> names = factory.createChain().getHandlerNames();

            assertThat(names).contains("TtlHandler");
            assertThat(names).doesNotContain("WeirdlyNamedLockHandler");
        }
    }

    @Nested
    @DisplayName("per-handler fired counter wiring (guide §223b)")
    class FiredCounterWiringTests {

        @Test
        @DisplayName("createChain with standard observer beans 注册 resicache.handler.fired 并在 execute 时自增")
        void createChain_withRegistry_attachesAndIncrementsFiredCounter() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            @SuppressWarnings("unchecked")
            ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(registry);

            AbstractCacheHandler probe = new FiredCounterProbe();
            // P1-API-001-C:observer 为有序 Bean,工厂注入 List<ChainObserver> 后单一注册。
            // 4-arg 便捷构造(空 observer)不装配 registry observer — fired counter 走标准 bean。
            factory = new CacheHandlerChainFactory(
                    List.of(probe), properties, provider, new ChainEngine(),
                    List.of(new io.github.davidhlp.spring.cache.redis.cache.FiredCounterChainObserver(registry)));

            CacheHandlerChain chain = factory.createChain();
            CacheContext ctx = CacheContext.of(CacheInput.builder()
                    .operation(CacheOperation.GET)
                    .cacheName("probe-cache")
                    .redisKey("probe:k")
                    .actualKey("probe:k")
                    .build());
            chain.execute(ctx);

            var counters = new ArrayList<>(registry.find("resicache.handler.fired").counters());
            assertThat(counters).as("factory 应为 probe 注册 fired counter").hasSize(1);
            assertThat(counters.get(0).count())
                    .as("probe 被引擎求值一次 → counter 自增 1")
                    .isEqualTo(1.0);
            assertThat(counters.get(0).getId().getTag("handler"))
                    .as("handler tag = 运行时子类 SimpleName")
                    .isEqualTo("FiredCounterProbe");
        }

        static class FiredCounterProbe extends AbstractCacheHandler {
            @Override
            protected boolean shouldHandle(CacheContext context) {
                return true;
            }

            @Override
            protected HandlerResult doHandle(CacheContext context) {
                return HandlerResult.continueWith(CacheResult.success());
            }
        }
    }

    // ========== Test Handler Implementations ==========

    // B1 回归专用:类名精确匹配真实 handler 简名,使 getHandlerDisableName 输出对应 kebab
    // (bloom-filter/sync-lock/early-expiration/null-value/ttl/actual-cache)
    abstract static class NamedHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

    }

    @HandlerPriority(HandlerOrder.BLOOM_FILTER)
    static class BloomFilterHandler extends NamedHandler { }
    @HandlerPriority(HandlerOrder.SYNC_LOCK)
    static class SyncLockHandler extends NamedHandler { }
    @HandlerPriority(HandlerOrder.EARLY_EXPIRATION)
    static class EarlyExpirationHandler extends NamedHandler { }
    @HandlerPriority(HandlerOrder.TTL)
    static class TtlHandler extends NamedHandler { }
    @HandlerPriority(HandlerOrder.NULL_VALUE)
    static class NullValueHandler extends NamedHandler { }
    @HandlerPriority(HandlerOrder.ACTUAL_CACHE)
    static class ActualCacheHandler extends NamedHandler { }

    // H1/I3 回归:类名刻意与真实 handler 不同,证明 disableName 来自注解而非类名派生
    @HandlerPriority(HandlerOrder.BLOOM_FILTER)
    static class OddlyNamedBloomHandler extends NamedHandler { }

    @HandlerPriority(HandlerOrder.SYNC_LOCK)
    static class WeirdlyNamedLockHandler extends NamedHandler { }

    static class TestCacheHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

    }

    static class AnotherTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

    }

    static class YetAnotherTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

    }

    @HandlerPriority(HandlerOrder.BLOOM_FILTER)
    static class BloomFilterTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

    }

    @HandlerPriority(HandlerOrder.SYNC_LOCK)
    static class SyncLockTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

    }

    @HandlerPriority(HandlerOrder.ACTUAL_CACHE)
    static class ActualCacheTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

    }

    @HandlerPriority(HandlerOrder.EARLY_EXPIRATION)
    static class EarlyExpirationTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

    }

    @HandlerPriority(HandlerOrder.TTL)
    static class PriorityTestHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueWith(CacheResult.success());
        }

    }
}
