package com.david.spring.cache.redis.operations;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class RedisUtils {
	public final RedisStringOperations stringOperations;
	public final RedisHashOperations hashOperations;

	public RedisStringOperations string(){
		return stringOperations;
	}

	public RedisHashOperations hash(){
		return hashOperations;
	}
}
