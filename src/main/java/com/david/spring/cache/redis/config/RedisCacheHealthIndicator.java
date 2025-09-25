package com.david.spring.cache.redis.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;

@Slf4j
public class RedisCacheHealthIndicator implements HealthIndicator {

	private final RedisTemplate<String, Object> redisTemplate;

	public RedisCacheHealthIndicator(RedisTemplate<String, Object> redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public Health health() {
		try {
			String result = null;
			if (redisTemplate.getConnectionFactory() != null) {
				result = redisTemplate.getConnectionFactory()
						.getConnection()
						.ping();
			}

			if ("PONG".equals(result)) {
				return Health.up()
						.withDetail("status", "Redis connection is healthy")
						.withDetail("ping", result)
						.build();
			} else {
				return Health.down()
						.withDetail("status", "Redis ping returned unexpected result")
						.withDetail("ping", result)
						.build();
			}
		} catch (Exception e) {
			log.error("Redis health check failed", e);
			return Health.down()
					.withDetail("status", "Redis connection failed")
					.withDetail("error", e.getMessage())
					.build();
		}
	}
}