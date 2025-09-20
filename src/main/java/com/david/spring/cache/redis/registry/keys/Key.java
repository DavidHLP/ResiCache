package com.david.spring.cache.redis.registry.keys;

import jakarta.annotation.Nonnull;
import org.springframework.lang.Nullable;

/**
 * 缓存键的复合记录类，用于唯一标识缓存项
 * 主人，现在这个Key类更加灵活和健壮了喵~
 */
public record Key(String cacheName, @Nullable Object key) {

    /**
     * 创建Key实例，对null key进行标准化处理
     * 
     * @param cacheName 缓存名称，不能为null
     * @param key       缓存键，可以为null
     * @return Key实例
     * @throws IllegalArgumentException 如果cacheName为null
     */
    public static Key of(String cacheName, @Nullable Object key) {
        if (cacheName == null) {
            throw new IllegalArgumentException("Cache name cannot be null");
        }
        return new Key(cacheName, key);
    }

    /**
     * 创建通配符Key实例，用于表示缓存名称下的所有条目
     * 
     * @param cacheName 缓存名称
     * @return 通配符Key实例
     */
    public static Key wildcard(String cacheName) {
        return new Key(cacheName, "*");
    }

    /**
     * 判断是否为通配符键
     * 
     * @return 如果key为"*"则返回true
     */
    public boolean isWildcard() {
        return "*".equals(key);
    }

    /**
     * 获取标准化的键值，null会被转换为"*"
     * 
     * @return 标准化后的键值
     */
    @Nonnull
    public Object normalizedKey() {
        return key != null ? key : "*";
    }

    @Override
    @Nonnull
    public String toString() {
        return cacheName + "::" + normalizedKey();
    }
}
