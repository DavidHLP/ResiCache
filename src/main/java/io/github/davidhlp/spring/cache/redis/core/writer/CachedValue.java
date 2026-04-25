package io.github.davidhlp.spring.cache.redis.core.writer;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.*;

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
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CachedValue {

    private Object value;

    private Class<?> type;
    
    @Builder.Default 
    private long ttl = 60;

    /** 创建时间（毫秒），用于统计和日志，以及旧数据兼容 */
    @Builder.Default 
    private long createdTime = System.currentTimeMillis();

    /** 单调时钟起始点（纳秒），用于可靠的相对时间计算。旧数据此值为 0 */
    @Builder.Default 
    private long startNanoTime = 0L;

    @Builder.Default 
    private long lastAccessTime = System.currentTimeMillis();

    @Builder.Default 
    private long visitTimes = 0L;

    @Builder.Default 
    private boolean expired = false;

    @Builder.Default 
    private long version = 1L;

    public static CachedValue of(Object value, long ttl) {
        long nowNano = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        return CachedValue.builder()
                .value(value)
                .type(value != null ? value.getClass() : Object.class)
                .ttl(ttl)
                .createdTime(nowMillis)
                .version(nowNano)  // 使用 nanoTime 作为版本号，保证唯一性和递增
                .startNanoTime(nowNano)
                .build();
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
    public boolean isExpired() {
        if (expired) {
            return true;
        }
        if (ttl <= 0) {
            return false;
        }

        if (startNanoTime > 0) {
            // 新版本：使用单调时钟计算经过的纳秒数
            long elapsedNanos = System.nanoTime() - startNanoTime;
            long elapsedMs = elapsedNanos / 1_000_000;
            return elapsedMs >= java.util.concurrent.TimeUnit.SECONDS.toMillis(ttl);
        } else {
            // 旧数据兼容：使用 createdTime（可能受时钟回拨影响）
            long elapsedMs = System.currentTimeMillis() - createdTime;
            return elapsedMs >= java.util.concurrent.TimeUnit.SECONDS.toMillis(ttl);
        }
    }

    @JsonIgnore
    public void updateAccess() {
        this.lastAccessTime = System.currentTimeMillis();
        this.visitTimes++;
    }

    /**
     * 获取剩余 TTL（秒）
     * 使用单调时钟计算，避免时钟回拨影响
     * 
     * 对于旧数据（startNanoTime=0），自动降级使用 createdTime
     */
    @JsonIgnore
    public long getRemainingTtl() {
        if (ttl <= 0) {
            return -1;
        }
        
        long elapsedMs;
        if (startNanoTime > 0) {
            // 新版本：使用单调时钟
            long elapsedNanos = System.nanoTime() - startNanoTime;
            elapsedMs = elapsedNanos / 1_000_000;
        } else {
            // 旧数据兼容：使用 createdTime
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
    public void markExpired() {
        this.expired = true;
    }
    
    /**
     * 检查是否为新版本数据（使用单调时钟）
     * @return 如果使用单调时钟返回 true
     */
    @JsonIgnore
    public boolean isUsingMonotonicClock() {
        return startNanoTime > 0;
    }
}
