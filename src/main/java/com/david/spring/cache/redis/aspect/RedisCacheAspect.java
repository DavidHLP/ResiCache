package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.core.CacheExpressionEvaluator;
import com.david.spring.cache.redis.core.CacheOperationResolver;
import com.david.spring.cache.redis.core.RedisCache;
import com.david.spring.cache.redis.core.RedisCacheManager;
import com.david.spring.cache.redis.core.strategy.CacheStrategyContext;
import com.david.spring.cache.redis.event.publisher.CacheEventPublisher;
import com.david.spring.cache.redis.template.CacheOperationTemplate;
import com.david.spring.cache.redis.template.StandardCacheOperationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.support.NullValue;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Aspect
public class RedisCacheAspect implements Ordered {

	private static final Object CACHE_MISS = new Object();
	private final RedisCacheManager cacheManager;
	private final CacheOperationResolver operationResolver;
	private final CacheExpressionEvaluator expressionEvaluator;
	private final KeyGenerator keyGenerator;
	private final RedissonClient redissonClient;
	private final ReentrantLock internalLock = new ReentrantLock();
	private final ExecutorService preRefreshExecutor = Executors.newFixedThreadPool(2);
	// 设计模式组件
	private final CacheStrategyContext strategyContext;
	private final CacheEventPublisher eventPublisher;
	private final CacheOperationTemplate operationTemplate;

	public RedisCacheAspect(RedisCacheManager cacheManager,
	                        KeyGenerator keyGenerator,
	                        RedissonClient redissonClient,
	                        CacheStrategyContext strategyContext,
	                        CacheEventPublisher eventPublisher) {
		this.cacheManager = cacheManager;
		this.keyGenerator = keyGenerator;
		this.redissonClient = redissonClient;
		this.operationResolver = new CacheOperationResolver();
		this.expressionEvaluator = new CacheExpressionEvaluator();

		// 初始化设计模式组件
		this.strategyContext = strategyContext;
		this.eventPublisher = eventPublisher;
		this.operationTemplate = new StandardCacheOperationTemplate(eventPublisher, cacheManager, keyGenerator);
	}

	@Around("@annotation(com.david.spring.cache.redis.annotation.RedisCacheable) || " +
			"@annotation(com.david.spring.cache.redis.annotation.RedisCaching)")
	public Object handleCacheable(ProceedingJoinPoint joinPoint) throws Throwable {
		MethodSignature signature = (MethodSignature) joinPoint.getSignature();
		Method method = signature.getMethod();
		Class<?> targetClass = joinPoint.getTarget().getClass();
		Object[] args = joinPoint.getArgs();

		List<CacheOperationResolver.CacheableOperation> operations =
				operationResolver.resolveCacheableOperations(method, targetClass);

		if (operations.isEmpty()) {
			return joinPoint.proceed();
		}

		// 对于单个操作，使用模板方法模式处理
		if (operations.size() == 1) {
			CacheOperationResolver.CacheableOperation operation = operations.get(0);

			// 检查是否启用布隆过滤器的特殊处理
			if (operation.isUseBloomFilter()) {
				return handleBloomFilterOperation(joinPoint, operation, method, args, targetClass);
			}

			// 使用模板方法模式处理标准缓存操作
			return operationTemplate.execute(joinPoint, operation, method, args, targetClass);
		}

		// 对于多个操作，使用策略模式选择执行策略
		return strategyContext.execute(joinPoint, operations, method, args, targetClass);
	}

	/**
	 * 处理布隆过滤器特殊场景
	 */
	private Object handleBloomFilterOperation(ProceedingJoinPoint joinPoint,
	                                          CacheOperationResolver.CacheableOperation operation,
	                                          Method method, Object[] args, Class<?> targetClass) throws Throwable {
		Object key = generateCacheKey(operation, method, args, joinPoint.getTarget(), targetClass);
		String bloomFilterName = "bloom:" + operation.getCacheNames()[0];
		RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(bloomFilterName);

		// 确保布隆过滤器已初始化
		if (!bloomFilter.isExists()) {
			bloomFilter.tryInit(100000L, 0.03);
			log.debug("Initialized bloom filter: {}", bloomFilterName);
		}

		String keyStr = String.valueOf(key);
		if (!bloomFilter.contains(keyStr)) {
			log.debug("Bloom filter indicates key might not exist: {}", keyStr);

			// 执行方法
			Object result = joinPoint.proceed();

			// 如果有结果，添加到布隆过滤器并缓存
			if (result != null) {
				bloomFilter.add(keyStr);
				log.debug("Added key to bloom filter: {}", keyStr);

				// 缓存结果
				cacheResult(operation, method, args, joinPoint.getTarget(), targetClass, result);
			}

			return result;
		}

		// 布隆过滤器说可能存在，继续正常的缓存处理流程
		return operationTemplate.execute(joinPoint, operation, method, args, targetClass);
	}

