package io.github.davidhlp.spring.cache.redis.chain.model;

import io.github.davidhlp.spring.cache.redis.chain.*;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import lombok.Getter;

import java.time.Duration;

/**
 * 缓存操作上下文 — 组合输入 + 类型化 handler 间消息 + 引擎控制流标记.
 *
 * <p><b>ADR-0033 (Round 24)</b>：删除原 {@code CacheOutput} 共享可变袋,改为三段
 * locality-first 模型:
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
 * <p><b>ADR-0061 (Round 46) — attributes 袋彻底删除</b>:原
 * <em>通用字符串键 attributes Map</em> (5 个 helper 方法: {@code setAttribute}/
 * {@code getAttribute}/{@code getAttribute-with-default}/{@code removeAttribute}/
 * {@code hasAttribute})被彻底删除。曾经的"临时键"用途(observer MDC/Timer 跨 start/end
 * 状态共享)由 observer scope token 机制承担 —— Engine 在 {@code onChainStart} 收集每个
 * observer 返回的 token,按 index 配对回传 {@code onChainEnd}。observer 状态机完全自承,
 * 无需通过 context.attributes 中转,字符串键漂移风险归零。
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
 * <p><b>删除测试</b>(历史) —— Round 24 删除 CacheOutput, Round 26 删除 PrefetchDecision 的
 * 3 个 magic-string key, Round 46 删除整个 attributes 袋:
 * 任何 observer 临时数据通信需求,改为各自返回 scope token record,跨 observer 不共享协议。
 */
public class CacheContext {

    /** 输入参数（不可变）。 */
    @Getter
    private final CacheInput input;

    /**
     * TTL 决策 — 由 {@code TtlHandler.doHandle} 写入、
     * {@code ActualCacheHandler.handlePut/handlePutIfAbsent} 读取。
     * 类型化替代原 {@code CacheOutput.shouldApplyTtl}/{@code finalTtl}/{@code ttlFromContext}
     * 三字段共享袋（ADR-0033）。
     */
    @Getter
    @lombok.Setter
    private TtlDecision ttlDecision;

    /**
     * Null 值处理决策 — 由 {@code NullValueHandler.doHandle} 写入、
     * {@code ActualCacheHandler.handlePut/handlePutIfAbsent} 读取。
     * 类型化替代原 {@code CacheOutput.storeValue} 单字段（ADR-0033）。
     */
    @Getter
    @lombok.Setter
    private NullDecision nullDecision;

    /**
     * 预取/提前过期决策 — 由 {@code EarlyExpirationHandler.doHandle} 写入、
     * {@code ActualCacheHandler.handleGet} 读取。类型化替代原 attributes Map 的 3 个业务
     * magic-string key({@code earlyExpiration.skipped}/{@code cache.prefetchedValue}/
     * {@code earlyExpiration.decision},ADR-0036 / Round 26 C1)。生产者/消费者一一对应。
     */
    @Getter
    @lombok.Setter
    private PrefetchDecision prefetchDecision;

    /**
     * 键模式 — 仅 {@link io.github.davidhlp.spring.cache.redis.chain.CacheOperation#CLEAN}
     * 操作由 {@code RedisProCacheWriter.clean} 写入,
     * {@code ActualCacheHandler.handleClean} 读取。从原 {@code CacheOutput.keyPattern}
     * 迁移到 context 一级字段（ADR-0033：writer→handler 跨包共享，提到 context 减少泄漏）。
     */
    @Getter
    @lombok.Setter
    @org.springframework.lang.Nullable
    private String keyPattern;

    /**
     * 引擎控制流标记 — SKIP_ALL 决策的物化状态。由 {@code ChainEngine.driveChain}
     * 在遇到 SKIP_ALL 时单点置位，{@code ChainEngine.driveChain} 节点循环开头
     * 检测短路、{@code BloomFilterHandler.afterChainExecution} 读它决定是否执行后置回填。
     * <p>handler 不应自行读它判自身行为（仅在返回后才生效）—— 与 ADR-0009 engine 推进协议一致。
     */
    private boolean skipRemaining = false;

    public CacheContext(CacheInput input) {
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
        return input.valueBytes();
    }

    public Object getDeserializedValue() {
        return input.deserializedValue();
    }

    public Duration getTtl() {
        return input.ttl();
    }

    public RedisCacheableOperation getCacheOperation() {
        return input.cacheOperation();
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

    public static CacheContext of(CacheInput input) {
        return new CacheContext(input);
    }
}
