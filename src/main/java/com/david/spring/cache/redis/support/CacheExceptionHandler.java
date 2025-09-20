package com.david.spring.cache.redis.support;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 缓存异常处理工具类
 * 提供统一的异常处理和日志记录
 */
@Slf4j
public final class CacheExceptionHandler {

	private CacheExceptionHandler() {
		// 工具类不允许实例化
	}

	/**
	 * 安全执行操作，发生异常时记录日志并返回默认值
	 *
	 * @param operation     要执行的操作
	 * @param defaultValue  默认值
	 * @param operationName 操作名称（用于日志）
	 * @param context       上下文信息（用于日志）
	 * @return 操作结果或默认值
	 */
	public static <T> T safeExecute(Supplier<T> operation, T defaultValue, String operationName, Object... context) {
		try {
			return operation.get();
		} catch (Exception e) {
			logException(operationName, e, context);
			return defaultValue;
		}
	}

	/**
	 * 安全执行操作，发生异常时记录日志（无返回值）
	 *
	 * @param operation     要执行的操作
	 * @param operationName 操作名称（用于日志）
	 * @param context       上下文信息（用于日志）
	 */
	public static void safeExecute(Runnable operation, String operationName, Object... context) {
		try {
			operation.run();
		} catch (Exception e) {
			logException(operationName, e, context);
		}
	}

	/**
	 * 记录异常日志
	 */
	private static void logException(String operationName, Exception e, Object... context) {
		if (context.length > 0) {
			log.error("Operation '{}' failed with context {}: {}", operationName, formatContext(context), e.getMessage(), e);
		} else {
			log.error("Operation '{}' failed: {}", operationName, e.getMessage(), e);
		}
	}

	/**
	 * 格式化上下文信息
	 */
	private static String formatContext(Object... context) {
		if (context.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < context.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(context[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * 判断是否为严重异常
	 */
	public static boolean isCriticalException(Exception e) {
		// 检查错误原因是否为严重的Error类型
		Throwable cause = e.getCause();
		if (cause instanceof OutOfMemoryError || cause instanceof StackOverflowError) {
			return true;
		}

		// 检查特定异常类型
		if (e instanceof java.util.concurrent.TimeoutException
				|| e instanceof java.io.IOException) {
			return true;
		}

		return isConnectionException(e);
	}

	/**
	 * 判断是否为连接异常
	 */
	private static boolean isConnectionException(Exception e) {
		String message = e.getMessage();
		if (message == null) {
			return false;
		}

		String lowerMessage = message.toLowerCase();
		return lowerMessage.contains("connection")
				|| lowerMessage.contains("timeout")
				|| lowerMessage.contains("pool exhausted")
				|| lowerMessage.contains("refused");
	}
}