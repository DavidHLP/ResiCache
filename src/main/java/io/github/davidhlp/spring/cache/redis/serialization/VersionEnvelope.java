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
 * <p>payload 字段使用 @JsonTypeInfo(Id.CLASS) 保留类型信息，
 * 安全性由 SecureJacksonRedisSerializer 的 validateTypeIds() 二次校验保障，
 * 确保反序列化时类型在白名单中。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VersionEnvelope {

    /** 序列化格式版本号 */
    private int version;

    /** 实际承载的缓存值 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    private Object payload;

    /** 当前支持的版本号 */
    public static final int CURRENT_VERSION = 2;
}
