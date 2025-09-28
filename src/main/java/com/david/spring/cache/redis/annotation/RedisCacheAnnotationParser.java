package com.david.spring.cache.redis.annotation;

import com.david.spring.cache.redis.register.interceptor.RedisCacheableOperation;
import org.springframework.cache.annotation.CacheAnnotationParser;
import org.springframework.cache.interceptor.CacheOperation;

import java.lang.reflect.AnnotatedElement;
import java.util.Collection;
import java.util.Collections;

public class RedisCacheAnnotationParser implements CacheAnnotationParser {

	@Override
	public Collection<CacheOperation> parseCacheAnnotations(Class<?> type) {
		return parseCacheAnnotations((AnnotatedElement) type);
	}

	@Override
	public Collection<CacheOperation> parseCacheAnnotations(java.lang.reflect.Method method) {
		return parseCacheAnnotations((AnnotatedElement) method);
	}

	private Collection<CacheOperation> parseCacheAnnotations(AnnotatedElement ae) {
		// 核心逻辑：解析 RedisCacheable / RedisCacheEvict / RedisCaching
		// 并生成对应的 CacheOperation
		return Collections.emptyList();
	}

	private RedisCacheableOperation parseRedisCacheable(RedisCacheable ann, AnnotatedElement ae) {
		RedisCacheableOperation.Builder builder = new RedisCacheableOperation.Builder();

		return builder.build();
	}

}