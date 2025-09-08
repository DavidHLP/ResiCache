package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.chain.CacheHandlerChain;
import com.david.spring.cache.redis.chain.context.CacheContext;
import com.david.spring.cache.redis.config.CustomRedisCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 自定义缓存逻辑的AOP切面，使用责任链模式处理缓存操作
 * 基于Spring CacheAspectSupport重构，支持缓存穿透、击穿、雪崩防护
 *
 * @author david
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RedisCacheAspect {

	private final CacheHandlerChain cacheHandlerChain;

	/**
	 * 环绕通知处理缓存逻辑
	 * 使用责任链模式依次处理：键生成 -> 缓存读取 -> 击穿保护 -> 方法执行 -> 穿透保护 -> 缓存写入 -> 雪崩保护
	 *
	 * @param joinPoint      切点信息
	 * @param redisCacheable 缓存注解
	 * @return 方法执行结果或缓存值
	 * @throws Throwable 执行过程中的异常
	 */
	@Around("@annotation(redisCacheable)")
	public Object customCacheAround(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable)
			throws Throwable {

		// 创建缓存上下文
		CacheContext context = new CacheContext(joinPoint, redisCacheable);

		try {
			log.debug("开始处理缓存操作，方法: {}.{}",
					context.getTargetClass().getSimpleName(),
					context.getMethod().getName());

			// 执行责任链处理
			cacheHandlerChain.execute(context);

			// 从上下文获取最终结果
			if (context.isCacheHit()) {
				log.debug("缓存命中，返回缓存值");
				return context.getCacheValue();
			} else {
				log.debug("缓存未命中，返回方法执行结果");
				return context.getResult();
			}

		} catch (Throwable e) {
			log.error("缓存处理过程中发生异常，方法: {}.{}",
					context.getTargetClass().getSimpleName(),
					context.getMethod().getName(), e);

			// 如果缓存处理失败，尝试直接执行原方法
			if (context.getResult() == null && !context.isCacheHit()) {
				log.warn("缓存处理失败，直接执行原方法");
				return joinPoint.proceed();
			}

			throw e;
		}
	}
}
