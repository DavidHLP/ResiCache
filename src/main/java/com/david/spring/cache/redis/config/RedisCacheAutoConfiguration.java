package com.david.spring.cache.redis.config;

import com.david.spring.cache.redis.aspect.RedisCacheAspect;
import com.david.spring.cache.redis.chain.CacheHandler;
import com.david.spring.cache.redis.chain.CacheHandlerChain;
import com.david.spring.cache.redis.chain.CacheOperationHelper;
import com.david.spring.cache.redis.chain.handlers.*;
import com.david.spring.cache.redis.locks.impl.RedissonDistributedLockManager;
import com.david.spring.cache.redis.locks.interfaces.DistributedLockManager;
import com.david.spring.cache.redis.operations.RedisHashOperations;
import com.david.spring.cache.redis.operations.RedisStringOperations;
import com.david.spring.cache.redis.operations.RedisUtils;
import com.david.spring.cache.redis.operations.impl.RedisHashOperationsImpl;
import com.david.spring.cache.redis.operations.impl.RedisStringOperationsImpl;
import com.david.spring.cache.redis.parser.CacheKeyGenerator;
import com.david.spring.cache.redis.parser.CacheNameGenerator;
import com.david.spring.cache.redis.protection.CacheAvalancheProtection;
import com.david.spring.cache.redis.protection.CacheBreakdownProtection;
import com.david.spring.cache.redis.protection.CachePenetrationProtection;
import com.david.spring.cache.redis.serialization.RedisSerialization;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Redis Cache 自动配置类 提供基于 Spring Cache 的 Redis 缓存支持，集成责任链模式 */
@Slf4j
@EnableCaching
@AutoConfiguration
@ComponentScan(basePackages = "com.david.spring.cache.redis")
@ConditionalOnClass({RedisTemplate.class, RedisCacheManager.class, RedissonClient.class})
@EnableConfigurationProperties(RedisProperties.class)
public class RedisCacheAutoConfiguration {

	/** 配置自定义 Redis 缓存管理器 使用 CustomRedisCacheManager 支持动态 TTL 设置 */
	@Bean
	@ConditionalOnMissingBean
	public CacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
		ObjectMapper om = new ObjectMapper();
		// 允许序列化所有可见性级别的字段，包括私有字段
		om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
		// 开启默认类型信息，以 PROPERTY 的形式在 JSON 中添加 @class 属性
		om.activateDefaultTyping(
				LaissezFaireSubTypeValidator.instance,
				ObjectMapper.DefaultTyping.NON_FINAL,
				JsonTypeInfo.As.PROPERTY
		);

