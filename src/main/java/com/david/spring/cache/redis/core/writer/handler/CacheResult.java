package com.david.spring.cache.redis.core.writer.handler;

import lombok.Builder;
import lombok.Data;
import org.springframework.lang.Nullable;

/** 缓存操作结果 */
@Data
@Builder
public class CacheResult {
    /** 是否成功 */
    private boolean success;

    /** 返回的字节数组（用于 GET/PUT_IF_ABSENT 操作） */
    @Nullable private byte[] resultBytes;

    /** 是否命中缓存 */
    private boolean hit;

    /** 是否被布隆过滤器拒绝 */
    private boolean rejectedByBloomFilter;

    /** 异常信息 */
    @Nullable private Exception exception;

    /** 创建成功的结果 */
    public static CacheResult success() {
        return CacheResult.builder().success(true).hit(false).build();
    }

    /** 创建成功的结果（带返回值） */
    public static CacheResult success(byte[] resultBytes) {
        return CacheResult.builder().success(true).resultBytes(resultBytes).hit(true).build();
    }

    /** 创建缓存未命中的结果 */
    public static CacheResult miss() {
        return CacheResult.builder().success(true).hit(false).build();
    }

    /** 创建被布隆过滤器拒绝的结果 */
    public static CacheResult rejectedByBloomFilter() {
        return CacheResult.builder()
                .success(true)
                .hit(false)
                .rejectedByBloomFilter(true)
                .build();
    }

    /** 创建失败的结果 */
    public static CacheResult failure(Exception e) {
        return CacheResult.builder().success(false).exception(e).build();
    }
}
