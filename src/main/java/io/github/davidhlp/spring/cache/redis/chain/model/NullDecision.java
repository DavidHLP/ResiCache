package io.github.davidhlp.spring.cache.redis.chain.model;



import org.springframework.lang.Nullable;

/**
 * Null 值处理决策 — {@link io.github.davidhlp.spring.cache.redis.cache.NullValueHandler}
 * 写入、由 {@link io.github.davidhlp.spring.cache.redis.cache.ActualCacheHandler} 读取的
 * 类型化跨 handler 消息。
 *
 * @param storeValue 转换后的存储值；{@code null} 表示沿用输入中的反序列化值
 */
public record NullDecision(@Nullable Object storeValue) {

    /** 决策中"使用此值"的形态（{@code null} 表示沿用输入中的反序列化值）。 */
    public static NullDecision of(@Nullable Object storeValue) {
        return new NullDecision(storeValue);
    }
}
