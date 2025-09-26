package com.david.spring.cache.redis.core;

/**
 * 缓存操作相关常量
 */
public final class CacheConstants {

	// 缓存键分隔符
	public static final String CACHE_KEY_SEPARATOR = ":";
	// 缓存null值参数标识
	public static final String NULL_PARAM = "null";
	// 缓存键最大长度
	public static final int MAX_KEY_LENGTH = 250;
	// 哈希长度（MD5）
	public static final int HASH_LENGTH = 32;


	private CacheConstants() {}

	// 操作类型常量
	public static final class Operations {
		public static final String GET = "get";
		public static final String PUT = "put";
		public static final String EVICT = "evict";
		public static final String CLEAR = "clear";
		public static final String CACHE_CREATION = "cache_creation";

		private Operations() {}
	}

	// 缓存层标识
	public static final class CacheLayers {
		public static final String REDIS_CACHE = "RedisCache";
		public static final String CACHE_MANAGER = "RedisCacheManager";

		private CacheLayers() {}
	}
}