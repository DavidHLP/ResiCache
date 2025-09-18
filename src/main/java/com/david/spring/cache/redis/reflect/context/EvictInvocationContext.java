package com.david.spring.cache.redis.reflect.context;

import lombok.Builder;

/** 缓存驱逐上下文信息记录类 */
@Builder
public record EvictInvocationContext(
		/* 缓存名称别名 */
		String[] value,
		/* 缓存名称 */
		String[] cacheNames,
		/* 缓存键表达式 */
		String key,
		/* KeyGenerator Bean 名称 */
		String keyGenerator,
		/* CacheManager Bean 名称 */
		String cacheManager,
		/* CacheResolver Bean 名称 */
		String cacheResolver,
		/* 条件表达式 */
		String condition,
		/* 是否清除所有条目 */
		boolean allEntries,
		/* 是否在方法调用前执行清除 */
		boolean beforeInvocation,
		/* 是否同步执行 */
		boolean sync) {}