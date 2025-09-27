package com.david.spring.cache.redis.register.interceptor;

import lombok.Builder;
import org.springframework.lang.NonNull;

public @Builder
record Key(String name, String key, OperationType operationType) {

	@Override
	@NonNull
	public String toString() {
		return "Key{" +
				"name='" + name + '\'' +
				", key='" + key + '\'' +
				", operationType=" + operationType +
				'}';
	}

	public enum OperationType {
		EVICT,
		CACHE
	}
}
