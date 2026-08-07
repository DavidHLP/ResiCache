package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.handler.CacheErrorHandler;
import lombok.Builder;
import lombok.Data;
import org.springframework.lang.Nullable;

/**
 * 缓存操作结果 — 责任链出口的纯数据载体。控制流(链推进决策)由
 * {@link HandlerResult#decision()} 单一承载,本类只表达"操作结果数据",不混入
 * 控制流语义。
 *
 * <p>结果类型:
 * <ul>
 *   <li>{@link #success()} —— 操作成功,无返回值(PUT / REMOVE / CLEAN)</li>
 *   <li>{@link #success(byte[])} —— 操作成功,带返回值(GET 命中)</li>
 *   <li>{@link #miss()} —— 缓存未命中(success 的"未命中"语义别名,字节同构但保留调用方可读性)</li>
 *   <li>{@link #failure()} —— 操作失败,{@link #isSuccess()} 返回 false</li>
 * </ul>
 *
 * <p>状态判断:{@link #isSuccess()} —— bloom 后置处理
 * ({@code BloomFilterHandler.afterChainExecution})据此决定是否回填布隆;
 * {@link #getResultBytes()} —— {@code RedisProCacheWriter} GET / PUT_IF_ABSENT 出口消费。
 */
@Data
@Builder
public class CacheResult {

    /** 是否成功 */
    private boolean success;

    /** 返回的字节数组(用于 GET / PUT_IF_ABSENT 操作,由 RedisProCacheWriter 消费) */
    @Nullable
    private byte[] resultBytes;

    // ==================== 静态工厂方法 ====================

    /** 创建成功的结果(无返回值) */
    public static CacheResult success() {
        return CacheResult.builder()
                .success(true)
                .build();
    }

    /** 创建成功的结果(带返回值) */
    public static CacheResult success(byte[] resultBytes) {
        return CacheResult.builder()
                .success(true)
                .resultBytes(resultBytes)
                .build();
    }

    /** 创建缓存未命中的结果(success 的"未命中"语义别名,调用方表达 GET 未命中场景) */
    public static CacheResult miss() {
        return CacheResult.builder()
                .success(true)
                .build();
    }

    /** 创建失败的结果 */
    public static CacheResult failure() {
        return CacheResult.builder()
                .success(false)
                .build();
    }
}
