package io.github.davidhlp.spring.cache.redis.chain.model;

import org.springframework.lang.Nullable;

/**
 * Null 值处理决策 — {@link io.github.davidhlp.spring.cache.redis.protection.nullvalue.NullValueHandler}
 * 写入、由 {@link io.github.davidhlp.spring.cache.redis.chain.ActualCacheHandler} 读取的
 * 类型化跨 handler 消息。
 *
 * <p><b>ADR-0033 替代 {@code CacheOutput.storeValue} 单字段</b>:
 * <ul>
 *   <li>原设计：handler 把 {@code storeValue} 写进 {@code CacheOutput}（mutable bean），
 *       下游 handler 通过 {@code context.getOutput().getStoreValue()} 读</li>
 *   <li>新设计：本 record 由唯一生产者 {@code NullValueHandler} 写入、由唯一消费者
 *       {@code ActualCacheHandler.handlePut/handlePutIfAbsent} 读取</li>
 *   <li>{@code storeValue == null} 合法（表示"无需转换，直接用 input.deserializedValue"）—
 *       由 record {@code @Nullable} 标注承载</li>
 * </ul>
 *
 * @param storeValue 转换后的存储值；{@code null} 表示沿用
 *                 {@link CacheInput#deserializedValue()}
 */
public record NullDecision(@Nullable Object storeValue) {

    /** 决策中"无转换，沿用输入值"的形态。 */
    public static NullDecision passthrough() {
        return new NullDecision(null);
    }

    /** 决策中"使用此值"的形态。 */
    public static NullDecision of(@Nullable Object storeValue) {
        return new NullDecision(storeValue);
    }
}