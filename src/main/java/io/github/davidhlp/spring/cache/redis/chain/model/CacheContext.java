package io.github.davidhlp.spring.cache.redis.chain.model;




import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import java.time.Duration;
import java.util.Arrays;
import lombok.Getter;

/**
 * 缓存操作上下文 — 组合输入 + 类型化 handler 间消息 + 引擎控制流标记.
 *
 * <p>三段 locality-first 模型:
 * <ul>
 *   <li><b>input</b> — 不可变 record,请求原始参数,全程只读</li>
 *   <li><b>类型化决策</b> — {@link TtlDecision} (TtlHandler→ActualCacheHandler)、
 *       {@link NullDecision} (NullValueHandler→ActualCacheHandler)、{@link PrefetchDecision}
 *       (EarlyExpirationHandler→ActualCacheHandler)、{@link #keyPattern}
 *       (RedisProCacheWriter→ActualCacheHandler);生产者/消费者一一对应,无共享字段,
 *       编译期类型约束</li>
 *   <li><b>控制流标记</b> — {@link #skipRemaining} 由 {@code ChainEngine} 在
 *       SKIP_ALL 决策时单点置位、{@code BloomFilterHandler.afterChainExecution}
 *       读取以决定是否执行后置回填</li>
 * </ul>
 *
 * <p><b>使用方式</b>:
 * <ul>
 *   <li>构造：{@code CacheContext.of(new CacheInput(operation, name, key, …))}</li>
 *   <li>读取操作参数：{@code context.getOperation()}, {@code context.getCacheName()} 等</li>
 *   <li>读取 handler 间决策：{@code context.getTtlDecision().finalTtl()}、
 *       {@code context.getNullDecision().storeValue()}</li>
 *   <li>读取 keyPattern(CLEAN):{@code context.getKeyPattern()}</li>
 *   <li>检查控制流：{@code context.isSkipRemaining()}</li>
 * </ul>
 *
 * <p><b>observer 临时数据通信</b>:observer 各自返回 scope token record,
 * 跨 observer 不共享协议;Engine 在 {@code onChainStart} 收集每个 observer 返回的 token,
 * 按 index 配对回传 {@code onChainEnd}。
 */
public class CacheContext {

    /** Stable input view; the concrete record remains an internal implementation detail. */
    public interface InputView {
        CacheOperation operation();
        String cacheName();
        String redisKey();
        String actualKey();
        byte[] valueBytes();
        Object deserializedValue();
        Duration ttl();
        CachePolicyView policy();
    }

    /** 输入参数（不可变）。P1-API-001-B:不暴露 {@code CacheInput} — 只经定向 accessor 读。 */
    private final InputView input;

    /**
     * TTL 决策 — 由 {@code TtlHandler.doHandle} 写入、
     * {@code ActualCacheHandler.handlePut/handlePutIfAbsent} 读取。
     */
    @Getter
    @lombok.Setter
    private TtlDecision ttlDecision;

    /**
     * Null 值处理决策 — 由 {@code NullValueHandler.doHandle} 写入、
     * {@code ActualCacheHandler.handlePut/handlePutIfAbsent} 读取。
     */
    @Getter
    @lombok.Setter
    private NullDecision nullDecision;

    /**
     * 预取/提前过期决策 — 由 {@code EarlyExpirationHandler.doHandle} 写入、
     * {@code ActualCacheHandler.handleGet} 读取。生产者/消费者一一对应。
     */
    @Getter
    @lombok.Setter
    private PrefetchDecision prefetchDecision;

    /**
     * 键模式 — 仅 {@link io.github.davidhlp.spring.cache.redis.chain.CacheOperation#CLEAN}
     * 操作由 {@code RedisProCacheWriter.clean} 写入,
     * {@code ActualCacheHandler.handleClean} 读取。writer→handler 跨包共享,提到 context
     * 一级字段以减少泄漏。
     */
    @Getter
    @lombok.Setter
    @org.springframework.lang.Nullable
    private String keyPattern;

    /**
     * 引擎控制流标记 — SKIP_ALL 决策的物化状态。由 {@code ChainEngine.driveChain}
     * 在遇到 SKIP_ALL 时单点置位，{@code ChainEngine.driveChain} 节点循环开头
     * 检测短路、{@code BloomFilterHandler.afterChainExecution} 读它决定是否执行后置回填。
     * <p>handler 不应自行读它判自身行为（仅在返回后才生效）—— 与 engine 推进协议一致。
     */
    private boolean skipRemaining = false;

    public CacheContext(InputView input) {
        this.input = input;
    }

    // ==================== 便捷访问方法（委托到 input） ====================

    public CacheOperation getOperation() {
        return input.operation();
    }

    public String getCacheName() {
        return input.cacheName();
    }

    public String getRedisKey() {
        return input.redisKey();
    }

    public String getActualKey() {
        return input.actualKey();
    }

    public byte[] getValueBytes() {
        byte[] valueBytes = input.valueBytes();
        return valueBytes == null ? null : Arrays.copyOf(valueBytes, valueBytes.length);
    }

    public Object getDeserializedValue() {
        return input.deserializedValue();
    }

    public Duration getTtl() {
        return input.ttl();
    }

    /**
     * 方法级缓存策略稳定视图 — 由内部 {@code RedisCacheableOperation} 派生。
     *
     * <p>P1-API-001-B:稳定 {@link io.github.davidhlp.spring.cache.redis.chain.CacheHandler}
     * 读取 {@link CachePolicyView}(ttl/randomTtl/variance/useBloomFilter/sync/…),不再
     * 依赖内部 operation 类型。{@code cacheOperation} 为 null 时返回 {@link CachePolicyView#NONE}。
     */
    public CachePolicyView policy() {
        return input.policy();
    }

    // ==================== 控制流（skipRemaining） ====================

    /** 是否已请求跳过后续处理器 — ChainEngine 在 SKIP_ALL 时物化此标记。 */
    public boolean isSkipRemaining() {
        return skipRemaining;
    }

    /** 物化跳过标记 — 仅 ChainEngine.driveChain 在 SKIP_ALL 分支调用。 */
    public void markSkipRemaining() {
        this.skipRemaining = true;
    }

    // ==================== 静态工厂方法 ====================

    public static CacheContext of(InputView input) {
        return new CacheContext(input);
    }
}
