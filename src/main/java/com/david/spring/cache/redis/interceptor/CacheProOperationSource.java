package com.david.spring.cache.redis.interceptor;

import com.david.spring.cache.redis.annotation.SpringCacheProAnnotationParser;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.annotation.CacheAnnotationParser;

public class CacheProOperationSource extends AnnotationCacheOperationSource {

	public CacheProOperationSource() {
		super(new SpringCacheProAnnotationParser());
	}

	public CacheProOperationSource(CacheAnnotationParser annotationParser) {
		super(annotationParser);
	}
}