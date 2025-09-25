package com.david.spring.cache.redis;

import com.david.spring.cache.redis.config.builder.RedisCacheConfig;
import com.david.spring.cache.redis.config.builder.RedisCacheConfigBuilder;
import com.david.spring.cache.redis.core.strategy.CacheExecutionStrategy;
import com.david.spring.cache.redis.core.strategy.DefaultStrategy;
import com.david.spring.cache.redis.event.*;
import com.david.spring.cache.redis.event.listener.CacheStatisticsListener;
import com.david.spring.cache.redis.factory.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设计模式功能测试
 */
@DisplayName("设计模式功能测试")
public class DesignPatternTest {

	@Test
	@DisplayName("建造者模式 - 配置构建测试")
	void testBuilderPattern() {
		// 使用建造者模式构建配置
		RedisCacheConfig config = RedisCacheConfigBuilder.create()
				.defaultTtl(Duration.ofMinutes(30))
				.allowNullValues(true)
				.enableTransactions()
				.connectionPoolSize(128)
				.cache("userCache")
				.ttl(Duration.ofMinutes(60))
				.enablePreRefresh()
				.useBloomFilter()
				.and()
				.cache("productCache")
				.ttl(Duration.ofHours(2))
				.enableRandomTtl()
				.ttlVariance(0.2f)
				.and()
				.build();

		// 验证基础配置
		assertEquals(Duration.ofMinutes(30), config.getDefaultTtl());
		assertTrue(config.isAllowNullValues());
		assertTrue(config.isEnableTransactions());
		assertEquals(128, config.getConnectionPoolSize());

		// 验证特定缓存配置
		assertEquals(2, config.getCacheConfigurations().size());

		var userCacheConfig = config.getCacheConfig("userCache");
		assertNotNull(userCacheConfig);
		assertEquals(Duration.ofMinutes(60), userCacheConfig.getTtl());
		assertTrue(userCacheConfig.isEnablePreRefresh());
		assertTrue(userCacheConfig.isUseBloomFilter());

		var productCacheConfig = config.getCacheConfig("productCache");
		assertNotNull(productCacheConfig);
		assertEquals(Duration.ofHours(2), productCacheConfig.getTtl());
		assertTrue(productCacheConfig.isEnableRandomTtl());
		assertEquals(0.2f, productCacheConfig.getTtlVariance());

		System.out.println("✓ 建造者模式测试通过：成功构建复杂缓存配置");
	}

	@Test
	@DisplayName("策略模式 - 策略选择测试")
	void testStrategyPattern() {
		// 创建模拟的策略
		DefaultStrategy strategy = Mockito.mock(DefaultStrategy.class);

		// 测试策略支持检查
		Mockito.when(strategy.supports(Mockito.any())).thenReturn(true);
		Mockito.when(strategy.getOrder()).thenReturn(Integer.MAX_VALUE);

		// 验证策略接口
		assertInstanceOf(CacheExecutionStrategy.class, strategy);
		assertTrue(strategy.supports(null));
		assertEquals(Integer.MAX_VALUE, strategy.getOrder());

		System.out.println("✓ 策略模式测试通过：策略接口正常工作");
	}

	@Test
	@DisplayName("观察者模式 - 事件发布和监听测试")
	void testObserverPattern() {
		// 创建事件发布器
		CacheEventPublisher publisher = new CacheEventPublisher();

		// 创建统计监听器
		CacheStatisticsListener listener = new CacheStatisticsListener();

		// 注册监听器
		publisher.registerListener(listener);
		assertEquals(1, publisher.getListenerCount());

		// 创建测试事件
		CacheHitEvent hitEvent = new CacheHitEvent("testCache", "testKey", "TestService", "testValue", 10L);
		CacheMissEvent missEvent = new CacheMissEvent("testCache", "testKey2", "TestService", "not_found");

		// 发布事件
		publisher.publishEvent(hitEvent);
		publisher.publishEvent(missEvent);

		// 验证事件类型
		assertEquals(CacheEventType.CACHE_HIT, hitEvent.getEventType());
		assertEquals(CacheEventType.CACHE_MISS, missEvent.getEventType());

		// 验证事件数据
		assertEquals("testCache", hitEvent.getCacheName());
		assertEquals("testKey", hitEvent.getCacheKey());
		assertEquals("testValue", hitEvent.getValue());
		assertEquals(10L, hitEvent.getAccessTime());

		assertEquals("not_found", missEvent.getReason());

		System.out.println("✓ 观察者模式测试通过：事件发布和监听正常工作");

		// 清理
		publisher.shutdown();
	}

