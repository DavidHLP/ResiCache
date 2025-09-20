package com.david.spring.cache.redis.aspect.constants;

/**
 * 切面相关常量定义
 *
 * @author David
 */
public final class AspectConstants {

	private AspectConstants() {
	}

	/**
	 * 切面优先级常量
	 */
	public static final class Order {
		/** 组合注解切面优先级 - 最高 */
		public static final int CACHING_ASPECT_ORDER = 5;
		/** 单个注解切面优先级 */
		public static final int SINGLE_ASPECT_ORDER = 10;

		private Order() {
		}
	}

	/**
	 * 日志消息模板
	 */
	public static final class LogMessages {
		public static final String PROCESSING_METHOD = "Processing {} annotation for method: {}.{}";
		public static final String REGISTRATION_SUCCESS = "Successfully registered {} for method: {}.{}";
		public static final String REGISTRATION_FAILED = "Failed to register {} for method: {}.{} - {}";
		public static final String ANNOTATION_NOT_FOUND = "{} annotation not found on method: {}.{}";
		public static final String RESOLVED_CACHE_KEY = "Resolved cache key for {}: {}";
		public static final String CACHE_OPERATION_REGISTERED = "Registered {} for cache={}, method={}, key={}";

		private LogMessages() {
		}
	}

	/**
	 * 异常消息模板
	 */
	public static final class ErrorMessages {
		public static final String UNSUPPORTED_OPERATION = "Please use the around method with annotation parameter";
		public static final String REGISTRATION_WARNING = "Failed to register cache invocation, cache operations may be affected: {}";

		private ErrorMessages() {
		}
	}
}
