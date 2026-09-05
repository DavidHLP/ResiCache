package io.github.davidhlp.spring.cache.redis.cache;





import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.lang.Nullable;

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
 *   <li>startNanoTime 仅保存当前 JVM 的单调时钟起点，不写入 v2 wire format</li>
 *   <li>旧缓存数据或跨 JVM 反序列化时 startNanoTime=0，会自动降级使用 createdTime</li>
 *   <li>value 字段使用 @JsonTypeInfo(Id.CLASS) 保留类型信息，安全性由 serializer 的流式预检校验</li>
 *   <li>刷新元数据（ttl/createdTime/lastAccessTime/visitTimes/expired/version）按字段持久化；
 *       startNanoTime 仅是进程内单调时钟，不写入 wire format</li>
 * </ul>
 */
final class CachedValue {

    @JsonProperty("value")
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    private Object value;
    private Class<?> type;
    @JsonProperty("ttl")
    private long ttl;
    @JsonProperty("createdTime")
    private long createdTime;
    private long startNanoTime;
    @JsonProperty("lastAccessTime")
    private long lastAccessTime;
    @JsonProperty("visitTimes")
    private long visitTimes;
    @JsonProperty("expired")
    private boolean expired;
    @JsonProperty("version")
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

    /**
     * 仅供测试使用：用指定 {@code createdTime} / {@code version} / {@code expired}
     * 三维覆盖构造 {@link CachedValue}（{@code type} / {@code startNanoTime} 仍按
     * {@link #of(Object, long)} 默认自动派生，避免与生产 seam 行为漂移）。
     *
     * <p>替换被删除的 {@code CachedValueBuilder}（75 行死代码路径：唯一生产 seam
     * 是 {@link #of(Object, long)}，builder 仅剩 3 处测试 helper 在用）。
     *
     * <p><b>Visible for testing</b>：因测试类分布在不同包（{@code chain}、
     * {@code protection.refresh}、{@code serialization}），使用 {@code public}
     * 以便跨包访问；调用契约由 Javadoc 与单元测试约束，<b>生产代码严禁引用</b>，
     * 唯一生产 seam 仍是 {@link #of(Object, long)}。
     */
    public static CachedValue forTest(@Nullable Object value, long ttl,
                                      long createdTime, long version, boolean expired) {
        long nowNano = System.nanoTime();
        // lastAccessTime 维持与 of() 一致：createdTime 时刻即最后访问。
        return new CachedValue(
                value,
                value != null ? value.getClass() : Object.class,
                ttl,
                createdTime,
                nowNano,
                createdTime,
                0L,
                expired,
                version);
    }

    public Object getValue() {
        return value;
    }

    @JsonIgnore
    public Class<?> getType() {
        return type;
    }

    public long getTtl() {
        return ttl;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    @JsonIgnore
    public long getStartNanoTime() {
        return startNanoTime;
    }

    public long getLastAccessTime() {
        return lastAccessTime;
    }

    public long getVisitTimes() {
        return visitTimes;
    }

    public boolean isExpired() {
        return expired;
    }

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
