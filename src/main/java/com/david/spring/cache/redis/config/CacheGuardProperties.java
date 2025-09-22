package com.david.spring.cache.redis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CacheGuard配置属性
 * 用于外化配置项，提供运维友好的配置管理
 */
@Data
@Component
@ConfigurationProperties(prefix = "cache.guard")
public class CacheGuardProperties {

	/**
	 * 双删延迟时间（毫秒）
	 */
	private long doubleDeleteDelayMs = 300L;
}