	@Around("@annotation(com.david.spring.cache.redis.annotation.RedisCacheEvict) || " +
			"@annotation(com.david.spring.cache.redis.annotation.RedisCaching)")
	public Object handleCacheEvict(ProceedingJoinPoint joinPoint) throws Throwable {
		MethodSignature signature = (MethodSignature) joinPoint.getSignature();
		Method method = signature.getMethod();
		Class<?> targetClass = joinPoint.getTarget().getClass();
		Object[] args = joinPoint.getArgs();

		List<CacheOperationResolver.EvictOperation> operations =
				operationResolver.resolveEvictOperations(method, targetClass);

		if (operations.isEmpty()) {
			return joinPoint.proceed();
		}

		processEvictOperations(operations, joinPoint, method, args, targetClass, true);

		Object result = joinPoint.proceed();

		processEvictOperations(operations, joinPoint, method, args, targetClass, false);

		return result;
	}

	/**
	 * 使用锁执行缓存操作
	 */
	private Object executeWithLock(CacheOperationResolver.CacheableOperation operation,
	                               ProceedingJoinPoint joinPoint, Method method, Object[] args,
	                               Class<?> targetClass, List<CacheOperationResolver.CacheableOperation> operations) throws Throwable {

		Object key = generateCacheKey(operation, method, args, joinPoint.getTarget(), targetClass);
		String lockKey = "cache:lock:" + operation.getCacheNames()[0] + ":" + key;

		if (operation.isDistributedLock()) {
			return executeWithDistributedLock(lockKey, joinPoint, operations, method, args, targetClass);
		} else if (operation.isInternalLock() || operation.isSync()) {
			return executeWithInternalLock(lockKey, joinPoint, operations, method, args, targetClass);
		}

		// 不应该到达这里，但为了安全起见
		return executeWithoutLock(joinPoint, operations, method, args, targetClass);
	}

	/**
	 * 使用分布式锁执行
	 */
	private Object executeWithDistributedLock(String lockKey, ProceedingJoinPoint joinPoint,
	                                          List<CacheOperationResolver.CacheableOperation> operations,
	                                          Method method, Object[] args, Class<?> targetClass) throws Throwable {
		RLock lock = redissonClient.getLock(lockKey);
		boolean acquired = false;

		try {
			// 尝试获取分布式锁，最多等待3秒，锁自动过期时间30秒
			acquired = lock.tryLock(3, 30, TimeUnit.SECONDS);
			if (!acquired) {
				log.warn("Failed to acquire distributed lock for key: {}", lockKey);
				// 获取锁失败，直接执行方法（不缓存结果）
				return joinPoint.proceed();
			}

			log.debug("Acquired distributed lock for key: {}", lockKey);

			// 获取锁后再次检查缓存，可能已经被其他线程缓存了
			for (CacheOperationResolver.CacheableOperation operation : operations) {
				if (!shouldExecute(operation, method, args, joinPoint.getTarget(), targetClass, null)) {
					continue;
				}

				Object cachedResult = getCachedValue(operation, method, args, joinPoint.getTarget(), targetClass);
				if (cachedResult != CACHE_MISS) {
					if (cachedResult instanceof CachedResult) {
						return ((CachedResult) cachedResult).value();
					}
					return cachedResult;
				}
			}

			// 执行方法并缓存结果
			return executeWithoutLock(joinPoint, operations, method, args, targetClass);

		} finally {
			if (acquired && lock.isHeldByCurrentThread()) {
				lock.unlock();
				log.debug("Released distributed lock for key: {}", lockKey);
			}
		}
	}

