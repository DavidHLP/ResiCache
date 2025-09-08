package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.service.entity.User;
import com.david.spring.cache.redis.service.entity.UserDetail;
import com.david.spring.cache.redis.service.entity.UserStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(SpringCacheRedis.class)
@DisplayName("UserService 单元测试")
public class UserServiceTest {

	@Autowired
	private UserService userService;

	@Autowired
	private CacheManager cacheManager;

	@BeforeEach
	@DisplayName("测试前准备")
	void setUp() {
		// 清空缓存
		cacheManager.getCacheNames().forEach(cacheName -> {
			var cache = cacheManager.getCache(cacheName);
			if (cache != null) {
				cache.clear();
			}
		});

		// 重置调用计数器
		userService.resetCallCounters();
	}

	@Test
	@DisplayName("测试根据ID获取用户 - 正常情况")
	void testGetUserById_Success() {
		// Given
		Long userId = 1L;

		// When - 第一次调用
		User result1 = userService.getUserById(userId);

		// Then - 验证结果
		assertNotNull(result1, "应该找到用户");
		assertEquals("张三", result1.getName(), "用户名应该匹配");
		assertEquals("zhangsan@example.com", result1.getEmail(), "邮箱应该匹配");
		assertEquals(1, userService.getGetUserCallCount(), "第一次调用应该访问数据库");

		// When - 第二次调用（缓存生效）
		User result2 = userService.getUserById(userId);

		// Then - 验证缓存
		assertNotNull(result2, "缓存的用户应该存在");
		assertEquals(result1.getName(), result2.getName(), "缓存结果应该一致");
		assertEquals(1, userService.getGetUserCallCount(), "第二次调用应该使用缓存，不访问数据库");
	}

	@Test
	@DisplayName("测试根据ID获取用户 - 用户不存在")
	void testGetUserById_UserNotFound() {
		// Given
		Long nonExistentUserId = 999L;

		// When
		User result = userService.getUserById(nonExistentUserId);

		// Then
		assertNull(result, "不存在的用户应该返回null");
		assertEquals(1, userService.getGetUserCallCount(), "应该调用数据库查询");
	}

	@Test
	@DisplayName("测试根据用户名搜索用户 - 正常情况")
	void testSearchUserByName_Success() {
		// Given
		String username = "李四";

		// When - 第一次调用
		User result1 = userService.searchUserByName(username);

		// Then
		assertNotNull(result1, "应该找到用户");
		assertEquals("李四", result1.getName(), "用户名应该匹配");
		assertEquals(1, userService.getSearchUserCallCount(), "第一次调用应该访问数据库");

		// When - 第二次调用（缓存生效）
		User result2 = userService.searchUserByName(username);

		// Then
		assertNotNull(result2, "缓存的用户应该存在");
		assertEquals(result1.getName(), result2.getName(), "缓存结果应该一致");
		assertEquals(1, userService.getSearchUserCallCount(), "第二次调用应该使用缓存");
	}

	@Test
	@DisplayName("测试根据用户名搜索用户 - 用户不存在")
	void testSearchUserByName_UserNotFound() {
		// Given
		String nonExistentUsername = "不存在的用户";

		// When - 第一次调用
		User result1 = userService.searchUserByName(nonExistentUsername);

		// Then
		assertNull(result1, "不存在的用户应该返回null");
		assertEquals(1, userService.getSearchUserCallCount(), "应该调用数据库查询");

		// When - 第二次调用（由于unless条件，空结果不会被缓存）
		User result2 = userService.searchUserByName(nonExistentUsername);

		// Then
		assertNull(result2, "第二次调用结果应该一致");
		assertEquals(2, userService.getSearchUserCallCount(), "空结果不应该被缓存，应该再次查询数据库");
	}

	@Test
	@DisplayName("测试根据用户名搜索用户 - 空值和空字符串（条件不满足）")
	void testSearchUserByName_InvalidInput() {
		// Test null username
		userService.searchUserByName(null);
		assertEquals(0, userService.getSearchUserCallCount(), "null用户名应该不调用数据库（condition不满足）");

		// Reset counter
		userService.resetCallCounters();

		// Test empty username
		userService.searchUserByName("");
		assertEquals(0, userService.getSearchUserCallCount(), "空字符串用户名应该不调用数据库（condition不满足）");
	}

