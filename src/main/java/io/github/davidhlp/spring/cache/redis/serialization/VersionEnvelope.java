package io.github.davidhlp.spring.cache.redis.serialization;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缓存值版本信封
 *
 * <p>用于包装存入 Redis 的缓存值，提供版本控制和能力协商：
 * <ul>
 *   <li>版本号允许未来升级序列化格式时进行平滑迁移</li>
 *   <li>payload 承载实际的缓存值（通常是 {@code CachedValue}）</li>
 * </ul>
 *
 * <p><b>类型信息策略</b>：payload 字段使用字段级
 * {@code @JsonTypeInfo} —— 这是 wire format 的实际承重者，移除它会破坏
 * 反序列化路径（payload 退化为 LinkedHashMap，{@code CachedValue} 等
 * 自定义类型丢失）。
 *
 * <p>{@code resi-cache.serializer.polymorphic-typing-enabled} 标志控制的是
 * ObjectMapper 全局 {@code setDefaultTyping} —— 用于 <em>无</em> 字段级注解的类
 * 是否需要类型信息。开启后非 final 类（如 {@code Object}、{@code Map} 子类）
 * 的字段也会被附加 {@code @class}，与本 envelope 的字段级注解是两条独立路径。
 *
 * <p>安全性来自<b>双重</b>防御：
 * <ol>
 *   <li>ObjectMapper 全局 {@code BasicPolymorphicTypeValidator}（{@code
 *       polymorphicTypingEnabled=true} 时生效）</li>
 *   <li>Serializer streaming type-id validation 预检
 *       （始终生效，递归验证所有 typeProperty 字段）</li>
 * </ol>
 * 即便 {@code polymorphicTypingEnabled=false} 关闭了全局 default typing,
 * 字段级注解仍嵌入 {@code @class}，但白名单预检始终拦截非白名单类名。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
class VersionEnvelope {

    /** 序列化格式版本号 */
    private int version;

    /**
     * Payload type information always uses the v2 wire property {@code @class}.
     * Configurable global default typing is independent of this field-level annotation.
     * The serializer validates both type-id paths before binding.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    private Object payload;

    /** 当前支持的版本号 */
    public static final int CURRENT_VERSION = 2;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    static VersionEnvelope create(Object payload) {
        return new VersionEnvelope(CURRENT_VERSION, payload);
    }

    static VersionEnvelope read(com.fasterxml.jackson.databind.ObjectMapper mapper, byte[] bytes)
            throws java.io.IOException {
        return mapper.readValue(bytes, VersionEnvelope.class);
    }

    static boolean isEnvelope(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(bytes);
            return node != null && node.isObject() && node.has("version") && node.has("payload");
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