	/**
	 * 使用内部锁执行
	 */
	private Object executeWithInternalLock(String lockKey, ProceedingJoinPoint joinPoint,
	                                       List<CacheOperationResolver.CacheableOperation> operations,
	                                       Method method, Object[] args, Class<?> targetClass) throws Throwable {
		synchronized (lockKey.intern()) {
			log.debug("Acquired internal lock for key: {}", lockKey);

			// 获取锁后再次检查缓存
			for (CacheOperationResolver.CacheableOperation operation : operations) {
				if (!shouldExecute(operation, method, args, joinPoint.getTarget(), targetClass, null)) {
					continue;
				}

				Object cachedResult = getCachedValue(operation, method, args, joinPoint.getTarget(), targetClass);
				if (cachedResult != CACHE_MISS) {
					if (cachedResult instanceof CachedResult) {
						return ((CachedResult) cachedResult).value();
					}
					return cachedResult;
				}
			}

			// 执行方法并缓存结果
			return executeWithoutLock(joinPoint, operations, method, args, targetClass);
		}
	}

	/**
	 * 不使用锁执行
	 */
	private Object executeWithoutLock(ProceedingJoinPoint joinPoint,
	                                  List<CacheOperationResolver.CacheableOperation> operations,
	                                  Method method, Object[] args, Class<?> targetClass) throws Throwable {
		// 执行方法
		Object result = joinPoint.proceed();

		// 将结果缓存到所有相关的缓存中
		for (CacheOperationResolver.CacheableOperation operation : operations) {
			if (shouldExecute(operation, method, args, joinPoint.getTarget(), targetClass, result)) {
				cacheResult(operation, method, args, joinPoint.getTarget(), targetClass, result);
			}
		}

		return result;
	}

	private Object getCachedValue(CacheOperationResolver.CacheableOperation operation,
	                              Method method, Object[] args, Object target, Class<?> targetClass) {

		for (String cacheName : operation.getCacheNames()) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache == null) {
				continue;
			}

			Object key = generateCacheKey(operation, method, args, target, targetClass);

