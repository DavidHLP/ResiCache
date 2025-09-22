package com.david.spring.cache.redis.core.holder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ApplicationContextHolder implements ApplicationContextAware {

	private static volatile ApplicationContext context;

	public static boolean hasContext() {
		return context != null;
	}

	@Nullable
	public static ApplicationContext getContext() {
		return context;
	}

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

	@Override
	public void setApplicationContext(@NonNull ApplicationContext applicationContext)
			throws BeansException {
		log.info("Setting Spring application context for CacheGuard components");
		ApplicationContextHolder.context = applicationContext;
		log.debug("Spring application context successfully set");
	}
}
