package com.david.spring.cache.redis.core;

/**
 * 缓存操作相关常量
 */
public final class CacheConstants {

    private CacheConstants() {}

    // 缓存键分隔符
    public static final String CACHE_KEY_SEPARATOR = ":";

    // 缓存null值参数标识
    public static final String NULL_PARAM = "null";

    // 缓存键最大长度
    public static final int MAX_KEY_LENGTH = 250;

    // 默认TTL（秒）
    public static final long DEFAULT_TTL_SECONDS = 3600;

    // 健康检查键前缀
    public static final String HEALTH_CHECK_KEY_PREFIX = "health-check-";

    // 同步阈值默认值（毫秒）
    public static final long DEFAULT_SYNC_THRESHOLD_MS = 100;

    // 连续失败次数阈值
    public static final int CONSECUTIVE_FAILURES_THRESHOLD = 3;

    // 哈希长度（MD5）
    public static final int HASH_LENGTH = 32;

    // 操作类型常量
    public static final class Operations {
        public static final String GET = "get";
        public static final String PUT = "put";
        public static final String EVICT = "evict";
        public static final String CLEAR = "clear";
        public static final String HEALTH_CHECK = "health_check";
        public static final String CACHE_CREATION = "cache_creation";

        private Operations() {}
    }

    // 缓存未命中原因
    public static final class MissReasons {
        public static final String NOT_FOUND = "not_found";
        public static final String EXPIRED = "expired";
        public static final String NOT_FOUND_IN_BOTH_LAYERS = "not_found_in_both_layers";

        private MissReasons() {}
    }

    // 缓存层标识
    public static final class CacheLayers {
        public static final String LOCAL = "local";
        public static final String REMOTE = "remote";
        public static final String REDIS_CACHE = "RedisCache";
        public static final String LAYERED_CACHE = "LayeredCache";
        public static final String CACHE_MANAGER = "RedisCacheManager";

        private CacheLayers() {}
    }
}