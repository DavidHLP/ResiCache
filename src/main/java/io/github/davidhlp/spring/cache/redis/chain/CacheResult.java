package io.github.davidhlp.spring.cache.redis.chain;




import java.util.Arrays;
import java.util.Objects;
import org.springframework.lang.Nullable;

/**
 * 缓存操作结果 — 责任链出口的不可变值类型。
 *
 * <p><b>合法状态模型(ADR-03)</b>:本类是不可变 {@code final} 值类型,合法状态由
 * 受控静态工厂 + 嵌套 {@link Outcome} / {@link FailureKind} 枚举表达,非法组合
 * (如「失败但没有 kind」「success + failure 字段并存」)在类型层面不可表示:
 *
 * <ul>
 *   <li>{@link #success()} / {@link #success(byte[])} — 写/删成功,GET 命中</li>
 *   <li>{@link #miss()} — GET 未命中(读侧语义别名,与 success 区分 MISS outcome)</li>
 *   <li>{@link #inserted()} / {@link #existing(byte[])} — PUT_IF_ABSENT 两态</li>
 *   <li>{@link #failure(CacheOperation, FailureKind, Throwable)} — 带 typed
 *       operation/kind/cause 的失败</li>
 * </ul>
 *
 * <p><b>查询</b>:
 * <ul>
 *   <li>{@link #isSuccess()} — 派生自 {@link Outcome}(非存储字段),success/miss/
 *       inserted/existing 为 true,failure 为 false</li>
 *   <li>{@link #outcome()} — typed {@link Outcome} 枚举(取代原 String outcome)</li>
 *   <li>{@link #resultBytes()} — GET 命中 / PUT_IF_ABSENT existing 的字节;防御性复制</li>
 *   <li>{@link #operation()} / {@link #failureKind()} — 仅 failure 非 null(typed)</li>
 *   <li>{@link #cause()} — 仅 failure 非 null</li>
 * </ul>
 *
 * <p>控制流(链推进决策)由 {@link HandlerResult#decision()} 单一承载,本类只表达
 * 「操作结果数据」,不混入控制流语义。
 *
 * <p><b>变更纪律</b>:setter / builder / String 状态 / 无诊断 failure 工厂已删除。
 * 扩展点:新增结果形态时先评估是否可用既有 {@link Outcome};必须新增枚举值时在
 * {@code switch} 使用处由编译器强制补齐。
 */
public final class CacheResult {

    /**
     * 结果细分状态 — 合法状态单一真理源。
     *
     * <p>success / miss / inserted / existing 均为成功态(负载不同),failure 为
     * 失败态。{@code isSuccess} 由本枚举派生,不单独存储,杜绝「success=true 但
     * outcome=FAILURE」类矛盾组合。
     */
    public enum Outcome {
        /** 成功写入 / 删除 / 普通 GET 命中(无值字节或写类操作) */
        SUCCESS,
        /** GET 未命中(读侧语义别名) */
        MISS,
        /** PUT_IF_ABSENT 插入成功 */
        INSERTED,
        /** PUT_IF_ABSENT 发现已存在(携带既有值字节) */
        EXISTING,
        /** 失败(携带 typed operation / kind / cause) */
        FAILURE
    }

    /**
     * 失败分类 — typed 枚举(取代原 String failureKind)。
     *
     * <p>与 {@link io.github.davidhlp.spring.cache.redis.cache.CacheErrorHandler}
     * 的分类逻辑对齐,但作为值类型上的正式契约,供调用方按 kind 分流。
     */
    public enum FailureKind {
        /** Redis / 底层存储 I/O 失败 */
        REDIS,
        /** 序列化失败(与 Redis 失败分类分离) */
        SERIALIZATION,
        /** 提前过期取消 / 线程中断 */
        CANCELLATION,
        /** 超时 */
        TIMEOUT,
        /** CLEAN 部分删除后失败(已删部分 key,剩余未删) */
        PARTIAL_CLEAN
    }

