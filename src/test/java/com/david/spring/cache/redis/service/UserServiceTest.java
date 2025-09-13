package com.david.spring.cache.redis.service;

import static org.junit.jupiter.api.Assertions.*;

import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.service.entity.User;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * UserService全面测试类 - 重点测试缓存击穿和缓存穿透特性
 *
 * @author David
 */
@SpringBootTest(classes = SpringCacheRedis.class)
@TestPropertySource(
        properties = {
            "logging.level.com.david.spring.cache.redis=DEBUG",
            "logging.level.org.springframework.cache=DEBUG"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserServiceTest {

    @Autowired private UserService userService;

    @BeforeEach
    void setUp() {
        // 每个测试前重置计数器
        userService.resetCounters();
    }

    // ==================== 基础功能测试 ====================

    @Test
    @Order(1)
    @DisplayName("基础缓存功能测试 - ID大于10时启用缓存")
    void testBasicCaching() {
        // Given
        Long userId = 15L;

        // When - 第一次调用
        User result1 = userService.getUser(userId);
        int accessCount1 = userService.getDbAccessCount();

        // 第二次调用（应该从缓存获取）
        User result2 = userService.getUser(userId);
        int accessCount2 = userService.getDbAccessCount();

        // Then
        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1.getId(), result2.getId());
        assertEquals(result1.getName(), result2.getName());

        // 验证缓存生效：第二次调用不应该增加数据库访问次数
        assertEquals(1, accessCount1, "第一次调用应该访问数据库");
        assertEquals(1, accessCount2, "第二次调用应该从缓存获取，不访问数据库");
    }

    @Test
    @Order(2)
    @DisplayName("缓存条件测试 - ID小于等于10时不使用缓存")
    void testCacheCondition() {
        // Given
        Long smallId = 5L;

        // When - 多次调用小ID
        userService.getUser(smallId);
        userService.getUser(smallId);
        userService.getUser(smallId);

        // Then - 每次都应该访问数据库（因为不满足缓存条件）
        assertEquals(3, userService.getDbAccessCount(), "ID<=10时不应该启用缓存，每次调用都访问数据库");
    }

    // ==================== 缓存穿透测试 ====================

    @Test
    @Order(3)
    @DisplayName("缓存穿透测试 - 未启用布隆过滤器")
    void testCachePenetrationWithoutBloom() {
        // Given - 不存在的用户ID
        Long nonExistentId = 999L;

        // When - 多次查询不存在的用户
        for (int i = 0; i < 5; i++) {
            User result = userService.getUserForPenetrationTest(nonExistentId);
            assertNull(result, "不存在的用户应该返回null");
        }

        // Then - 每次都会访问数据库（缓存穿透）
        assertEquals(5, userService.getDbAccessCount(), "未启用布隆过滤器时，每次查询不存在的数据都会访问数据库");
    }

    @Test
    @Order(4)
    @DisplayName("缓存穿透测试 - 不使用手动预热（基线对比）")
    void testCachePenetrationBaselineNoPreheat() {
        // Given - 不存在的用户ID
        Long nonExistentId = 999L;

        // When - 多次查询不存在的用户
        for (int i = 0; i < 5; i++) {
            User result = userService.getUserForPenetrationTest(nonExistentId);
            assertNull(result, "不存在的用户应该返回null");
        }

        // Then - 每次都会访问数据库（无预热无布隆时的基线行为）
        assertEquals(5, userService.getDbAccessCount(), "未启用/预热布隆过滤器时，每次查询不存在的数据都会访问数据库");
    }

    @Test
    @Order(5)
    @DisplayName("缓存穿透测试 - 存在的数据应正常返回（无手动预热）")
    void testExistingUserWithoutPreheat() {
        // When - 查询存在的用户
        Long existingId = 50L;
        User result = userService.getUserForPenetrationTest(existingId);

        // Then - 存在的用户应该正常返回
        assertNotNull(result, "存在的用户应该正常返回");
        assertEquals(existingId, result.getId());
        assertEquals(1, userService.getDbAccessCount(), "存在的用户查询应该正常访问数据库");
    }

    // ==================== 缓存击穿测试 ====================

    @Test
    @Order(6)
    @DisplayName("缓存击穿测试 - 单线程情况")
    void testCacheBreakdownSingleThread() throws InterruptedException {
        // Given
        Long hotUserId = 1L;

        // When - 连续查询热点数据
        long startTime = System.currentTimeMillis();
        User result1 = userService.getHotUser(hotUserId);
        long firstQueryTime = System.currentTimeMillis() - startTime;

        startTime = System.currentTimeMillis();
        User result2 = userService.getHotUser(hotUserId);
        long secondQueryTime = System.currentTimeMillis() - startTime;

        // Then
        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1.getId(), result2.getId());

        // 第一次查询应该较慢（访问数据库）
        assertTrue(
                firstQueryTime > 1500, String.format("第一次查询应该较慢(>1.5s)，实际：%dms", firstQueryTime));

        // 第二次查询应该很快（从缓存获取）——阈值略放宽以避免环境抖动造成偶发失败
        assertTrue(
                secondQueryTime < 200, String.format("第二次查询应该很快(<200ms)，实际：%dms", secondQueryTime));

        assertEquals(1, userService.getSlowQueryCount(), "应该只执行一次慢查询");
    }

    @Test
    @Order(7)
    @DisplayName("缓存击穿测试 - 并发情况下的防护")
    void testCacheBreakdownConcurrent() throws InterruptedException {
        // Given
        Long hotUserId = 2L;
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<CompletableFuture<User>> futures = new ArrayList<>();

        // When - 并发查询同一个热点数据
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<User> future =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    startLatch.await(); // 等待统一开始信号
                                    return userService.getHotUser(hotUserId);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return null;
                                } finally {
                                    endLatch.countDown();
                                }
                            },
                            executor);
            futures.add(future);
        }

        // 统一开始
        long startTime = System.currentTimeMillis();
        startLatch.countDown();

        // 等待所有线程完成
        boolean awaitResult = endLatch.await(10, TimeUnit.SECONDS);
        if (!awaitResult) {
            fail("测试超时，未能在规定时间内完成所有并发请求");
        }
        long totalTime = System.currentTimeMillis() - startTime;

        // Then
        List<User> results =
                futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList();

        assertEquals(threadCount, results.size(), "所有线程都应该获得结果");

        // 验证所有结果一致
        User firstResult = results.get(0);
        results.forEach(
                result -> {
                    assertEquals(firstResult.getId(), result.getId());
                    assertEquals(firstResult.getName(), result.getName());
                });

        // 验证缓存击穿防护效果：慢查询次数应该远少于并发线程数
        int slowQueryCount = userService.getSlowQueryCount();
        assertTrue(
                slowQueryCount <= 2, String.format("缓存击穿防护应该限制慢查询次数，期望<=2，实际：%d", slowQueryCount));

        // 总耗时应该接近单次慢查询时间（约2秒），而不是累加
        assertTrue(totalTime < 4000, String.format("并发查询总耗时应该<4秒，实际：%dms", totalTime));

        executor.shutdown();
    }

    // ==================== 性能对比测试 ====================

    @Test
    @Order(8)
    @DisplayName("性能对比测试 - 有缓存 vs 无缓存")
    void testPerformanceComparison() throws InterruptedException {
        // Given
        Long[] testIds = {11L, 12L, 13L, 14L, 15L};
        int iterations = 100;

        // When - 测试无缓存性能（使用小ID，不启用缓存）
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            for (Long id : testIds) {
                userService.getUser(id - 10); // 使用<=10的ID，不启用缓存
            }
        }
        long noCacheTime = System.currentTimeMillis() - startTime;

        userService.resetCounters();

        // 测试有缓存性能
        startTime = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            for (Long id : testIds) {
                userService.getUser(id); // 使用>10的ID，启用缓存
            }
        }
        long withCacheTime = System.currentTimeMillis() - startTime;

        // Then
        System.out.printf(
                "性能对比 - 无缓存：%dms，有缓存：%dms，提升：%.2fx%n",
                noCacheTime, withCacheTime, (double) noCacheTime / withCacheTime);

        assertTrue(withCacheTime < noCacheTime, "启用缓存后的查询时间应该显著减少");

        // 验证缓存确实生效了
        int finalDbAccessCount = userService.getDbAccessCount();
        assertEquals(testIds.length, finalDbAccessCount, "启用缓存后，每个ID应该只访问数据库一次");
    }

    // ==================== 边界条件测试 ====================

    @Test
    @Order(9)
    @DisplayName("边界条件测试")
    void testEdgeCases() {
        // Test null ID
        User nullResult = userService.getUser(null);
        assertNull(nullResult, "null ID应该返回null");

        // Test boundary values
        User result10 = userService.getUser(10L);
        assertNotNull(result10, "ID=10的用户应该存在");

        User result11 = userService.getUser(11L);
        assertNotNull(result11, "ID=11的用户应该存在");

        // Test non-existent ID
        User nonExistent = userService.getUserForPenetrationTest(999L);
        assertNull(nonExistent, "不存在的用户应该返回null");
    }

    @Test
    @Order(10)
    @DisplayName("批量操作测试")
    void testBatchOperations() {
        // Given
        Long[] userIds = {11L, 12L, 13L, 999L}; // 包含存在和不存在的ID

        // When
        Map<Long, User> results = userService.getUsers(userIds);

        // Then
        assertEquals(3, results.size(), "应该返回3个存在的用户");
        assertTrue(results.containsKey(11L));
        assertTrue(results.containsKey(12L));
        assertTrue(results.containsKey(13L));
        assertFalse(results.containsKey(999L));
    }

    // ==================== 辅助方法测试 ====================

    @Test
    @Order(11)
    @DisplayName("辅助方法测试")
    void testUtilityMethods() {
        // Test user existence check
        assertTrue(userService.userExists(1L), "ID=1的用户应该存在");
        assertFalse(userService.userExists(999L), "ID=999的用户不应该存在");
        assertFalse(userService.userExists(null), "null ID应该返回false");

        // Test total users count
        int totalUsers = userService.getTotalUsers();
        assertTrue(totalUsers > 0, "应该有用户数据");
        assertEquals(100, totalUsers, "应该有100个用户");
    }

    // ==================== 压力测试 ====================

    @Test
    @Order(12)
    @DisplayName("压力测试 - 大量并发请求")
    void testHighConcurrency() throws InterruptedException {
        // Given
        int threadCount = 50;
        int requestsPerThread = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // When
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            startLatch.await();
                            for (int j = 0; j < requestsPerThread; j++) {
                                long userId = ((threadIndex * requestsPerThread + j) % 100 + 1);
                                if (userId > 10) {
                                    userService.getUser(userId);
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            endLatch.countDown();
                        }
                    });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        long totalTime = System.currentTimeMillis() - startTime;

        // Then
        assertTrue(completed, "所有线程应该在30秒内完成");
        System.out.printf(
                "压力测试完成 - %d线程 x %d请求，总耗时：%dms%n", threadCount, requestsPerThread, totalTime);

        executor.shutdown();
    }
}
