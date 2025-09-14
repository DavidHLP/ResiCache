package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.service.entity.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheAvalancheService {
    // 一个静态的 ConcurrentHashMap，用于模拟内存数据库。
    // 它被声明为 static，以确保只初始化一次，并被所有实例共享。
    private static final Map<Long, User> DATABASE = new ConcurrentHashMap<>();

    // 静态初始化块，用于在类加载时填充一些示例数据。
    static {
        DATABASE.put(1L, new User(1L, "Alice", 25L));
        DATABASE.put(2L, new User(2L, "Bob", 30L));
        DATABASE.put(3L, new User(3L, "Charlie", 35L));
        DATABASE.put(4L, new User(4L, "David", 40L));
        DATABASE.put(5L, new User(5L, "Eva", 45L));
        DATABASE.put(6L, new User(6L, "Frank", 50L));
        DATABASE.put(7L, new User(7L, "Grace", 28L));
        DATABASE.put(8L, new User(8L, "Heidi", 32L));
        DATABASE.put(9L, new User(9L, "Ivan", 38L));
        DATABASE.put(10L, new User(10L, "Judy", 42L));
        // 添加更多测试数据
        for (long i = 11; i <= 100; i++) {
            DATABASE.put(i, new User(i, "TestUser" + i, 20L + (i % 50)));
        }
    }

    // 用于统计数据库访问次数的计数器
    private final AtomicInteger dbAccessCount = new AtomicInteger(0);
    private final AtomicInteger slowQueryCount = new AtomicInteger(0);
    private final ApplicationContext applicationContext;

    /** 模拟根据 ID 查询用户，并将结果缓存。 默认缓存名：userCache，key 为 id。 */
    @RedisCacheable(value = "userCache", key = "#id")
    public User getUserById(Long id) {
        // 模拟回源查询
        int c = dbAccessCount.incrementAndGet();
        log.debug("DB load user by id={}, accessCount={}", id, c);
        return DATABASE.get(id);
    }

    /** 可选：模拟慢查询以观测缓存命中效果。 */
    @RedisCacheable(value = "userCache", key = "'slow:' + #id")
    public User getUserByIdSlow(Long id) {
        slowQueryCount.incrementAndGet();
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {
        }
        int c = dbAccessCount.incrementAndGet();
        log.debug("DB slow load user by id={}, accessCount={}", id, c);
        return DATABASE.get(id);
    }

    /** 测试结束后清理 userCache 的所有条目。 */
    @RedisCacheEvict(value = "userCache", allEntries = true)
    public void evictAllUserCache() {
        log.info("Evict all entries in userCache");
    }

    public int getDbAccessCount() {
        return dbAccessCount.get();
    }

    public int getSlowQueryCount() {
        return slowQueryCount.get();
    }

    public void resetCounters() {
        dbAccessCount.set(0);
        slowQueryCount.set(0);
    }
}
