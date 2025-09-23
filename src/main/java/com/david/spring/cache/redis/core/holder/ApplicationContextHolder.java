package com.david.spring.cache.redis.core.holder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Spring应用上下文持有者，提供静态方法访问Spring容器
 *
 * 主要功能：
 * - 持有Spring应用上下文引用
 * - 提供静态方法获取Bean实例
 * - 支持按名称和类型获取Bean
 * - 提供安全的Bean解析机制
 */
@Slf4j
@Component
public class ApplicationContextHolder implements ApplicationContextAware {

	private static volatile ApplicationContext context;

	/**
	 * 检查是否已设置应用上下文
	 *
	 * @return 如果应用上下文可用返回true
	 */
	public static boolean hasContext() {
		return context != null;
	}

	/**
	 * 获取Spring应用上下文
	 *
	 * @return Spring应用上下文，可能为null
	 */
	@Nullable
	public static ApplicationContext getContext() {
		return context;
	}

	/**
	 * 根据名称和类型获取Bean实例
	 *
	 * @param name Bean名称
	 * @param requiredType Bean类型
	 * @param <T> Bean类型泛型
	 * @return Bean实例，如果获取失败返回null
	 */
	@Nullable
	public static <T> T getBean(String name, Class<T> requiredType) {
		ApplicationContext ctx = context;
		if (ctx == null) {
			log.debug("Application context not available, cannot resolve bean: {}", name);
			return null;
		}

		if (name == null || name.isBlank()) {
			log.debug("Bean name is empty, cannot resolve bean");
			return null;
		}

		try {
			return ctx.getBean(name, requiredType);
		} catch (Exception e) {
			log.debug("Failed to resolve bean: {} of type: {} - {}", name, requiredType.getSimpleName(), e.getMessage());
			return null;
		}
	}

	/**
	 * 根据类型获取Bean实例
	 *
	 * @param requiredType Bean类型
	 * @param <T> Bean类型泛型
	 * @return Bean实例，如果获取失败返回null
	 */
	@Nullable
	public static <T> T getBean(Class<T> requiredType) {
		ApplicationContext ctx = context;
		if (ctx == null) {
			log.debug("Application context not available, cannot resolve bean of type: {}", requiredType.getSimpleName());
			return null;
		}

		try {
			return ctx.getBean(requiredType);
		} catch (Exception e) {
			log.debug("Failed to resolve bean of type: {} - {}", requiredType.getSimpleName(), e.getMessage());
			return null;
		}
	}

	/**
	 * 设置Spring应用上下文
	 *
	 * 该方法由Spring容器自动调用，用于注入应用上下文
	 *
	 * @param applicationContext Spring应用上下文
	 * @throws BeansException 如果设置过程中发生错误
	 */
	@Override
	public void setApplicationContext(@NonNull ApplicationContext applicationContext)
			throws BeansException {
		log.info("Setting Spring application context for CacheGuard components");
		ApplicationContextHolder.context = applicationContext;
		log.debug("Spring application context successfully set");
	}
}
