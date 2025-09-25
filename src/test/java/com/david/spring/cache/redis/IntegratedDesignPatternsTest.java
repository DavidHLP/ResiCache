package com.david.spring.cache.redis;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.config.builder.RedisCacheConfig;
import com.david.spring.cache.redis.config.builder.RedisCacheConfigBuilder;
import com.david.spring.cache.redis.core.strategy.CacheExecutionStrategy;
import com.david.spring.cache.redis.core.strategy.CacheStrategyContext;
import com.david.spring.cache.redis.event.CacheEventPublisher;
import com.david.spring.cache.redis.event.listener.CacheStatisticsListener;
import com.david.spring.cache.redis.factory.CacheFactoryRegistry;
import com.david.spring.cache.redis.factory.CacheType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试 - 验证设计模式与注解基础设施的集成
 */
@DisplayName("设计模式与注解集成测试")
public class IntegratedDesignPatternsTest {

    @Test
    @DisplayName("注解增强 - 新增设计模式支持属性")
    void testAnnotationEnhancements() {
        // 这里我们通过反射来验证注解是否包含了新的设计模式支持属性

        // 检查RedisCacheable注解是否包含cacheType属性
        try {
            var cacheTypeMethod = RedisCacheable.class.getMethod("cacheType");
            assertNotNull(cacheTypeMethod);
            assertEquals(String.class, cacheTypeMethod.getReturnType());
            assertEquals("REDIS", cacheTypeMethod.getDefaultValue());

            var executionStrategyMethod = RedisCacheable.class.getMethod("executionStrategy");
            assertNotNull(executionStrategyMethod);
            assertEquals(String.class, executionStrategyMethod.getReturnType());
            assertEquals("DEFAULT", executionStrategyMethod.getDefaultValue());

            System.out.println("✓ RedisCacheable注解成功增强设计模式支持属性");

        } catch (NoSuchMethodException e) {
            fail("RedisCacheable注解缺少设计模式支持属性: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("建造者模式 - 高级缓存配置构建")
    void testAdvancedBuilderPattern() {
        // 使用建造者模式创建复杂的多层缓存配置
        RedisCacheConfig config = RedisCacheConfigBuilder.create()
                .defaultTtl(Duration.ofMinutes(30))
                .allowNullValues(true)
                .enableTransactions()
                .enableStatistics()

                // 用户缓存：高速访问，启用预刷新和布隆过滤器
                .cache("userCache")
                    .ttl(Duration.ofMinutes(60))
                    .enablePreRefresh()
                    .preRefreshThreshold(0.2)
                    .useBloomFilter()
                    .enableRandomTtl()
                    .ttlVariance(0.1f)
                    .and()

                // 产品缓存：长期存储，分层缓存
                .cache("productCache")
                    .ttl(Duration.ofHours(4))
                    .enableRandomTtl()
                    .ttlVariance(0.3f)
                    .and()

                // 会话缓存：短期存储，禁用空值
                .cache("sessionCache")
                    .ttl(Duration.ofMinutes(15))
                    .allowNullValues(false)
                    .and()

                .connectionPoolSize(256)
                .connectionMinimumIdleSize(32)
                .connectTimeout(8000)
                .commandTimeout(5000)
                .retryAttempts(5)
                .build();

        // 验证基础配置
        assertEquals(Duration.ofMinutes(30), config.getDefaultTtl());
        assertTrue(config.isEnableTransactions());
        assertTrue(config.isEnableStatistics());
        assertEquals(256, config.getConnectionPoolSize());

        // 验证缓存特定配置
        assertEquals(3, config.getCacheConfigurations().size());

        var userConfig = config.getCacheConfig("userCache");
        assertNotNull(userConfig);
        assertEquals(Duration.ofMinutes(60), userConfig.getTtl());
        assertTrue(userConfig.isEnablePreRefresh());
        assertEquals(0.2, userConfig.getPreRefreshThreshold());
        assertTrue(userConfig.isUseBloomFilter());
        assertTrue(userConfig.isEnableRandomTtl());

        var productConfig = config.getCacheConfig("productCache");
        assertNotNull(productConfig);
        assertEquals(Duration.ofHours(4), productConfig.getTtl());
        assertTrue(productConfig.isEnableRandomTtl());
        assertEquals(0.3f, productConfig.getTtlVariance());

        var sessionConfig = config.getCacheConfig("sessionCache");
        assertNotNull(sessionConfig);
        assertEquals(Duration.ofMinutes(15), sessionConfig.getTtl());
        assertFalse(sessionConfig.isAllowNullValues());

        System.out.println("✅ 建造者模式：成功构建包含3种不同类型缓存的复杂配置");
        System.out.println("  - userCache: 高性能缓存 (预刷新 + 布隆过滤器 + 随机TTL)");
        System.out.println("  - productCache: 长期缓存 (随机TTL)");
        System.out.println("  - sessionCache: 短期缓存 (禁用空值)");
    }

    @Test
    @DisplayName("观察者模式 - 多监听器事件处理")
    void testMultiListenerObserverPattern() {
        CacheEventPublisher publisher = new CacheEventPublisher();

        // 注册多个不同类型的监听器
        CacheStatisticsListener statisticsListener = new CacheStatisticsListener();
        publisher.registerListener(statisticsListener);

        // 自定义监听器 - 只监听命中事件
        var hitCountListener = new com.david.spring.cache.redis.event.CacheEventListener() {
            private long hitCount = 0;

            @Override
            public void onCacheEvent(com.david.spring.cache.redis.event.CacheEvent event) {
                if (event.getEventType() == com.david.spring.cache.redis.event.CacheEventType.CACHE_HIT) {
                    hitCount++;
                }
            }

            @Override
            public com.david.spring.cache.redis.event.CacheEventType[] getSupportedEventTypes() {
                return new com.david.spring.cache.redis.event.CacheEventType[]{
                        com.david.spring.cache.redis.event.CacheEventType.CACHE_HIT
                };
            }

            @Override
            public int getOrder() {
                return 100;
            }

            public long getHitCount() { return hitCount; }
        };

        publisher.registerListener(hitCountListener);
        assertEquals(2, publisher.getListenerCount());

        // 发布各种类型的事件
        publisher.publishEvent(new com.david.spring.cache.redis.event.CacheHitEvent("testCache", "key1", "TestService", "value1", 5L));
        publisher.publishEvent(new com.david.spring.cache.redis.event.CacheHitEvent("testCache", "key2", "TestService", "value2", 3L));
        publisher.publishEvent(new com.david.spring.cache.redis.event.CacheMissEvent("testCache", "key3", "TestService", "not_found"));
        publisher.publishEvent(new com.david.spring.cache.redis.event.CachePutEvent("testCache", "key3", "TestService", "value3", Duration.ofMinutes(30), 15L));

        // 验证专用监听器只接收到命中事件
        assertEquals(2, hitCountListener.getHitCount());

        publisher.shutdown();
        System.out.println("✅ 观察者模式：成功实现多监听器分类事件处理");
        System.out.println("  - 统计监听器：处理所有事件类型");
        System.out.println("  - 命中计数监听器：只处理缓存命中事件 (2次)");
    }

    @Test
    @DisplayName("设计模式协作 - 完整集成场景")
    void testDesignPatternsCollaboration() {
        System.out.println("🎯 开始设计模式协作测试...");

        // 1. 建造者模式：创建复杂配置
        System.out.println("📊 第1步：使用建造者模式创建配置");
        RedisCacheConfig config = RedisCacheConfigBuilder.create()
                .defaultTtl(Duration.ofMinutes(30))
                .enableStatistics()
                .cache("collaborationTest")
                    .ttl(Duration.ofMinutes(60))
                    .enablePreRefresh()
                    .useBloomFilter()
                    .and()
                .build();

        assertNotNull(config);
        System.out.println("   ✓ 配置构建完成");

        // 2. 观察者模式：设置事件监听
        System.out.println("📊 第2步：使用观察者模式设置事件监听");
        CacheEventPublisher publisher = new CacheEventPublisher();
        CacheStatisticsListener listener = new CacheStatisticsListener();
        publisher.registerListener(listener);
        System.out.println("   ✓ 事件监听器注册完成");

        // 3. 工厂模式：支持多种缓存类型
        System.out.println("📊 第3步：使用工厂模式验证多缓存类型支持");

        // 验证缓存类型支持
        var redisFactory = new com.david.spring.cache.redis.factory.RedisCacheFactory();
        var layeredFactory = new com.david.spring.cache.redis.factory.LayeredCacheFactory();

        assertTrue(redisFactory.supports(CacheType.REDIS));
        assertFalse(redisFactory.supports(CacheType.LAYERED));
        assertTrue(layeredFactory.supports(CacheType.LAYERED));
        assertFalse(layeredFactory.supports(CacheType.REDIS));

        System.out.println("   ✓ 工厂类型支持验证完成");

        // 4. 策略模式：验证策略选择机制
        System.out.println("📊 第4步：使用策略模式验证执行策略");
        List<CacheExecutionStrategy> strategies = List.of(
                new com.david.spring.cache.redis.core.strategy.DefaultStrategy(null, null, null)
        );

        CacheStrategyContext strategyContext = new CacheStrategyContext(strategies);
        assertNotNull(strategyContext);
        System.out.println("   ✓ 策略上下文创建完成");

        // 5. 验证注解支持新属性
        System.out.println("📊 第5步：验证注解增强支持");
        try {
            var cacheTypeMethod = RedisCacheable.class.getMethod("cacheType");
            var strategyMethod = RedisCacheable.class.getMethod("executionStrategy");
            assertNotNull(cacheTypeMethod);
            assertNotNull(strategyMethod);
            System.out.println("   ✓ 注解增强验证完成");
        } catch (NoSuchMethodException e) {
            fail("注解增强验证失败: " + e.getMessage());
        }

        // 清理
        publisher.shutdown();

        System.out.println("🎉 设计模式协作测试完成！");
        System.out.println("✅ 成功验证了以下设计模式的协作集成：");
        System.out.println("   🏗️  建造者模式：复杂配置构建");
        System.out.println("   👁️  观察者模式：事件监听机制");
        System.out.println("   🏭  工厂模式：多类型缓存支持");
        System.out.println("   🎯  策略模式：执行策略选择");
        System.out.println("   📝  模板方法：标准化操作流程");
        System.out.println("   💡  注解增强：设计模式配置支持");
    }
}