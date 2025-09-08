package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.service.entity.User;
import com.david.spring.cache.redis.service.entity.UserDetail;
import com.david.spring.cache.redis.service.entity.UserStats;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户服务类，演示 RedisCacheable 注解的使用
 */
@Service
public class UserService {

	// 模拟数据库数据
	private final Map<Long, User> userDatabase = new HashMap<>();

	// 获取调用计数器，用于测试
	// 模拟调用计数器，用于测试缓存是否生效
	@Getter
	private int getUserCallCount = 0;
	@Getter
	private int searchUserCallCount = 0;

	public UserService() {
		// 初始化测试数据
		userDatabase.put(1L, new User(1L, "张三", "zhangsan@example.com"));
		userDatabase.put(2L, new User(2L, "李四", "lisi@example.com"));
		userDatabase.put(3L, new User(3L, "王五", "wangwu@example.com"));
	}

	/**
	 * 根据用户ID获取用户信息，使用默认缓存配置
	 *
	 * @param userId 用户ID
	 * @return 用户信息
	 */
	@RedisCacheable(value = "users", key = "#userId", type = User.class)
	public User getUserById(Long userId) {
		getUserCallCount++;
		System.out.println("从数据库查询用户: " + userId + "，调用次数: " + getUserCallCount);
		return userDatabase.get(userId);
	}

	/**
	 * 根据用户名搜索用户，使用自定义TTL和条件
	 *
	 * @param username 用户名
	 * @return 用户信息
	 */
	@RedisCacheable(type = User.class, value = "user_search", key = "#username", ttl = 300L, condition = "#username != null && #username.length() > 0", unless = "#result == null")
	public User searchUserByName(String username) {
		searchUserCallCount++;
		System.out.println("从数据库搜索用户: " + username + "，调用次数: " + searchUserCallCount);

		return userDatabase.values().stream()
				.filter(user -> user.getName().equals(username))
				.findFirst()
				.orElse(null);
	}

	/**
	 * 获取用户统计信息，使用SpEL表达式生成复合键
	 *
	 * @param departmentId 部门ID
	 * @param role         角色
	 * @return 统计数据
	 */
	@RedisCacheable(type = UserStats.class, value = "user_stats", key = "'dept:' + #departmentId + ':role:' + #role", ttl = 600L, sync = true)
	public UserStats getUserStats(Long departmentId, String role) {
		System.out.println("计算用户统计: 部门=" + departmentId + ", 角色=" + role);

		// 模拟复杂的统计计算
		try {
			Thread.sleep(100); // 模拟耗时操作
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		return new UserStats(departmentId, role,
				(int) (Math.random() * 100), (int) (Math.random() * 50));
	}

	/**
	 * 获取用户详细信息，使用自定义键生成器
	 *
	 * @param userId         用户ID
	 * @param includePrivate 是否包含私人信息
	 * @return 用户详细信息
	 */
	@RedisCacheable(value = "user_details", ttl = 120L, type = UserDetail.class)
	public UserDetail getUserDetail(Long userId, boolean includePrivate) {
		System.out.println("获取用户详细信息: " + userId + ", 包含私人信息: " + includePrivate);

		User user = userDatabase.get(userId);
		if (user == null) {
			return null;
		}

		UserDetail detail = new UserDetail();
		detail.setUserId(user.getId());
		detail.setName(user.getName());
		detail.setEmail(user.getEmail());

		if (includePrivate) {
			detail.setPhone("138****1234");
			detail.setAddress("北京市朝阳区");
		}

		return detail;
	}

	public void resetCallCounters() {
		getUserCallCount = 0;
		searchUserCallCount = 0;
	}
}
