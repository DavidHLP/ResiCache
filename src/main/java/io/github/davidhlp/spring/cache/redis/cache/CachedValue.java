package io.github.davidhlp.spring.cache.redis.cache;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 缓存值包装类
 *
 * <p>注意：TTL 过期判断主要依赖 Redis 原生 TTL，此处的 isExpired() 仅作为本地 fallback。
 * 使用单调时钟 (nanoTime) 进行相对时间计算，避免系统时钟回拨导致的问题。
 *
 * <p>双时钟过期计算（单调时钟优先、旧数据降级 wall-clock）集中收敛于内嵌的
 * {@link Expiry}，避免此前 checkExpired/getRemainingTtl 各自重复一份 startNanoTime>0 分支判断。
 *
 * <p>序列化兼容性说明：
 * <ul>
 *   <li>v2 版本新增了 startNanoTime 字段</li>
 *   <li>旧缓存数据反序列化时 startNanoTime=0，会自动降级使用 createdTime</li>
 *   <li>value 字段使用 @JsonTypeInfo(Id.CLASS) 保留类型信息，安全性由 validateTypeIds() 校验</li>
 *   <li>字段布局保持稳定：序列化走字段（getter 全 @JsonIgnore），不得随意重组字段结构</li>
 * </ul>
 */
public final class CachedValue {

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    private Object value;
    private Class<?> type;
    private long ttl;
    private long createdTime;
    private long startNanoTime;
    private long lastAccessTime;
    private long visitTimes;
    private boolean expired;
    private long version;

    /** 仅供 Jackson 反序列化使用 */
    private CachedValue() {
    }

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
     * <p>对于旧数据（startNanoTime=0），自动降级使用 createdTime。
     * 双时钟策略实现收敛于 {@link Expiry}。
     *
     * @return 如果已过期返回 true
     */
    @JsonIgnore
    public boolean checkExpired() {
        return Expiry.isExpired(expired, ttl, startNanoTime, createdTime);
    }

    @JsonIgnore
    public long getRemainingTtl() {
        return Expiry.remainingSeconds(ttl, startNanoTime, createdTime);
    }

    @JsonIgnore
    public long getAge() {
        return (System.currentTimeMillis() - createdTime) / 1000;
    }

    @JsonIgnore
    public boolean isUsingMonotonicClock() {
        return Expiry.isMonotonic(startNanoTime);
    }

    /**
     * 创建新的 CachedValue，标记为已过期
     */
    public CachedValue withExpired() {
        return new CachedValue(value, type, ttl, createdTime, startNanoTime,
                               lastAccessTime, visitTimes, true, version);
    }

    /**
     * 创建新的 CachedValue，更新访问时间和访问次数
     */
    public CachedValue withAccessUpdate() {
        return new CachedValue(value, type, ttl, createdTime, startNanoTime,
                               System.currentTimeMillis(), visitTimes + 1, expired, version);
    }

    /**
     * 过期时间计算的内聚单元：双时钟策略（单调时钟优先、旧数据降级 wall-clock）集中于此，
     * 消除原 checkExpired/getRemainingTtl 各自重复的 startNanoTime>0 分支判断。
     *
     * <p>单调时钟（startNanoTime>0，新数据）用 {@link System#nanoTime()} 避免 wall-clock 回拨；
     * 旧数据（startNanoTime=0）降级用 createdTime + {@link System#currentTimeMillis()}。
     * 两处调用方（checkExpired/getRemainingTtl）共用 {@link #elapsedMillis} 单一实现，
     * 确保双时钟判定始终一致。
     */
    private static final class Expiry {

        /** 是否已过期：显式标记优先，其次按双时钟计算已过时间是否达到 ttl */
        static boolean isExpired(boolean expired, long ttlSeconds,
                                 long startNanoTime, long createdTimeMillis) {
            if (expired) {
                return true;
            }
            if (ttlSeconds <= 0) {
                return false;
            }
            return elapsedMillis(startNanoTime, createdTimeMillis)
                    >= java.util.concurrent.TimeUnit.SECONDS.toMillis(ttlSeconds);
        }

        /** 剩余 TTL（秒）；ttl<=0 返回 -1 表示永不过期 */
        static long remainingSeconds(long ttlSeconds, long startNanoTime, long createdTimeMillis) {
            if (ttlSeconds <= 0) {
                return -1;
            }
            long remainingMs = java.util.concurrent.TimeUnit.SECONDS.toMillis(ttlSeconds)
                    - elapsedMillis(startNanoTime, createdTimeMillis);
            return Math.max(0, remainingMs / 1000);
        }

        /** 是否使用单调时钟（startNanoTime>0） */
        static boolean isMonotonic(long startNanoTime) {
            return startNanoTime > 0;
        }

        /** 双时钟统一：单调时钟优先，否则降级 wall-clock */
        private static long elapsedMillis(long startNanoTime, long createdTimeMillis) {
            if (startNanoTime > 0) {
                return (System.nanoTime() - startNanoTime) / 1_000_000;
            }
            return System.currentTimeMillis() - createdTimeMillis;
        }
    }
}