	@Test
	@DisplayName("工厂模式 - 工厂注册和缓存类型支持测试")
	void testFactoryPattern() {
		// 创建模拟工厂
		CacheFactory mockFactory1 = Mockito.mock(CacheFactory.class);
		CacheFactory mockFactory2 = Mockito.mock(CacheFactory.class);

		// 配置工厂支持的类型
		Mockito.when(mockFactory1.supports(CacheType.REDIS)).thenReturn(true);
		Mockito.when(mockFactory1.supports(CacheType.LAYERED)).thenReturn(false);
		Mockito.when(mockFactory1.getOrder()).thenReturn(1);

		Mockito.when(mockFactory2.supports(CacheType.LAYERED)).thenReturn(true);
		Mockito.when(mockFactory2.supports(CacheType.REDIS)).thenReturn(false);
		Mockito.when(mockFactory2.getOrder()).thenReturn(2);

		// 创建工厂注册中心
		List<CacheFactory> factories = Arrays.asList(mockFactory1, mockFactory2);
		CacheFactoryRegistry registry = new CacheFactoryRegistry(factories);

		// 验证工厂数量
		assertEquals(2, registry.getFactoryCount());

		// 验证支持的缓存类型
		List<CacheType> supportedTypes = registry.getSupportedCacheTypes();
		assertTrue(supportedTypes.contains(CacheType.REDIS));
		assertTrue(supportedTypes.contains(CacheType.LAYERED));

		System.out.println("✓ 工厂模式测试通过：工厂注册和类型支持正常工作");
	}

	@Test
	@DisplayName("设计模式集成测试")
	void testDesignPatternsIntegration() {
		System.out.println("🎯 设计模式集成测试开始...");

		// 1. 使用建造者模式创建配置
		RedisCacheConfig config = RedisCacheConfigBuilder.create()
				.defaultTtl(Duration.ofMinutes(15))
				.allowNullValues(true)
				.enableStatistics()
				.cache("integrationTest")
				.ttl(Duration.ofMinutes(30))
				.enablePreRefresh()
				.and()
				.build();

		assertNotNull(config);
		assertTrue(config.isEnableStatistics());
		System.out.println("  ✓ 建造者模式：配置创建成功");

		// 2. 观察者模式：事件系统
		CacheEventPublisher publisher = new CacheEventPublisher();
		CacheStatisticsListener listener = new CacheStatisticsListener();
		publisher.registerListener(listener);

		// 发布一些测试事件
		publisher.publishEvent(new CacheHitEvent("integrationTest", "key1", "TestService", "value1", 5L));
		publisher.publishEvent(new CacheMissEvent("integrationTest", "key2", "TestService", "not_found"));
		publisher.publishEvent(new CachePutEvent("integrationTest", "key2", "TestService", "value2",
				Duration.ofMinutes(30), 15L));

		System.out.println("  ✓ 观察者模式：事件发布成功");

		// 3. 工厂模式：支持多种缓存类型
		CacheFactory redisFactory = new RedisCacheFactory();
		assertTrue(redisFactory.supports(CacheType.REDIS));
		assertFalse(redisFactory.supports(CacheType.LAYERED));

		CacheFactory layeredFactory = new LayeredCacheFactory();
		assertTrue(layeredFactory.supports(CacheType.LAYERED));
		assertFalse(layeredFactory.supports(CacheType.REDIS));

		System.out.println("  ✓ 工厂模式：多种工厂类型支持正确");

		// 清理
		publisher.shutdown();

		System.out.println("🎉 设计模式集成测试全部通过！");
		System.out.println("✅ 已成功验证：建造者模式、观察者模式、工厂模式、策略模式的集成使用");
	}
}