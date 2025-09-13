package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.service.entity.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个服务类，使用 ConcurrentHashMap 模拟本地内存数据库，并演示多种缓存策略。
 *
 * @author David
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

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

    /**
     * 从内存数据库中根据用户ID获取用户，并使用缓存。 - ttl = 60：缓存过期时间为 60 秒。 - key = "'user:' + #id"：缓存键的规则。 - condition
     * = "#id > 10"：只有当 id 大于 10 时才进行缓存。
     *
     * @param id 要获取的用户的ID。
     * @return 如果找到，返回 User 对象，否则返回 null。
     */
    @RedisCacheable(
            ttl = 60,
            key = "'user:' + #id",
            value = "User",
            type = User.class,
            condition = "#id != null && #id > 10")
    public User getUser(Long id) {
        if (id == null) {
            log.warn("尝试查询null用户ID");
            return null;
        }

        int count = dbAccessCount.incrementAndGet();
        log.info("正在从模拟数据库中获取用户，ID为：{}，数据库访问次数：{}", id, count);

        // 模拟一个小的延迟，以模仿数据库查询时间。
        simulateDbDelay(100);

        return DATABASE.get(id);
    }

    /**
     * 缓存穿透演示：模拟查询不存在的用户 - unless = "#result == null"：null 结果不写入缓存 - 启用 Bloom 后，未预热的 key 将被 Bloom
     * 拦截，避免回源
     */
    @RedisCacheable(
            ttl = 30,
            key = "'user_penetration:' + #id",
            value = "UserPenetration",
            type = User.class,
            unless = "#result == null")
    public User getUserForPenetrationTest(Long id) {
        if (id == null) {
            return null;
        }

        int count = dbAccessCount.incrementAndGet();
        log.info("缓存穿透测试 - 查询用户ID：{}，数据库访问次数：{}", id, count);

        // 模拟数据库查询延迟
        simulateDbDelay(50);

        // 模拟只有ID在1-100范围内的用户存在
        if (id >= 1 && id <= 100) {
            return DATABASE.get(id);
        }

        log.warn("用户不存在，ID：{}", id);
        return null; // 模拟数据库中不存在的记录
    }

    /**
     * 缓存击穿防护演示：热点数据的慢查询 - 模拟热点用户查询场景 - 启用 CacheBreakdown 防护（本地锁 + 分布式锁） - 在空缓存 +
     * 高并发同时访问时，集群内只允许一个请求回源，其他请求等待缓存结果
     */
    @RedisCacheable(ttl = 30, key = "'hot_user:' + #id", value = "HotUser", type = User.class, sync = true)
    public User getHotUser(Long id) {
        if (id == null) {
            return null;
        }

        int count = slowQueryCount.incrementAndGet();
        log.info("缓存击穿测试 - 执行热点用户慢查询，ID：{}，慢查询次数：{}", id, count);

        // 模拟数据库慢查询
        simulateDbDelay(2000); // 2秒延迟

        User user = DATABASE.get(id);
        if (user != null) {
            // 返回一个标记为热点用户的副本
            return new User(user.getId(), "HOT_" + user.getName(), user.getAge());
        }

        return null;
    }

    /** 解决方案1：通过ApplicationContext获取代理对象 */
    public Map<Long, User> getUsers(Long... ids) {
        log.info("开始批量查询用户（使用ApplicationContext解决方案）");

        // 获取当前bean的代理对象
        UserService proxyBean = applicationContext.getBean(UserService.class);

        Map<Long, User> result = new ConcurrentHashMap<>();
        for (Long id : ids) {
            // 解决：通过代理对象调用，缓存会生效
            User user = proxyBean.getUser(id);
            if (user != null) {
                result.put(id, user);
            }
        }

        return result;
    }

    /** 重置计数器（用于测试） */
    public void resetCounters() {
        dbAccessCount.set(0);
        slowQueryCount.set(0);
        log.info("计数器已重置");
    }

    /** 获取数据库访问计数 */
    public int getDbAccessCount() {
        return dbAccessCount.get();
    }

    /** 获取慢查询计数 */
    public int getSlowQueryCount() {
        return slowQueryCount.get();
    }

    /** 模拟数据库访问延迟 */
    private void simulateDbDelay(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("数据库查询被中断");
        }
    }

    /** 检查用户是否存在（不使用缓存，直接查数据库） */
    public boolean userExists(Long id) {
        return id != null && DATABASE.containsKey(id);
    }

    /** 获取数据库中的总用户数 */
    public int getTotalUsers() {
        return DATABASE.size();
    }
}
