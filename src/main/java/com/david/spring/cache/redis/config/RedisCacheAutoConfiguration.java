package com.david.spring.cache.redis.config;

import com.david.spring.cache.redis.aspect.RedisCacheAspect;
import com.david.spring.cache.redis.core.CacheExpressionEvaluator;
import com.david.spring.cache.redis.core.CacheKeyGenerator;
import com.david.spring.cache.redis.core.RedisCacheManager;
import com.david.spring.cache.redis.core.strategy.CacheStrategyContext;
import com.david.spring.cache.redis.event.CacheEventPublisher;
import com.david.spring.cache.redis.factory.CacheFactoryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass({RedisOperations.class})
@ConditionalOnProperty(prefix = "spring.redis.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RedisCacheProperties.class)
@EnableCaching
@EnableAspectJAutoProxy
@Import(CacheDesignPatternConfiguration.class)
public class RedisCacheAutoConfiguration {

	private final RedisCacheProperties properties;

	public RedisCacheAutoConfiguration(RedisCacheProperties properties) {
		this.properties = properties;
		log.info("Initializing RedisCacheAutoConfiguration with properties: {}", properties);
	}

	@Bean
	@ConditionalOnMissingBean(name = "redisCacheTemplate")
	public RedisTemplate<String, Object> redisCacheTemplate(RedisConnectionFactory redisConnectionFactory) {
		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(redisConnectionFactory);

		StringRedisSerializer stringSerializer = new StringRedisSerializer();
		GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

		template.setKeySerializer(stringSerializer);
		template.setHashKeySerializer(stringSerializer);
		template.setValueSerializer(jsonSerializer);
		template.setHashValueSerializer(jsonSerializer);

		template.setDefaultSerializer(jsonSerializer);
		template.setEnableDefaultSerializer(true);

		template.afterPropertiesSet();

		log.info("Created RedisCacheTemplate with StringRedisSerializer for keys and GenericJackson2JsonRedisSerializer for CachedValue");
		return template;
	}

	@Bean
	@ConditionalOnMissingBean(name = "redisCacheKeyGenerator")
	public KeyGenerator redisCacheKeyGenerator() {
		CacheKeyGenerator keyGenerator = new CacheKeyGenerator();
		log.info("Created RedisCacheKeyGenerator");
		return keyGenerator;
	}

	@Bean
	@ConditionalOnMissingBean
	public CacheExpressionEvaluator cacheExpressionEvaluator() {
		log.info("Created CacheExpressionEvaluator");
		return new CacheExpressionEvaluator();
	}

	@Bean
	@ConditionalOnMissingBean
	public RedisCacheManager redisCacheManager(@Qualifier("redisCacheTemplate") RedisTemplate<String, Object> redisCacheTemplate) {
		Map<String, Duration> cacheConfigurations = new HashMap<>();

		for (Map.Entry<String, RedisCacheProperties.CacheConfiguration> entry : properties.getCaches().entrySet()) {
			String cacheName = entry.getKey();
			RedisCacheProperties.CacheConfiguration config = entry.getValue();
			Duration ttl = config.getTtl() != null ? config.getTtl() : properties.getDefaultTtl();
			cacheConfigurations.put(cacheName, ttl);
		}

		RedisCacheManager cacheManager = new RedisCacheManager(
				redisCacheTemplate,
				properties.getDefaultTtl(),
				properties.isAllowNullValues(),
				cacheConfigurations
		);

		cacheManager.setTransactionAware(properties.isEnableTransactions());

		log.info("Created RedisCacheManager with {} cache configurations, default TTL: {}, allowNullValues: {}",
				cacheConfigurations.size(), properties.getDefaultTtl(), properties.isAllowNullValues());

		return cacheManager;
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnClass(RedissonClient.class)
	public RedissonClient redissonClient(RedisProperties redisProperties) {
		Config config = new Config();
		String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();
		config.useSingleServer()
				.setAddress(address)
				.setPassword(redisProperties.getPassword())
				.setDatabase(redisProperties.getDatabase())
				.setConnectionPoolSize(64)
				.setConnectionMinimumIdleSize(10)
				.setIdleConnectionTimeout(10000)
				.setConnectTimeout(10000)
				.setTimeout(3000)
				.setRetryAttempts(3)
				.setRetryInterval(1500);

		RedissonClient redissonClient = Redisson.create(config);
		log.info("Created RedissonClient with single server configuration");
		return redissonClient;
	}

	@Bean
	@ConditionalOnMissingBean
	public RedisCacheAspect redisCacheAspect(RedisCacheManager redisCacheManager,
	                                         @Qualifier("redisCacheKeyGenerator") KeyGenerator keyGenerator,
	                                         RedissonClient redissonClient,
	                                         CacheStrategyContext strategyContext,
	                                         CacheEventPublisher eventPublisher,
	                                         CacheFactoryRegistry factoryRegistry) {
		// 为RedisCacheManager设置工厂支持
		redisCacheManager.setCacheFactoryRegistry(factoryRegistry);

		RedisCacheAspect aspect = new RedisCacheAspect(redisCacheManager, keyGenerator, redissonClient,
				strategyContext, eventPublisher);
		log.info("Created RedisCacheAspect with design patterns support");
		return aspect;
	}

	@Bean
	@ConditionalOnMissingBean(name = "redisCacheHealthIndicator")
	@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
	public RedisCacheHealthIndicator redisCacheHealthIndicator(@Qualifier("redisCacheTemplate") RedisTemplate<String, Object> redisCacheTemplate) {
		return new RedisCacheHealthIndicator(redisCacheTemplate);
	}
}