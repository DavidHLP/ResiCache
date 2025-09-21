package com.david.spring.cache.redis.reflect.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.MethodInvoker;

import java.lang.reflect.Method;

/**
 * 调用工具类
 * 提供统一的方法调用逻辑
 */
@Slf4j
public final class InvocationUtils {

	private InvocationUtils() {
		// 工具类禁止实例化
	}

	/**
	 * 执行方法调用
	 *
	 * @param targetBean 目标Bean实例
	 * @param targetMethod 目标方法
	 * @param arguments 方法参数
	 * @param operationType 操作类型（用于日志）
	 * @return 方法执行结果
	 * @throws Exception 执行异常
	 */
	public static Object invokeMethod(Object targetBean, Method targetMethod, Object[] arguments, String operationType) throws Exception {
		long startTime = System.currentTimeMillis();

		try {
			MethodInvoker invoker = new MethodInvoker();
			invoker.setTargetObject(targetBean);
			invoker.setArguments(arguments);
			invoker.setTargetMethod(targetMethod.getName());
			invoker.prepare();

			Object result = invoker.invoke();

			long duration = System.currentTimeMillis() - startTime;
			log.trace("{} method execution completed: {}#{} in {}ms",
				operationType, targetBean.getClass().getSimpleName(), targetMethod.getName(), duration);

			return result;
		} catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			log.debug("{} method execution failed: {}#{} after {}ms, error: {}",
				operationType, targetBean.getClass().getSimpleName(), targetMethod.getName(), duration, e.getMessage());
			throw e;
		}
	}

	/**
	 * 验证调用信息的有效性
	 *
	 * @param targetBean 目标Bean实例
	 * @param targetMethod 目标方法
	 * @param context 上下文对象
	 * @return 如果调用信息有效则返回true
	 */
	public static boolean isValidInvocation(Object targetBean, Method targetMethod, Object context) {
		return targetBean != null && targetMethod != null && context != null;
	}

	/**
	 * 获取方法签名的字符串表示
	 *
	 * @param targetBean 目标Bean实例
	 * @param targetMethod 目标方法
	 * @return 方法签名字符串
	 */
	public static String getMethodSignature(Object targetBean, Method targetMethod) {
		if (targetMethod == null) {
			return "unknown";
		}

		return String.format("%s#%s",
			targetBean != null ? targetBean.getClass().getSimpleName() : "unknown",
			targetMethod.getName());
	}
}