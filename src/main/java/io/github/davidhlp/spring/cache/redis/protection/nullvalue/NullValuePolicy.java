package io.github.davidhlp.spring.cache.redis.protection.nullvalue;

import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import org.springframework.lang.Nullable;

/**
 * 空值策略 seam:封装 null 缓存判定、存储/返回值转换、null 值识别(防穿透)。
 *
 * <p>默认实现 {@link DefaultNullValuePolicy} 为 Spring {@code @Component};自定义实现声明
 * {@code @Bean} 即可顶替(对齐 {@code LockManager} / {@code BloomIFilter} 的可替换纪律,
 * 落实 ADR-0005)。{@code NullValueHandler} 依赖本接口而非具体类。
 *
 * <p>此前 {@code DefaultNullValuePolicy} 是无接口的 {@code @Component}(假 seam)。本接口
 * 把它升为真 seam。
 */
public interface NullValuePolicy {

    /** 是否应缓存 null(由 {@code cacheOperation.cacheNullValues} 决定)。 */
    boolean shouldCacheNull(@Nullable RedisCacheableOperation cacheOperation);

    /** 转存储格式;null 且应缓存时返回 null 占位。 */
    @Nullable
    Object toStoreValue(@Nullable Object value, @Nullable RedisCacheableOperation cacheOperation);

    /** 从存储值还原(默认恒等)。 */
    @Nullable
    Object fromStoreValue(@Nullable Object storeValue);

    /** 是否为 null 值。 */
    boolean isNullValue(@Nullable Object value);

    /** 转返回字节;null 值序列化为 Spring {@code NullValue.INSTANCE}。 */
    @Nullable
    byte[] toReturnValue(@Nullable Object value, String cacheName, String key);
}