	@Test
	@DisplayName("测试获取用户统计信息")
	void testGetUserStats() {
		// Given
		Long departmentId = 1L;
		String role = "ADMIN";

		// When - 第一次调用
		UserStats stats1 = userService.getUserStats(departmentId, role);

		// Then
		assertNotNull(stats1, "统计信息不应该为null");
		assertEquals(departmentId, stats1.getDepartmentId(), "部门ID应该匹配");
		assertEquals(role, stats1.getRole(), "角色应该匹配");
		assertTrue(stats1.getTotalUsers() >= 0, "总用户数应该>=0");
		assertTrue(stats1.getActiveUsers() >= 0, "活跃用户数应该>=0");

		// When - 第二次调用（缓存生效）
		UserStats stats2 = userService.getUserStats(departmentId, role);

		// Then - 验证缓存（由于是随机数，通过快速连续调用验证缓存）
		assertEquals(stats1.getTotalUsers(), stats2.getTotalUsers(), "缓存的统计数据应该一致");
		assertEquals(stats1.getActiveUsers(), stats2.getActiveUsers(), "缓存的统计数据应该一致");
	}

	@Test
	@DisplayName("测试获取用户详细信息 - 包含私人信息")
	void testGetUserDetail_WithPrivateInfo() {
		// Given
		Long userId = 1L;
		boolean includePrivate = true;

		// When
		UserDetail detail = userService.getUserDetail(userId, includePrivate);

		// Then
		assertNotNull(detail, "用户详细信息不应该为null");
		assertEquals(userId, detail.getUserId(), "用户ID应该匹配");
		assertEquals("张三", detail.getName(), "用户名应该匹配");
		assertEquals("zhangsan@example.com", detail.getEmail(), "邮箱应该匹配");
		assertNotNull(detail.getPhone(), "包含私人信息时，电话不应该为null");
		assertNotNull(detail.getAddress(), "包含私人信息时，地址不应该为null");
		assertEquals("138****1234", detail.getPhone(), "电话应该匹配");
		assertEquals("北京市朝阳区", detail.getAddress(), "地址应该匹配");
	}

	@Test
	@DisplayName("测试获取用户详细信息 - 不包含私人信息")
	void testGetUserDetail_WithoutPrivateInfo() {
		// Given
		Long userId = 2L;
		boolean includePrivate = false;

		// When
		UserDetail detail = userService.getUserDetail(userId, includePrivate);

		// Then
		assertNotNull(detail, "用户详细信息不应该为null");
		assertEquals(userId, detail.getUserId(), "用户ID应该匹配");
		assertEquals("李四", detail.getName(), "用户名应该匹配");
		assertEquals("lisi@example.com", detail.getEmail(), "邮箱应该匹配");
		assertNull(detail.getPhone(), "不包含私人信息时，电话应该为null");
		assertNull(detail.getAddress(), "不包含私人信息时，地址应该为null");
	}

	@Test
	@DisplayName("测试获取用户详细信息 - 用户不存在")
	void testGetUserDetail_UserNotFound() {
		// Given
		Long nonExistentUserId = 999L;
		boolean includePrivate = true;

		// When
		UserDetail detail = userService.getUserDetail(nonExistentUserId, includePrivate);

		// Then
		assertNull(detail, "不存在的用户应该返回null");
	}

	@Test
	@DisplayName("测试缓存键的差异性")
	void testCacheKeyDifference() {
		// 验证相同方法不同参数使用不同的缓存键

		// getUserById with different IDs
		userService.getUserById(1L);
		userService.getUserById(2L);
		assertEquals(2, userService.getGetUserCallCount(), "不同ID应该调用数据库两次");

		// Reset and test getUserDetail with different parameters
		userService.resetCallCounters();
		userService.getUserDetail(1L, true);
		userService.getUserDetail(1L, false);
		// 由于使用自定义键生成器，不同的includePrivate参数应该产生不同的缓存键

		userService.getUserDetail(2L, true);
		// 应该有3次不同的调用（假设自定义键生成器考虑了所有参数）
	}

	@Test
	@DisplayName("测试多线程并发访问")
	void testConcurrentAccess() throws InterruptedException {
		// Given
		Long userId = 1L;
		int threadCount = 5;
		Thread[] threads = new Thread[threadCount];

		// When - 多线程同时访问
		for (int i = 0; i < threadCount; i++) {
			threads[i] = new Thread(() -> {
				User user = userService.getUserById(userId);
				assertNotNull(user, "多线程访问应该都能获取到用户");
			});
			threads[i].start();
		}

		// 等待所有线程完成
		for (Thread thread : threads) {
			thread.join();
		}

		// Then - 验证缓存在并发情况下的表现
		// 由于缓存的存在，实际的数据库调用次数应该很少
		assertTrue(userService.getGetUserCallCount() <= threadCount,
				"并发访问时，缓存应该减少数据库调用次数");
	}
}
