package com.david.spring.cache.redis.config;

import com.david.spring.cache.redis.event.CacheEventPublisher;
import com.david.spring.cache.redis.event.listener.CacheStatisticsListener;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 设计模式相关组件的配置类
 */
@Slf4j
@Configuration
@ComponentScan(basePackages = {
		"com.david.spring.cache.redis.core.strategy",
		"com.david.spring.cache.redis.factory",
		"com.david.spring.cache.redis.template"
})
@ConditionalOnProperty(prefix = "spring.redis.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheDesignPatternConfiguration {

	@PostConstruct
	public void init() {
		log.info("Initializing Cache Design Pattern Configuration");
	}

	/**
	 * 注册缓存统计监听器
	 */
	@Bean
	public CacheStatisticsListener cacheStatisticsListener(CacheEventPublisher eventPublisher) {
		CacheStatisticsListener listener = new CacheStatisticsListener();
		eventPublisher.registerListener(listener);
		log.info("Registered CacheStatisticsListener");
		return listener;
	}
}