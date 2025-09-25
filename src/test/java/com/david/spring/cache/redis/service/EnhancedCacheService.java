package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCaching;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 增强的缓存服务示例
 * 展示如何使用集成了设计模式的缓存注解
 */
@Service
public class EnhancedCacheService {

    private final Map<String, Object> mockDatabase = new HashMap<>();

    /**
     * 用户缓存 - 使用建造者模式配置的高性能缓存
     * 特性：预刷新 + 布隆过滤器 + 随机TTL + 分布式锁
     */
    @RedisCacheable(
            cacheNames = "userCache",
            key = "#userId",
            ttl = 3600,  // 1小时
            distributedLock = true,      // 使用分布式锁策略
            useBloomFilter = true,       // 启用布隆过滤器
            enablePreRefresh = true,     // 启用预刷新
            preRefreshThreshold = 0.2,   // 20%时触发预刷新
            randomTtl = true,            // 启用随机TTL
            variance = 0.1f,             // 10%的TTL随机变化
            cacheType = "REDIS",         // 使用Redis缓存工厂
            executionStrategy = "DISTRIBUTED_LOCK"  // 使用分布式锁策略
    )
    public Object getUserById(String userId) {
        System.out.println("🔍 查询用户数据库: " + userId);

        // 模拟数据库查询延迟
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Object user = mockDatabase.get("user:" + userId);
        if (user == null) {
            user = Map.of(
                "id", userId,
                "name", "用户-" + userId,
                "email", userId + "@example.com",
                "createTime", System.currentTimeMillis()
            );
            mockDatabase.put("user:" + userId, user);
        }

        return user;
    }

    /**
     * 产品缓存 - 使用分层缓存提升性能
     * 特性：分层缓存 + 随机TTL + 内部锁
     */
    @RedisCacheable(
            cacheNames = "productCache",
            key = "#productId",
            ttl = 14400,  // 4小时
            internalLock = true,         // 使用内部锁策略
            randomTtl = true,            // 启用随机TTL
            variance = 0.3f,             // 30%的TTL随机变化
            cacheType = "LAYERED",       // 使用分层缓存工厂
            executionStrategy = "SYNC"   // 使用同步策略
    )
    public Object getProductById(String productId) {
        System.out.println("🛍️ 查询产品数据库: " + productId);

        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Object product = mockDatabase.get("product:" + productId);
        if (product == null) {
            product = Map.of(
                "id", productId,
                "name", "产品-" + productId,
                "price", Math.random() * 1000,
                "stock", (int)(Math.random() * 100),
                "updateTime", System.currentTimeMillis()
            );
            mockDatabase.put("product:" + productId, product);
        }

        return product;
    }

    /**
     * 会话缓存 - 短期存储，观察者模式监控
     * 特性：短TTL + 禁用空值 + 事件监控
     */
    @RedisCacheable(
            cacheNames = "sessionCache",
            key = "#sessionId",
            ttl = 900,    // 15分钟
            cacheNullValues = false,     // 禁用空值缓存
            sync = true,                 // 启用同步
            cacheType = "REDIS",
            executionStrategy = "DEFAULT"
    )
    public Object getSessionData(String sessionId) {
        System.out.println("🔐 查询会话数据: " + sessionId);

        if (sessionId.startsWith("invalid")) {
            return null;  // 无效会话返回null
        }

        Object session = mockDatabase.get("session:" + sessionId);
        if (session == null) {
            session = Map.of(
                "sessionId", sessionId,
                "userId", "user-" + sessionId.hashCode(),
                "loginTime", System.currentTimeMillis(),
                "ipAddress", "192.168.1." + (Math.abs(sessionId.hashCode()) % 255)
            );
            mockDatabase.put("session:" + sessionId, session);
        }

        return session;
    }

    /**
     * 组合注解使用 - 同时使用多种缓存操作
     */
    @RedisCaching(
            cacheable = {
                @RedisCacheable(
                        cacheNames = "searchCache",
                        key = "#keyword",
                        ttl = 1800,  // 30分钟
                        useBloomFilter = true,
                        cacheType = "REDIS"
                ),
                @RedisCacheable(
                        cacheNames = "popularSearchCache",
                        key = "'popular:' + #keyword",
                        ttl = 7200,  // 2小时
                        condition = "#keyword.length() <= 10",  // 只缓存短关键词
                        cacheType = "LAYERED"
                )
            }
    )
    public Object searchProducts(String keyword) {
        System.out.println("🔍 搜索产品: " + keyword);

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 模拟搜索结果
        return Map.of(
            "keyword", keyword,
            "results", java.util.List.of(
                "产品-" + keyword + "-1",
                "产品-" + keyword + "-2",
                "产品-" + keyword + "-3"
            ),
            "count", 3,
            "searchTime", System.currentTimeMillis()
        );
    }

    /**
     * 清理缓存 - 支持模式匹配
     */
    @RedisCacheEvict(
            cacheNames = {"userCache", "sessionCache"},
            key = "#userId",
            condition = "#clearAll == false"
    )
    public void clearUserCache(String userId, boolean clearAll) {
        System.out.println("🗑️ 清理用户缓存: " + userId + ", clearAll=" + clearAll);
    }

    /**
     * 清理所有缓存
     */
    @RedisCacheEvict(
            cacheNames = {"userCache", "productCache", "sessionCache", "searchCache", "popularSearchCache"},
            allEntries = true
    )
    public void clearAllCache() {
        System.out.println("🗑️ 清理所有缓存");
        mockDatabase.clear();
    }
}