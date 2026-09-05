package io.github.davidhlp.spring.cache.redis.cache;




import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.support.NullValue;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Null-aware 字节编码器 seam — 单一职责:把"是否将 {@code null} 编码为
 * {@link NullValue#INSTANCE}"的 null 决策与"实际字节生产"两类职责解耦。
 *
 * <p>本类承接 null 决策层:{@code value == null ⇒ NullValue.INSTANCE}。
 * 字节生产由 {@link TypeSupport} 完成(经 {@code SecureNullValueDeserializer}
 * 走白名单往返);本类作为决策层,不强求知晓字节内部细节。
 *
 * <p><b>依赖方向</b>:{@code NullValueEncoder} → {@code TypeSupport}(单向,
 * 无循环)。{@code TypeSupport} 不感知上层 null 决策,两条流水线各司其职。
 *
 * <p>本类是 {@code DefaultNullValuePolicy} 的协作者;不暴露为独立 interface —
 * 字节编码是实现细节,不属于可替换策略面(对齐 {@code SecureNullValueDeserializer}
 * 的 final 工具类纪律)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class NullValueEncoder {

    private final TypeSupport typeSupport;

    /**
     * 将缓存返回值编码为字节,完成 null 决策的最后一英里。
     *
     * <p>contract:
     * <ul>
     *   <li>{@code value == null} ⇒ 返回 {@link NullValue#INSTANCE} 的字节(由
     *       {@code TypeSupport.serializeToBytes} 内部识别 + 安全 Java 序列化往返)</li>
     *   <li>{@code value != null} ⇒ 原值直通 {@code TypeSupport.serializeToBytes}</li>
     * </ul>
     *
     * @param value 缓存返回值(可为 {@code null} 或任意类型)
     * @param cacheName 缓存名(仅用于 debug 日志定位)
     * @param key 缓存键(仅用于 debug 日志定位)
     * @return 序列化后的字节;{@code value == null} 时为 {@code NullValue.INSTANCE} 字节
     */
    @Nullable
    public byte[] encodeForReturn(
            @Nullable Object value, String cacheName, String key) {
        if (value == null) {
            log.debug(
                    "Returning null value in standard format: cacheName={}, key={}",
                    cacheName,
                    key);
            return typeSupport.serializeToBytes(NullValue.INSTANCE);
        }
        return typeSupport.serializeToBytes(value);
    }
}
