package com.david.spring.cache.redis.config;

import com.david.spring.cache.redis.aspect.RedisCacheAspect;
import com.david.spring.cache.redis.expression.CacheExpressionEvaluator;
import com.david.spring.cache.redis.generator.CacheKeyGenerator;
import com.david.spring.cache.redis.manager.RedisCacheManager;
import com.david.spring.cache.redis.template.CacheOperationTemplate;
import com.david.spring.cache.redis.template.StandardCacheOperationTemplate;
import jakarta.annotation.PostConstruct;
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
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;
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
@ComponentScan(basePackages = {
		"com.david.spring.cache.redis.template"
})
public class RedisCacheAutoConfiguration {

	private final RedisCacheProperties properties;

	public RedisCacheAutoConfiguration(RedisCacheProperties properties) {
		this.properties = properties;
		log.info("Initializing RedisCacheAutoConfiguration with properties: {}", properties);
	}

	@PostConstruct
	public void init() {
		log.info("Initializing Cache Design Pattern Configuration");
	}

	/**
	 * 记录Bean创建日志的通用方法
	 */
	private <T> T logBeanCreation(T bean, String beanName, String description) {
		log.info("Created {} - {}", beanName, description);
		return bean;
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

		return logBeanCreation(template, "RedisCacheTemplate",
			"StringRedisSerializer for keys and GenericJackson2JsonRedisSerializer for CachedValue");
	}

	@Bean
	@ConditionalOnMissingBean(name = "redisCacheKeyGenerator")
	public KeyGenerator redisCacheKeyGenerator() {
		return logBeanCreation(new CacheKeyGenerator(), "RedisCacheKeyGenerator", "for cache key generation");
	}

	@Bean
	@ConditionalOnMissingBean
	public CacheExpressionEvaluator cacheExpressionEvaluator() {
		return logBeanCreation(new CacheExpressionEvaluator(), "CacheExpressionEvaluator", "for cache expression evaluation");
	}

	@Bean
	@ConditionalOnMissingBean
	public RedisCacheManager redisCacheManager(@Qualifier("redisCacheTemplate") RedisTemplate<String, Object> redisCacheTemplate) {
		Map<String, Duration> cacheConfigurations = new HashMap<>();

		for (Map.Entry<String, RedisCacheProperties.CacheConfiguration> entry : properties.getCaches().entrySet()) {
			String cacheName = entry.getKey();
			RedisCacheProperties.CacheConfiguration config = entry.getValue();
			Duration ttl = config.getTtl();
			cacheConfigurations.put(cacheName, ttl);
		}

		RedisCacheManager cacheManager = new RedisCacheManager(
				redisCacheTemplate,
				properties.getDefaultTtl(),
				properties.isAllowNullValues(),
				cacheConfigurations
		);

		cacheManager.setTransactionAware(properties.isEnableTransactions());

		return logBeanCreation(cacheManager, "RedisCacheManager",
			String.format("with %d cache configurations, default TTL: %s, allowNullValues: %s",
				cacheConfigurations.size(), properties.getDefaultTtl(), properties.isAllowNullValues()));
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

		return logBeanCreation(Redisson.create(config), "RedissonClient", "with single server configuration");
	}

	@Bean
	@ConditionalOnMissingBean
	@Primary
	public CacheOperationTemplate cacheOperationTemplate(RedisCacheManager redisCacheManager) {
		return logBeanCreation(new StandardCacheOperationTemplate(redisCacheManager),
			"StandardCacheOperationTemplate", "as primary cache operation template");
	}

	@Bean
	@ConditionalOnMissingBean
	public RedisCacheAspect redisCacheAspect(RedisCacheManager redisCacheManager,
	                                         @Qualifier("redisCacheKeyGenerator") KeyGenerator keyGenerator,
	                                         RedissonClient redissonClient,
	                                         CacheOperationTemplate operationTemplate) {
		// 为RedisCacheManager设置模板支持
		redisCacheManager.setOperationTemplate(operationTemplate);

		return logBeanCreation(new RedisCacheAspect(redisCacheManager, keyGenerator, redissonClient),
			"RedisCacheAspect", "with direct cache operations");
	}

	@Bean
	@ConditionalOnMissingBean(name = "redisCacheHealthIndicator")
	@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
	public RedisCacheHealthIndicator redisCacheHealthIndicator(@Qualifier("redisCacheTemplate") RedisTemplate<String, Object> redisCacheTemplate) {
		return new RedisCacheHealthIndicator(redisCacheTemplate);
	}

}