package com.david.spring.cache.redis.config;

import com.david.spring.cache.redis.annotation.RedisCacheAnnotationParser;
import com.david.spring.cache.redis.aspect.RedisCacheAspect;
import com.david.spring.cache.redis.core.writer.RedisProCacheWriter;
import com.david.spring.cache.redis.interceptor.RedisCacheInterceptor;
import com.david.spring.cache.redis.manager.RedisProCacheManager;
import com.david.spring.cache.redis.register.RedisCacheRegister;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.annotation.SpringCacheAnnotationParser;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Slf4j
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass({RedisOperations.class})
@EnableCaching
@EnableAspectJAutoProxy
public class RedisCacheAutoConfiguration {

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
	public RedisCacheRegister redisCacheRegister() {
		return logBeanCreation(new RedisCacheRegister(), "RedisCacheRegister", "cache operation registry");
	}

	@Bean
	@ConditionalOnMissingBean
	public RedisProCacheWriter redisProCacheWriter(RedisTemplate<String, Object> redisCacheTemplate) {
		return logBeanCreation(new RedisProCacheWriter(redisCacheTemplate, org.springframework.data.redis.cache.CacheStatisticsCollector.none()),
				"RedisProCacheWriter", "custom cache writer with CachedValue support");
	}

	@Bean
	@ConditionalOnMissingBean
	public RedisCacheConfiguration redisCacheConfiguration() {
		return logBeanCreation(
				RedisCacheConfiguration.defaultCacheConfig()
						.entryTtl(Duration.ofMinutes(30))
						.serializeKeysWith(org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
						.serializeValuesWith(org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())),
				"RedisCacheConfiguration", "with 30 minutes TTL and JSON serialization");
	}

	@Bean
	@ConditionalOnMissingBean(CacheManager.class)
	public RedisProCacheManager cacheManager(RedisProCacheWriter redisProCacheWriter,
	                                         RedisCacheConfiguration redisCacheConfiguration) {
		return logBeanCreation(new RedisProCacheManager(redisProCacheWriter, redisCacheConfiguration),
				"RedisProCacheManager", "custom cache manager");
	}

	@Bean
	@ConditionalOnMissingBean
	public RedisCacheAspect redisCacheAspect(RedisCacheRegister redisCacheRegister, KeyGenerator keyGenerator) {
		return logBeanCreation(new RedisCacheAspect(redisCacheRegister, keyGenerator),
				"RedisCacheAspect", "AOP support for @RedisCacheable annotation");
	}

	@Bean
	@ConditionalOnMissingBean
	public KeyGenerator keyGenerator() {
		return logBeanCreation(new SimpleKeyGenerator(),
				"KeyGenerator", "SimpleKeyGenerator for cache operations");
	}

	@Bean
	@ConditionalOnMissingBean
	public SpringCacheAnnotationParser redisCacheAnnotationParser() {
		return logBeanCreation(new RedisCacheAnnotationParser(),
				"RedisCacheAnnotationParser", "AnnotationParser for @RedisCacheable annotation");
	}
}