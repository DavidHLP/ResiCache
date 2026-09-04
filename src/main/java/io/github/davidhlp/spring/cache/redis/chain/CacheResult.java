package io.github.davidhlp.spring.cache.redis.chain;

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
 *   <li>{@link #success()} —— 普通成功写入或删除</li>
 *   <li>{@link #success(byte[])} —— GET 命中</li>
 *   <li>{@link #miss()} —— 缓存未命中</li>
 *   <li>{@link #inserted()} / {@link #existing(byte[])} —— PIFA 两种成功状态</li>
 *   <li>{@link #failure(String, String, Throwable)} —— 带 operation/kind/cause 的失败</li>
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

    /** 结果细分状态: SUCCESS / MISS / INSERTED / EXISTING / FAILURE. */
    @Nullable
    private String outcome;

    /** 失败时的缓存操作名称. */
    @Nullable
    private String operation;

    /** 失败分类,例如 REDIS / SERIALIZATION / TIMEOUT / CANCELLATION. */
    @Nullable
    private String failureKind;

    /** 失败的原始原因. */
    @Nullable
    private Throwable cause;

    // ==================== 静态工厂方法 ====================

    /** 创建成功的结果(无返回值) */
    public static CacheResult success() {
        return CacheResult.builder()
                .success(true)
                .outcome("SUCCESS")
                .build();
    }

    /** 创建成功的结果(带返回值) */
    public static CacheResult success(byte[] resultBytes) {
        return CacheResult.builder()
                .success(true)
                .resultBytes(resultBytes)
                .outcome("HIT")
                .build();
    }

    /** 创建缓存未命中的结果 */
    public static CacheResult miss() {
        return CacheResult.builder()
                .success(true)
                .outcome("MISS")
                .build();
    }

    /** 创建 PUT_IF_ABSENT 插入成功的结果. */
    public static CacheResult inserted() {
        return CacheResult.builder()
                .success(true)
                .outcome("INSERTED")
                .build();
    }

    /** 创建 PUT_IF_ABSENT 发现已有 key 的结果. */
    public static CacheResult existing(@Nullable byte[] resultBytes) {
        return CacheResult.builder()
                .success(true)
                .resultBytes(resultBytes)
                .outcome("EXISTING")
                .build();
    }

    /** 创建无附加诊断信息的失败结果. */
    public static CacheResult failure() {
        return failure(null, null, null);
    }

    /** 创建保留操作、分类和原始 cause 的失败结果. */
    public static CacheResult failure(
            @Nullable String operation,
            @Nullable String failureKind,
            @Nullable Throwable cause) {
        return CacheResult.builder()
                .success(false)
                .outcome("FAILURE")
                .operation(operation)
                .failureKind(failureKind)
                .cause(cause)
                .build();
    }
}