		// 使用默认的缓存配置
		RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
				.entryTtl(Duration.ofMinutes(5)) // 默认5分钟过期
				.disableCachingNullValues() // 禁用缓存空值
				.serializeValuesWith(
						RedisSerializationContext.SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(om, Object.class)));
		// 使用自定义的 CustomRedisCacheManager
		RedisCacheWriter redisCacheWriter = RedisCacheWriter.nonLockingRedisCacheWriter(redisConnectionFactory);
		return CustomRedisCacheManager.builder(redisConnectionFactory).cacheDefaults(defaultConfig).cacheWriter(redisCacheWriter)
				.build();
	}

	/** 配置 RedisTemplate */
	@Bean
	@ConditionalOnMissingBean
	public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);

		// key 序列化器
		StringRedisSerializer stringSerializer = new StringRedisSerializer();
		template.setKeySerializer(stringSerializer);
		template.setHashKeySerializer(stringSerializer);

		ObjectMapper om = new ObjectMapper();
		// 允许序列化所有可见性级别的字段，包括私有字段
		om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
		// 开启默认类型信息，以 PROPERTY 的形式在 JSON 中添加 @class 属性
		om.activateDefaultTyping(
				LaissezFaireSubTypeValidator.instance,
				ObjectMapper.DefaultTyping.NON_FINAL,
				JsonTypeInfo.As.PROPERTY
		);


		// 使用新的构造函数传入 ObjectMapper
		Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<>(om, Object.class);

		// value 序列化器
		template.setValueSerializer(jackson2JsonRedisSerializer);
		template.setHashValueSerializer(jackson2JsonRedisSerializer);
		template.afterPropertiesSet();

		return template;
	}

	/** 配置 Redisson 客户端 提供分布式锁、分布式集合等高级功能 */
	@Bean
	@ConditionalOnMissingBean
	public RedissonClient redissonClient(RedisProperties redisProperties) {
		Config config = new Config();

		// 单机模式配置
		String redisUrl = String.format(
				"redis://%s:%d", redisProperties.getHost(), redisProperties.getPort());

		config.useSingleServer()
				.setAddress(redisUrl)
				.setDatabase(redisProperties.getDatabase())
				.setConnectionPoolSize(64)
				.setConnectionMinimumIdleSize(10)
				.setIdleConnectionTimeout(10000)
				.setConnectTimeout(10000)
				.setTimeout(3000)
				.setRetryAttempts(3)
				.setRetryInterval(1500);

		// 设置密码（如果有）
		if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
			config.useSingleServer().setPassword(redisProperties.getPassword());
		}

		return Redisson.create(config);
	}

	/** 配置默认的键生成器 */
	@Bean
	@ConditionalOnMissingBean
	public KeyGenerator keyGenerator() {
		return new SimpleKeyGenerator();
	}

	/** 配置Redis序列化工具 */
	@Bean
	@ConditionalOnMissingBean
	public RedisSerialization redisSerialization() {
		return new RedisSerialization();
	}

	/** 配置Redis操作工具类 */
	@Bean
	@ConditionalOnMissingBean
	public RedisStringOperations redisStringOperations(
			RedisTemplate<String, Object> redisTemplate, RedisSerialization redisSerialization) {
		return new RedisStringOperationsImpl(redisTemplate, redisSerialization);
	}

	@Bean
	@ConditionalOnMissingBean
	public RedisHashOperations redisHashOperations(
			RedisTemplate<String, Object> redisTemplate, RedisSerialization redisSerialization) {
		return new RedisHashOperationsImpl(redisTemplate, redisSerialization);
	}

	@Bean
	@ConditionalOnMissingBean
	public RedisUtils redisUtils(
			RedisStringOperations stringOperations, RedisHashOperations hashOperations) {
		return new RedisUtils(stringOperations, hashOperations);
	}

	/** 配置分布式锁管理器 */
	@Bean
	@ConditionalOnMissingBean
	public DistributedLockManager distributedLockManager(RedissonClient redissonClient) {
		return new RedissonDistributedLockManager(redissonClient);
	}

	/** 配置缓存键生成处理器 */
	@Bean
	@ConditionalOnMissingBean
	public CacheKeyGeneratorHandler cacheKeyGeneratorHandler(
			CacheKeyGenerator cacheKeyGenerator,
			CacheNameGenerator cacheNameGenerator) {
		return new CacheKeyGeneratorHandler(cacheKeyGenerator, cacheNameGenerator);
	}

	/** 配置缓存读取处理器 */
	@Bean
	@ConditionalOnMissingBean
	public CacheReadHandler cacheReadHandler(CacheOperationHelper cacheOperationHelper) {
		return new CacheReadHandler(cacheOperationHelper);
	}

	/** 配置缓存击穿保护处理器 */
	@Bean
	@ConditionalOnMissingBean
	public CacheBreakdownHandler cacheBreakdownHandler(CacheBreakdownProtection cacheBreakdownProtection,
	                                                   CacheOperationHelper cacheOperationHelper) {
		return new CacheBreakdownHandler(cacheBreakdownProtection, cacheOperationHelper);
	}

	/** 配置缓存穿透保护处理器 */
	@Bean
	@ConditionalOnMissingBean
	public CachePenetrationHandler cachePenetrationHandler(CachePenetrationProtection cachePenetrationProtection) {
		return new CachePenetrationHandler(cachePenetrationProtection);
	}

	/** 配置缓存写入处理器 */
	@Bean
	@ConditionalOnMissingBean
	public CacheWriteHandler cacheWriteHandler(CacheOperationHelper cacheOperationHelper,
	                                           CachePenetrationProtection cachePenetrationProtection) {
		return new CacheWriteHandler(cacheOperationHelper, cachePenetrationProtection);
	}

	/** 配置缓存雪崩保护处理器 */
	@Bean
	@ConditionalOnMissingBean
	public CacheAvalancheHandler cacheAvalancheHandler(CacheAvalancheProtection cacheAvalancheProtection,
	                                                   CacheOperationHelper cacheOperationHelper) {
		return new CacheAvalancheHandler(cacheAvalancheProtection, cacheOperationHelper);
	}

	/** 配置缓存处理器责任链 */
	@Bean
	@ConditionalOnMissingBean
	public CacheHandlerChain cacheHandlerChain(
			CacheKeyGeneratorHandler cacheKeyGeneratorHandler,
			CacheReadHandler cacheReadHandler,
			CacheBreakdownHandler cacheBreakdownHandler,
			CachePenetrationHandler cachePenetrationHandler,
			CacheWriteHandler cacheWriteHandler,
			CacheAvalancheHandler cacheAvalancheHandler) {

		// 按照责任链顺序创建处理器列表
		Map<String, CacheHandler> handlers = new LinkedHashMap<>();
		handlers.put("cacheKeyGeneratorHandler", cacheKeyGeneratorHandler); // 1. 生成缓存键
		handlers.put("cacheReadHandler", cacheReadHandler); // 2. 读取缓存
		handlers.put("cacheBreakdownHandler", cacheBreakdownHandler); // 3. 击穿保护
		handlers.put("cachePenetrationHandler", cachePenetrationHandler); // 5. 穿透保护
		handlers.put("cacheWriteHandler", cacheWriteHandler); // 6. 写入缓存
		handlers.put("cacheAvalancheHandler", cacheAvalancheHandler); // 7. 雪崩保护

		return new CacheHandlerChain(handlers);
	}

	/** 配置Redis缓存AOP切面 */
	@Bean
	@ConditionalOnMissingBean
	public RedisCacheAspect redisCacheAspect(CacheHandlerChain cacheHandlerChain) {
		return new RedisCacheAspect(cacheHandlerChain);
	}
}
