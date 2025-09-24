package com.david.spring.cache.redis.annotation;

import org.springframework.cache.annotation.CacheAnnotationParser;
import org.springframework.cache.interceptor.CacheOperation;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

public class SpringCacheProAnnotationParser implements CacheAnnotationParser, Serializable {
	@Override
	public boolean isCandidateClass(Class<?> targetClass) {
		return CacheAnnotationParser.super.isCandidateClass(targetClass);
	}

	@Override
	public Collection<CacheOperation> parseCacheAnnotations(Class<?> type) {
		return List.of();
	}

	@Override
	public Collection<CacheOperation> parseCacheAnnotations(Method method) {
		return List.of();
	}
}
