package io.github.davidhlp.spring.cache.redis.core.writer.chain;

import lombok.Builder;
import lombok.Data;
import org.springframework.lang.Nullable;

/**
 * 缓存操作结果
 * 
 * 结果类型说明：
 * - success(): 操作成功，无返回值（用于 PUT/REMOVE/CLEAN）
 * - success(bytes): 操作成功，带返回值（用于 GET 命中）
 * - miss(): 缓存未命中
 * - rejectedByBloomFilter(): 被布隆过滤器拒绝
 * - failure(e): 操作失败
 * 
 * 状态判断：
 * - isSuccess(): 操作是否成功
 * - isHit(): 是否缓存命中（仅 GET 有意义）
 * - isRejectedByBloomFilter(): 是否被布隆过滤器拒绝
 */
@Data
@Builder
public class CacheResult {
    
    /** 是否成功 */
    private boolean success;

    /** 返回的字节数组（用于 GET/PUT_IF_ABSENT 操作） */
    @Nullable
    private byte[] resultBytes;

    /** 是否命中缓存 */
    private boolean hit;

    /** 是否被布隆过滤器拒绝 */
    private boolean rejectedByBloomFilter;

    /** 异常信息 */
    @Nullable
    private Exception exception;

    // ==================== 静态工厂方法 ====================

    /** 创建成功的结果（无返回值） */
    public static CacheResult success() {
        return CacheResult.builder()
                .success(true)
                .hit(false)
                .build();
    }

    /** 创建成功的结果（带返回值） */
    public static CacheResult success(byte[] resultBytes) {
        return CacheResult.builder()
                .success(true)
                .resultBytes(resultBytes)
                .hit(true)
                .build();
    }

    /** 创建缓存未命中的结果 */
    public static CacheResult miss() {
        return CacheResult.builder()
                .success(true)
                .hit(false)
                .build();
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
        return CacheResult.builder()
                .success(false)
                .exception(e)
                .build();
    }
    
    // ==================== 便捷方法 ====================
    
    /** 是否有返回数据 */
    public boolean hasResult() {
        return resultBytes != null;
    }
    
    /** 是否为失败结果 */
    public boolean isFailure() {
        return !success;
    }
}
