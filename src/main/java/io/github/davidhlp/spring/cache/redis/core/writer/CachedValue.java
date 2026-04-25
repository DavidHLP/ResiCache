package io.github.davidhlp.spring.cache.redis.core.writer;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 缓存值包装类
 *
 * <p>注意：TTL 过期判断主要依赖 Redis 原生 TTL，此处的 isExpired() 仅作为本地 fallback。
 * 使用单调时钟 (nanoTime) 进行相对时间计算，避免系统时钟回拨导致的问题。
 *
 * <p>序列化兼容性说明：
 * <ul>
 *   <li>v2 版本新增了 startNanoTime 字段</li>
 *   <li>旧缓存数据反序列化时 startNanoTime=0，会自动降级使用 createdTime</li>
 * </ul>
 */
public final class CachedValue {

    private final Object value;
    private final Class<?> type;
    private final long ttl;
    private final long createdTime;
    private final long startNanoTime;
    private final long lastAccessTime;
    private final long visitTimes;
    private final boolean expired;
    private final long version;

    private CachedValue(Object value, Class<?> type, long ttl, long createdTime,
                        long startNanoTime, long lastAccessTime, long visitTimes,
                        boolean expired, long version) {
        this.value = value;
        this.type = type;
        this.ttl = ttl;
        this.createdTime = createdTime;
        this.startNanoTime = startNanoTime;
        this.lastAccessTime = lastAccessTime;
        this.visitTimes = visitTimes;
        this.expired = expired;
        this.version = version;
    }

    public static CachedValue of(Object value, long ttl) {
        long nowNano = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        return new CachedValue(
                value,
                value != null ? value.getClass() : Object.class,
                ttl,
                nowMillis,
                nowNano,
                nowMillis,
                0L,
                false,
                nowNano);
    }

    public static CachedValueBuilder builder() {
        return new CachedValueBuilder();
    }

    public static class CachedValueBuilder {
        private Object value;
        private Class<?> type;
        private long ttl = 60;
        private long createdTime = System.currentTimeMillis();
        private long startNanoTime = 0L;
        private long lastAccessTime = System.currentTimeMillis();
        private long visitTimes = 0L;
        private boolean expired = false;
        private long version = 1L;

        public CachedValueBuilder value(Object value) {
            this.value = value;
            return this;
        }

        public CachedValueBuilder type(Class<?> type) {
            this.type = type;
            return this;
        }

        public CachedValueBuilder ttl(long ttl) {
            this.ttl = ttl;
            return this;
        }

        public CachedValueBuilder createdTime(long createdTime) {
            this.createdTime = createdTime;
            return this;
        }

        public CachedValueBuilder startNanoTime(long startNanoTime) {
            this.startNanoTime = startNanoTime;
            return this;
        }

        public CachedValueBuilder version(long version) {
            this.version = version;
            return this;
        }

        public CachedValueBuilder expired(boolean expired) {
            this.expired = expired;
            return this;
        }

        public CachedValueBuilder lastAccessTime(long lastAccessTime) {
            this.lastAccessTime = lastAccessTime;
            return this;
        }

        public CachedValueBuilder visitTimes(long visitTimes) {
            this.visitTimes = visitTimes;
            return this;
        }

        public CachedValue build() {
            return new CachedValue(
                    value,
                    type != null ? type : (value != null ? value.getClass() : Object.class),
                    ttl,
                    createdTime,
                    startNanoTime,
                    lastAccessTime,
                    visitTimes,
                    expired,
                    version);
        }
    }

    @JsonIgnore
    public Object getValue() {
        return value;
    }

    @JsonIgnore
    public Class<?> getType() {
        return type;
    }

    @JsonIgnore
    public long getTtl() {
        return ttl;
    }

    @JsonIgnore
    public long getCreatedTime() {
        return createdTime;
    }

    @JsonIgnore
    public long getStartNanoTime() {
        return startNanoTime;
    }

    @JsonIgnore
    public long getLastAccessTime() {
        return lastAccessTime;
    }

    @JsonIgnore
    public long getVisitTimes() {
        return visitTimes;
    }

    @JsonIgnore
    public boolean isExpired() {
        return expired;
    }

    @JsonIgnore
    public long getVersion() {
        return version;
    }

    /**
     * 判断是否过期
     * 使用单调时钟计算相对时间，避免系统时钟回拨的影响
     *
     * 对于旧数据（startNanoTime=0），自动降级使用 createdTime
     *
     * @return 如果已过期返回 true
     */
    @JsonIgnore
    public boolean checkExpired() {
        if (expired) {
            return true;
        }
        if (ttl <= 0) {
            return false;
        }

        if (startNanoTime > 0) {
            long elapsedNanos = System.nanoTime() - startNanoTime;
            long elapsedMs = elapsedNanos / 1_000_000;
            return elapsedMs >= java.util.concurrent.TimeUnit.SECONDS.toMillis(ttl);
        } else {
            long elapsedMs = System.currentTimeMillis() - createdTime;
            return elapsedMs >= java.util.concurrent.TimeUnit.SECONDS.toMillis(ttl);
        }
    }

    @JsonIgnore
    public long getRemainingTtl() {
        if (ttl <= 0) {
            return -1;
        }

        long elapsedMs;
        if (startNanoTime > 0) {
            long elapsedNanos = System.nanoTime() - startNanoTime;
            elapsedMs = elapsedNanos / 1_000_000;
        } else {
            elapsedMs = System.currentTimeMillis() - createdTime;
        }

        long remainingMs = (java.util.concurrent.TimeUnit.SECONDS.toMillis(ttl)) - elapsedMs;
        return Math.max(0, remainingMs / 1000);
    }

    @JsonIgnore
    public long getAge() {
        return (System.currentTimeMillis() - createdTime) / 1000;
    }

    @JsonIgnore
    public boolean isUsingMonotonicClock() {
        return startNanoTime > 0;
    }

    /**
     * 创建新的 CachedValue，标记为已过期
     */
    public CachedValue withExpired() {
        return new CachedValue(value, type, ttl, createdTime, startNanoTime,
                               lastAccessTime, visitTimes, true, version);
    }

    /**
     * 标记此实例为已过期（用于测试）
     */
    public void markExpired() {
        throw new UnsupportedOperationException("CachedValue is immutable, use withExpired() instead");
    }

    /**
     * 创建新的 CachedValue，更新访问时间和访问次数
     */
    public CachedValue withAccessUpdate() {
        return new CachedValue(value, type, ttl, createdTime, startNanoTime,
                               System.currentTimeMillis(), visitTimes + 1, expired, version);
    }
}
