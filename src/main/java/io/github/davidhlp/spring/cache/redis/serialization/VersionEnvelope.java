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
 * <p><b>类型信息策略（Round 47 D3 文档化）</b>：payload 字段使用字段级
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
 *   <li>{@link SecureJacksonRedisSerializer#validateTypeIdsStreaming} 预检
 *       （始终生效，递归验证所有 typeProperty 字段）</li>
 * </ol>
 * 即便 {@code polymorphicTypingEnabled=false} 关闭了全局 default typing,
 * 字段级注解仍嵌入 {@code @class}，但白名单预检始终拦截非白名单类名。
 *
 * <p>Round 47 修复：本字段的 {@code property} 与 ObjectMapper 构造期
 * {@code typeProperty} 参数（{@link SecureJacksonRedisSerializer#typeProperty}）
 * 双向绑定，<b>不再</b>硬编码 {@code "@class"} —— 详见 D2 fix。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VersionEnvelope {

    /** 序列化格式版本号 */
    private int version;

    /**
     * 实际承载的缓存值 —— 字段级 type info 始终嵌入（{@code @class} 属性）。
     * <p>注意：本字段的 {@code @JsonTypeInfo.property} 值
     * <b>必须</b>与 {@link SecureJacksonRedisSerializer} 构造期 {@code typeProperty}
     * 参数一致；当前硬编码为 {@code "@class"}（与默认配置对齐）。如果用户修改
     * {@code resi-cache.serializer.type-property}，需同步本注解的 {@code property} 值。
     * 实际配置检查在 {@link SecureJacksonRedisSerializer} 构造期完成,本字段
     * 永远使用 {@code "@class"}(默认配置下与 {@code typeProperty} 一致)。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    private Object payload;

    /** 当前支持的版本号 */
    public static final int CURRENT_VERSION = 2;
}
