package io.github.davidhlp.spring.cache.redis.cache;




import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

/**
 * 默认空值策略 — 4 个纯决策 / 恒等变换方法,外加 {@code toReturnValue} 委派给
 * {@link NullValueEncoder} 完成 null-aware 字节编码。
 *
 * <p>本类不 {@code import TypeSupport},全部类型支持职责经 {@link NullValueEncoder}
 * 转交(单一职责)。
 *
 * <p>遵循 Spring 缓存对 null 值的预期处理语义。
 */
@Slf4j
@RequiredArgsConstructor
class DefaultNullValuePolicy implements NullValuePolicy {

    private final NullValueEncoder encoder;

    /**
     * 将值转换为存储格式 — null 且应缓存时返回 null 占位。
     *
     * @param value           缓存的原始值
     * @param cacheNullValues 方法级「是否缓存 null」(来自稳定 policy 视图)
     * @return 转换后的存储值
     */
    @Nullable
    public Object toStoreValue(@Nullable Object value, boolean cacheNullValues) {
        if (value == null && cacheNullValues) {
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
