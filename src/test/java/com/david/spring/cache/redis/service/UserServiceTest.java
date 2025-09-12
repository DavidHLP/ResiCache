package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.service.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserService测试类
 *
 * @author David
 */
@SpringBootTest(classes = SpringCacheRedis.class)
@TestPropertySource(properties = {"logging.level.com.david.spring.cache.redis=DEBUG"})
class UserServiceTest {

    @Autowired private UserService userService;

    @Test
    void testGetUser_WhenIdGreaterThan10_ShouldReturnUser() {
        // Given
        Long userId = 15L;

        // When
        User result = userService.getUser(userId);

        // Then
        assertNotNull(result);
        assertEquals(userId, result.getId());
        assertEquals("David", result.getName());
        assertEquals(11L, result.getAge());
    }

    @Test
    void testGetUser_WhenIdEquals10_ShouldReturnUser() {
        // Given
        Long userId = 10L;

        // When
        User result = userService.getUser(userId);

        // Then
        assertNotNull(result);
        assertEquals(userId, result.getId());
        assertEquals("David", result.getName());
        assertEquals(11L, result.getAge());
    }

    @Test
    void testGetUser_WhenIdLessThan10_ShouldReturnUser() {
        // Given
        Long userId = 5L;

        // When
        User result = userService.getUser(userId);

        // Then
        assertNotNull(result);
        assertEquals(userId, result.getId());
        assertEquals("David", result.getName());
        assertEquals(11L, result.getAge());
    }

    @Test
    void testGetUser_MultipleCalls_ShouldReturnConsistentResults() {
        // Given
        Long userId = 20L;

        // When
        User result1 = userService.getUser(userId);
        User result2 = userService.getUser(userId);

        // Then
        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1.getId(), result2.getId());
        assertEquals(result1.getName(), result2.getName());
        assertEquals(result1.getAge(), result2.getAge());
    }

    @Test
    void testGetUser_CacheCondition_WhenIdGreaterThan10() {
        // Given - 根据@RedisCacheable注解，只有当id > 10时才会缓存
        Long userId = 25L;

        // When
        User result = userService.getUser(userId);

        // Then
        assertNotNull(result);
        assertEquals(userId, result.getId());
        assertEquals("David", result.getName());
        assertEquals(11L, result.getAge());

        // 验证缓存条件：id > 10 时才缓存
        // 这里我们主要测试方法的功能性，缓存行为的详细测试需要集成测试环境
    }

    @Test
    void testGetUser_WithNullId_ShouldHandleGracefully() {
        // Given
        Long userId = null;

        // When & Then
        // 注意：根据当前实现，这会创建一个id为null的User对象
        User result = userService.getUser(userId);
        assertNotNull(result);
        assertNull(result.getId());
        assertEquals("David", result.getName());
        assertEquals(11L, result.getAge());
    }
}
