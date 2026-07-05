package io.github.davidhlp.spring.cache.redis.protection.nullvalue;

import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 默认空值策略 — 4 个纯决策 / 恒等变换方法,外加 {@code toReturnValue} 委派给
 * {@link NullValueEncoder} 完成 null-aware 字节编码。
 *
 * <p><b>Round 35 拆分动机(ADR-0047 C6 / ADR-0048)</b>:此前 5 方法中
 * {@code toReturnValue} 是唯一耦合 {@code TypeSupport} 的方法,混入字节编码职责。
 * 抽 seam 后本类不再 {@code import TypeSupport},全部类型支持职责经
 * {@code NullValueEncoder} 转交;类瘦身 ~42 SLOC,单一职责清晰。
 *
 * <p>遵循 Spring 缓存对 null 值的预期处理语义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultNullValuePolicy implements NullValuePolicy {

    private final NullValueEncoder encoder;

    /**
     * 判断是否应该缓存null值
     *
     * @param cacheOperation 缓存操作配置信息
     * @return 如果应该缓存null值则返回true，否则返回false
     */
    public boolean shouldCacheNull(@Nullable RedisCacheableOperation cacheOperation) {
        return cacheOperation != null && cacheOperation.isCacheNullValues();
    }

    /**
     * 将值转换为存储格式
     *
     * @param value 缓存的原始值
     * @param cacheOperation 缓存操作配置信息
     * @return 转换后的存储值
     */
    @Nullable
    public Object toStoreValue(
            @Nullable Object value, @Nullable RedisCacheableOperation cacheOperation) {
        if (value == null && shouldCacheNull(cacheOperation)) {
            log.debug("Caching null value directly");
            return null;
        }
        return value;
    }

    /**
     * 从存储值转换回原始值（恒等）
     *
     * @param storeValue 存储的值
     * @return 转换后的原始值
     */
    @Nullable
    public Object fromStoreValue(@Nullable Object storeValue) {
        return storeValue;
    }

    /**
     * 判断值是否为null值（{@code value == null}）
     *
     * @param value 待判断的值
     * @return 如果是null值则返回true，否则返回false
     */
    public boolean isNullValue(@Nullable Object value) {
        return value == null;
    }

    /**
     * 将值转换为返回字节 — 委派 {@link NullValueEncoder#encodeForReturn}。
     *
     * <p>本方法已成 1 行委派,null-aware 字节编码职责完全交给 seam 类。
     *
     * @param value 待转换的值
     * @param cacheName 缓存名称（用于 debug 日志定位）
     * @param key 缓存键（用于 debug 日志定位）
     * @return 转换后的字节数组
     */
    @Nullable
    public byte[] toReturnValue(
            @Nullable Object value, String cacheName, String key) {
        return encoder.encodeForReturn(value, cacheName, key);
    }
}
