package com.david.spring.cache.redis.chain.context;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import lombok.Data;
import org.aspectj.lang.ProceedingJoinPoint;

import java.lang.reflect.Method;

/**
 * 缓存处理上下文，用于在责任链中传递处理过程中的数据
 *
 * @author david
 */
@Data
public class CacheContext {

	/** AOP切点信息 */
	private ProceedingJoinPoint joinPoint;

	/** Redis缓存注解 */
	private RedisCacheable redisCacheable;

	/** 目标方法 */
	private Method method;

	/** 方法参数 */
	private Object[] args;

	/** 目标对象 */
	private Object target;

	/** 目标类 */
	private Class<?> targetClass;

	/** 生成的缓存键 */
	private String cacheKey;

	/** 缓存名称 */
	private String cacheName;

	/** 缓存值 */
	private Object cacheValue;

	/** 方法执行结果 */
	private Object result;

	/** 是否命中缓存 */
	private boolean cacheHit;

	/** 是否需要更新缓存 */
	private boolean needUpdateCache;

	/** 是否处理完成 */
	private boolean processed;

	/** 异常信息 */
	private Throwable exception;

	/** 是否可能存在穿透（布隆过滤器判定不存在时标记） */
	private boolean possiblePenetration;

	public CacheContext(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable) {
		this.joinPoint = joinPoint;
		this.redisCacheable = redisCacheable;
		this.method = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature()).getMethod();
		this.args = joinPoint.getArgs();
		this.target = joinPoint.getTarget();
		this.targetClass =redisCacheable.type();
		this.cacheHit = false;
		this.needUpdateCache = true;
		this.processed = false;
		this.possiblePenetration = false;
	}
}