    private final Outcome outcome;
    private final CacheOperation operation;
    private final FailureKind failureKind;
    private final Throwable cause;
    private final byte[] resultBytes;

    private CacheResult(Outcome outcome,
                        @Nullable CacheOperation operation,
                        @Nullable FailureKind failureKind,
                        @Nullable Throwable cause,
                        @Nullable byte[] resultBytes) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.operation = operation;
        this.failureKind = failureKind;
        this.cause = cause;
        this.resultBytes = defensiveCopy(resultBytes);
    }

    // ==================== 静态工厂 ====================

    /** 创建成功的结果(无返回值) */
    public static CacheResult success() {
        return new CacheResult(Outcome.SUCCESS, null, null, null, null);
    }

    /** 创建成功的结果(带返回值字节,防御性复制) */
    public static CacheResult success(@Nullable byte[] resultBytes) {
        return new CacheResult(Outcome.SUCCESS, null, null, null, resultBytes);
    }

    /** 创建缓存未命中的结果 */
    public static CacheResult miss() {
        return new CacheResult(Outcome.MISS, null, null, null, null);
    }

    /** 创建 PUT_IF_ABSENT 插入成功的结果 */
    public static CacheResult inserted() {
        return new CacheResult(Outcome.INSERTED, null, null, null, null);
    }

    /** 创建 PUT_IF_ABSENT 发现已有 key 的结果(带既有值字节,防御性复制) */
    public static CacheResult existing(@Nullable byte[] resultBytes) {
        return new CacheResult(Outcome.EXISTING, null, null, null, resultBytes);
    }

    /**
     * 创建带 typed operation / kind / cause 的失败结果 — 唯一失败工厂。
     *
     * @param operation 失败的操作(不得为 null — 失败必须可归类)
     * @param kind      失败分类
     * @param cause     原始原因
     */
    public static CacheResult failure(CacheOperation operation,
                                      FailureKind kind,
                                      Throwable cause) {
        return new CacheResult(Outcome.FAILURE,
                Objects.requireNonNull(operation, "operation"),
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(cause, "cause"),
                null);
    }

    // ==================== 查询 ====================

    /** 结果细分状态(typed)。 */
    public Outcome outcome() {
        return outcome;
    }

    /**
     * 是否成功 — 派生自 {@link Outcome}。{@code BloomFilterHandler.afterChainExecution}
     * 据此决定是否回填布隆;失败态为唯一 false。
     */
    public boolean isSuccess() {
        return outcome != Outcome.FAILURE;
    }

    /** 返回的字节数组(用于 GET 命中 / PUT_IF_ABSENT existing,由 RedisProCacheWriter 消费);防御性复制。 */
    @Nullable
    public byte[] resultBytes() {
        return defensiveCopy(resultBytes);
    }

    /** 失败时的缓存操作(仅 failure 结果非 null)。 */
    @Nullable
    public CacheOperation operation() {
        return operation;
    }

    /** 失败分类(仅 failure 结果非 null)。 */
    @Nullable
    public FailureKind failureKind() {
        return failureKind;
    }

    /** 失败的原始原因(仅 failure 结果非 null)。 */
    @Nullable
    public Throwable cause() {
        return cause;
    }

    private static byte[] defensiveCopy(@Nullable byte[] source) {
        return source == null ? null : Arrays.copyOf(source, source.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CacheResult that)) {
            return false;
        }
        return outcome == that.outcome
                && operation == that.operation
                && failureKind == that.failureKind
                && Objects.equals(cause, that.cause)
                && Arrays.equals(resultBytes, that.resultBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(outcome, operation, failureKind, cause);
        result = 31 * result + Arrays.hashCode(resultBytes);
        return result;
    }

    @Override
    public String toString() {
        return "CacheResult{outcome=" + outcome
                + ", operation=" + operation
                + ", failureKind=" + failureKind
                + ", cause=" + (cause == null ? null : cause.getClass().getSimpleName())
                + ", resultBytes=" + (resultBytes == null ? null : resultBytes.length + " bytes")
                + '}';
    }
}