			Cache.ValueWrapper cachedValue = cache.get(key);
			if (cachedValue != null) {
				Object value = cachedValue.get();
				if (operation.hasUnless() &&
						expressionEvaluator.evaluateUnless(operation.getUnless(), method, args, target, targetClass, value)) {
					continue;
				}

				log.debug("Cache hit for key: {}", key);

				// 检查是否需要预刷新
				if (operation.isEnablePreRefresh()) {
					checkAndPreRefresh(operation, cache, key, method, args, target, targetClass);
				}

				// 使用特殊包装器来区分缓存命中（包括null值）和缓存未命中
				return new CachedResult(value);
			}
		}

		return CACHE_MISS;
	}

	/**
	 * 检查是否需要预刷新缓存
	 */
	private void checkAndPreRefresh(CacheOperationResolver.CacheableOperation operation, Cache cache, Object key,
	                                Method method, Object[] args, Object target, Class<?> targetClass) {
		if (!(cache instanceof RedisCache redisCache)) {
			return;
		}

		try {
			RedisCache.CacheStats stats = redisCache.getCacheStats(key);
			if (stats == null) {
				return;
			}

			Duration ttl = operation.getTtl();
			if (ttl == null || ttl.isZero() || ttl.isNegative()) {
				return; // 永不过期的缓存不需要预刷新
			}

			double preRefreshThreshold = operation.getPreRefreshThreshold();
			long totalTtlSeconds = ttl.getSeconds();
			long thresholdTime = (long) (totalTtlSeconds * preRefreshThreshold);

			// 如果剩余TTL小于阈值时间，触发异步预刷新
			if (stats.remainingTtl() <= thresholdTime) {
				log.debug("Triggering pre-refresh for key: {}, remaining TTL: {}s, threshold: {}s",
						key, stats.remainingTtl(), thresholdTime);

				// 异步执行预刷新
				preRefreshExecutor.submit(() -> {
					try {
						log.debug("Pre-refreshing cache for key: {}", key);
						// 通过反射调用原始方法获取新值
						Object newResult = method.invoke(target, args);
						// 缓存新结果
						cacheResult(operation, method, args, target, targetClass, newResult);
						log.debug("Pre-refresh completed for key: {}", key);
					} catch (Exception e) {
						log.warn("Pre-refresh failed for key: {}: {}", key, e.getMessage());
					}
				});
			}
		} catch (Exception e) {
			log.warn("Failed to check pre-refresh condition for key: {}: {}", key, e.getMessage());
		}
	}

	private void cacheResult(CacheOperationResolver.CacheableOperation operation,
	                         Method method, Object[] args, Object target, Class<?> targetClass, Object result) {

		if (result == null && !operation.isCacheNullValues()) {
			log.debug("Not caching null result");
			return;
		}

		if (operation.hasUnless() &&
				expressionEvaluator.evaluateUnless(operation.getUnless(), method, args, target, targetClass, result)) {
			log.debug("Unless condition matched, not caching result");
			return;
		}

		for (String cacheName : operation.getCacheNames()) {
			Cache cache = cacheManager.getCache(cacheName);
			if (cache == null) {
				continue;
			}

			Object key = generateCacheKey(operation, method, args, target, targetClass);

			Duration ttl = calculateTtl(operation);
			if (cache instanceof RedisCache) {
				((RedisCache) cache).putWithTtl(key, result, ttl);
			} else {
				cache.put(key, result);
			}

			log.debug("Cached result for key: {} with TTL: {}", key, ttl);

			// 如果启用了布隆过滤器，将key添加到布隆过滤器中
			if (operation.isUseBloomFilter()) {
				String bloomFilterName = "bloom:" + cacheName;
				RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(bloomFilterName);

				// 确保布隆过滤器已初始化
				if (!bloomFilter.isExists()) {
					bloomFilter.tryInit(100000L, 0.03);
					log.debug("Initialized bloom filter: {}", bloomFilterName);
				}

				String keyStr = String.valueOf(key);
				bloomFilter.add(keyStr);
				log.debug("Added key to bloom filter: {}", keyStr);
			}
		}
	}

	private void processEvictOperations(List<CacheOperationResolver.EvictOperation> operations,
	                                    ProceedingJoinPoint joinPoint, Method method, Object[] args,
	                                    Class<?> targetClass, boolean beforeInvocation) {

		for (CacheOperationResolver.EvictOperation operation : operations) {
			if (operation.isBeforeInvocation() == beforeInvocation &&
					shouldExecuteEvict(operation, method, args, joinPoint.getTarget(), targetClass)) {

				for (String cacheName : operation.getCacheNames()) {
					Cache cache = cacheManager.getCache(cacheName);
					if (cache != null) {
						if (operation.isAllEntries()) {
							cache.clear();
							log.debug("Cleared all entries from cache: {}", cacheName);
						} else {
							Object key = generateEvictKey(operation, method, args, joinPoint.getTarget(), targetClass);
							cache.evict(key);
							log.debug("Evicted key: {} from cache: {}", key, cacheName);
						}
					}
				}
			}
		}
	}

	private Object generateCacheKey(CacheOperationResolver.CacheableOperation operation,
	                                Method method, Object[] args, Object target, Class<?> targetClass) {
		if (operation.hasKey()) {
			Object key = expressionEvaluator.generateKey(operation.getKey(), method, args, target, targetClass, null);
			return key != null ? key : keyGenerator.generate(target, method, args);
		}

		operation.hasKeyGenerator();

		return keyGenerator.generate(target, method, args);
	}

	private Object generateEvictKey(CacheOperationResolver.EvictOperation operation,
	                                Method method, Object[] args, Object target, Class<?> targetClass) {
		if (operation.hasKey()) {
			Object key = expressionEvaluator.generateKey(operation.getKey(), method, args, target, targetClass, null);
			return key != null ? key : keyGenerator.generate(target, method, args);
		}

		operation.hasKeyGenerator();

		return keyGenerator.generate(target, method, args);
	}

	private boolean shouldExecute(CacheOperationResolver.CacheableOperation operation,
	                              Method method, Object[] args, Object target, Class<?> targetClass, Object result) {
		return operation.hasCondition() ||
				expressionEvaluator.evaluateCondition(operation.getCondition(), method, args, target, targetClass, result);
	}

	private boolean shouldExecuteEvict(CacheOperationResolver.EvictOperation operation,
	                                   Method method, Object[] args, Object target, Class<?> targetClass) {
		return !operation.hasCondition() ||
				expressionEvaluator.evaluateCondition(operation.getCondition(), method, args, target, targetClass, null);
	}

	private Duration calculateTtl(CacheOperationResolver.CacheableOperation operation) {
		Duration baseTtl = operation.getTtl();

		if (operation.isRandomTtl() && baseTtl != null) {
			long baseSeconds = baseTtl.getSeconds();
			float variance = operation.getVariance();
			long randomOffset = (long) (baseSeconds * variance * (ThreadLocalRandom.current().nextFloat() - 0.5f) * 2);
			return Duration.ofSeconds(Math.max(1, baseSeconds + randomOffset));
		}

		return baseTtl;
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 1;
	}

	// 内部类用于包装缓存结果
	private record CachedResult(Object value) {

		@Override
		public Object value() {
			// 将 Spring 的 NullValue 转换为真正的 null
			return (value instanceof NullValue) ? null : value;
		}
	}
}