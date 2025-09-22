package com.david.spring.cache.redis.management;

import com.david.spring.cache.redis.aspect.support.KeyResolver;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 统一的缓存键管理器
 * 负责缓存键的生成、格式化和验证
 */
@Slf4j
@Component
public class CacheKeyManager {

    /**
     * 生成缓存键
     *
     * @param targetBean    目标Bean
     * @param method        目标方法
     * @param arguments     方法参数
     * @param keyGenerator  键生成器名称
     * @return 生成的缓存键
     */
    @Nonnull
    public Object generateKey(@Nonnull Object targetBean, @Nonnull Method method,
                             @Nonnull Object[] arguments, @Nonnull String keyGenerator) {
        try {
            return KeyResolver.resolveKey(targetBean, method, arguments, keyGenerator);
        } catch (Exception e) {
            log.error("Failed to generate cache key using generator: {}, fallback to simple key generation",
                    keyGenerator, e);
            return generateFallbackKey(method, arguments);
        }
    }

    /**
     * 创建Redis缓存键
     *
     * @param cacheName       缓存名称
     * @param businessKey     业务键
     * @param configuration   Redis缓存配置
     * @return Redis存储键
     */
    @Nonnull
    public String createRedisCacheKey(@Nonnull String cacheName, @Nonnull Object businessKey,
                                     @Nonnull RedisCacheConfiguration configuration) {
        try {
            return configuration.getKeySerializationPair()
                    .getWriter()
                    .write(cacheName + "::" + businessKey)
                    .toString();
        } catch (Exception e) {
            log.warn("Failed to serialize cache key using configuration, using simple concatenation", e);
            return cacheName + "::" + businessKey;
        }
    }

    /**
     * 验证缓存键是否有效
     *
     * @param key 缓存键
     * @return true表示有效
     */
    public boolean isValidKey(@Nonnull Object key) {
        if (key == null) {
            return false;
        }

        String keyString = key.toString();
        if (keyString.trim().isEmpty()) {
            return false;
        }

        // 检查键长度（Redis键长度限制）
        if (keyString.length() > 512) {
            log.warn("Cache key too long: {} characters, max recommended: 512", keyString.length());
            return false;
        }

        return true;
    }

    /**
     * 生成备用缓存键（当主要键生成失败时使用）
     */
    @Nonnull
    private Object generateFallbackKey(@Nonnull Method method, @Nonnull Object[] arguments) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(method.getDeclaringClass().getSimpleName())
                  .append(".")
                  .append(method.getName());

        if (arguments.length > 0) {
            keyBuilder.append("(");
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) {
                    keyBuilder.append(",");
                }
                keyBuilder.append(arguments[i] != null ? arguments[i].toString() : "null");
            }
            keyBuilder.append(")");
        }

        return keyBuilder.toString();
    }

    /**
     * 格式化缓存键用于日志输出
     *
     * @param key 缓存键
     * @return 格式化后的键字符串
     */
    @Nonnull
    public String formatKeyForLog(@Nonnull Object key) {
        String keyString = key.toString();
        if (keyString.length() > 100) {
            return keyString.substring(0, 97) + "...";
        }
        return keyString;
    }
}