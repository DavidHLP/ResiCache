package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.aspect.support.CacheAspectSupport;
import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.reflect.context.EvictInvocationContext;
import com.david.spring.cache.redis.registry.RegistryFactory;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Redis缓存清除切面，处理@RedisCacheEvict注解。
 * <p>
 * 该切面负责处理缓存清除操作的拦截，包括：
 * <ul>
 *   <li>注册清除调用信息到注册表中，用于缓存策略处理</li>
 *   <li>支持单条目和全部条目清除</li>
 *   <li>支持方法执行前后的清除时机控制</li>
 *   <li>提供同步和异步清除模式</li>
 * </ul>
 * </p>
 *
 * @author CacheGuard
 * @since 1.0.0
 * @see RedisCacheEvict
 * @see EvictInvocation
 */
@Slf4j
@Aspect
@Component
public class RedisCacheEvictAspect extends AbstractCacheAspect {

	/** 注册表工厂，用于管理清除调用信息 */
	private final RegistryFactory registryFactory;

	/** 缓存切面支持类，提供通用缓存操作功能 */
	private final CacheAspectSupport cacheSupport;

	/**
	 * 构造函数。
	 *
	 * @param registryFactory 注册表工厂
	 * @param cacheSupport 缓存切面支持类
	 */
	public RedisCacheEvictAspect(RegistryFactory registryFactory, CacheAspectSupport cacheSupport) {
		this.registryFactory = registryFactory;
		this.cacheSupport = cacheSupport;
	}

	/**
	 * 环绕通知，拦截带有@RedisCacheEvict注解的方法。
	 * <p>
	 * 执行流程：
	 * <ol>
	 *   <li>提取目标方法和参数信息</li>
	 *   <li>注册清除调用信息到注册表</li>
	 *   <li>委托给缓存支持类执行实际的清除逻辑</li>
	 * </ol>
	 * </p>
	 *
	 * @param joinPoint 连接点，包含方法执行上下文
	 * @param redisCacheEvict 缓存清除注解
	 * @return 方法执行结果
	 * @throws Throwable 方法执行过程中的异常
	 */
	@Around("@annotation(redisCacheEvict)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCacheEvict redisCacheEvict) throws Throwable {
		// 提取方法执行上下文信息
		Method method = extractTargetMethod(joinPoint);
		Object targetBean = joinPoint.getTarget();
		Object[] arguments = joinPoint.getArgs();

		// 注册清除调用信息，用于后续的缓存策略处理
		registerInvocation(redisCacheEvict, method, targetBean, arguments);

		// 委托给缓存支持类执行实际的清除逻辑
		return cacheSupport.executeCacheEvict(joinPoint, redisCacheEvict, method, targetBean, arguments);
	}

	/**
	 * 注册清除调用信息到注册表。
	 * <p>
	 * 该方法负责：
	 * <ul>
	 *   <li>解析缓存名称和缓存键</li>
	 *   <li>构建清除调用对象</li>
	 *   <li>将调用信息注册到对应的注册表中</li>
	 * </ul>
	 * 注册的信息将被缓存处理器链使用，用于实现清除策略。
	 * </p>
	 *
	 * @param annotation 缓存清除注解
	 * @param method 目标方法
	 * @param targetBean 目标Bean实例
	 * @param arguments 方法参数
	 */
	private void registerInvocation(RedisCacheEvict annotation, Method method, Object targetBean, Object[] arguments) {
		try {
			// 解析缓存名称列表
			String[] cacheNames = cacheSupport.keyManager.getCacheNames(annotation.value(), annotation.cacheNames());

			// 根据是否清除全部条目来决定缓存键的处理
			final Object cacheKey;
			if (!annotation.allEntries()) {
				// 单条目清除：解析具体的缓存键
				cacheKey = cacheSupport.keyManager.resolveKey(targetBean, method, arguments, annotation.keyGenerator());
			} else {
				// 全部条目清除：缓存键为null
				cacheKey = null;
			}

			// 构建清除调用对象，包含所有必要的上下文信息
			EvictInvocation evictInvocation = buildEvictInvocation(method, targetBean, arguments, annotation);
			final boolean allEntries = annotation.allEntries();
			final boolean beforeInvocation = annotation.beforeInvocation();

			// 为每个有效的缓存名称注册清除调用信息
			cacheSupport.processValidCacheNames(cacheNames, method, cacheName -> {
				registryFactory.getEvictInvocationRegistry().register(cacheName, cacheKey, evictInvocation);
				log.debug("注册清除调用: cache={}, method={}, key={}, allEntries={}, beforeInvocation={}",
						cacheName, method.getName(), cacheKey, allEntries, beforeInvocation);
			});
		} catch (Exception e) {
			log.warn("注册清除调用失败: method={}, error={}", method.getName(), e.getMessage(), e);
		}
	}

	/**
	 * 构建清除调用对象。
	 * <p>
	 * 该方法将注解信息和方法执行上下文封装成EvictInvocation对象，
	 * 包含清除操作所需的所有信息。
	 * </p>
	 *
	 * @param method 目标方法
	 * @param targetBean 目标Bean实例
	 * @param arguments 方法参数
	 * @param annotation 缓存清除注解
	 * @return 清除调用对象
	 */
	private EvictInvocation buildEvictInvocation(Method method, Object targetBean, Object[] arguments, RedisCacheEvict annotation) {
		// 构建清除调用上下文，包含注解的所有配置信息
		EvictInvocationContext context = EvictInvocationContext.builder()
				.value(annotation.value())                          // 缓存名称
				.cacheNames(annotation.cacheNames())                // 额外的缓存名称
				.key(safeString(annotation.key()))                  // 缓存键表达式
				.keyGenerator(annotation.keyGenerator())            // 键生成器
				.cacheManager(annotation.cacheManager())            // 缓存管理器
				.cacheResolver(annotation.cacheResolver())          // 缓存解析器
				.condition(safeString(annotation.condition()))      // 条件表达式
				.allEntries(annotation.allEntries())                // 是否清除全部条目
				.beforeInvocation(annotation.beforeInvocation())    // 是否在方法执行前清除
				.sync(annotation.sync())                            // 是否同步执行
				.build();

		// 构建清除调用对象，包含方法执行上下文和配置信息
		return EvictInvocation.builder()
				.arguments(arguments)                               // 方法参数
				.targetBean(targetBean)                             // 目标Bean实例
				.targetMethod(method)                               // 目标方法
				.evictInvocationContext(context)                    // 清除调用上下文
				.build();
	}
}