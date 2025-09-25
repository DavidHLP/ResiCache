package com.david.spring.cache.redis;

import com.david.spring.cache.redis.core.CachedValue;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试CachedValue的数据流
 */
public class CachedValueFlowTest {

	@Test
	void testCachedValueDataFlow() {
		// 1. 创建原始数据
		String originalData = "Hello, World!";
		long ttl = 300; // 5分钟

		// 2. 使用 CachedValue.of() 创建缓存值对象
		CachedValue cachedValue = CachedValue.of(originalData, ttl);

		// 3. 验证实际值被正确存储在 value 字段中
		assertEquals(originalData, cachedValue.getValue());
		assertEquals(String.class, cachedValue.getType());
		assertEquals(ttl, cachedValue.getTtl());

		System.out.println("原始数据: " + originalData);
		System.out.println("CachedValue.value: " + cachedValue.getValue());
		System.out.println("数据类型: " + cachedValue.getType());
		System.out.println("TTL: " + cachedValue.getTtl() + " 秒");

		// 4. 验证数据完整性
		assertSame(originalData, cachedValue.getValue());
	}

	@Test
	void testCachedValueWithComplexObject() {
		// 测试复杂对象
		TestUser user = new TestUser("张三", 25);
		long ttl = 600;

		CachedValue cachedValue = CachedValue.of(user, ttl);

		// 验证复杂对象被正确存储
		assertEquals(user, cachedValue.getValue());
		assertEquals(TestUser.class, cachedValue.getType());

		TestUser retrievedUser = (TestUser) cachedValue.getValue();
		assertEquals("张三", retrievedUser.name());
		assertEquals(25, retrievedUser.age());

		System.out.println("原始用户: " + user);
		System.out.println("缓存中的用户: " + retrievedUser);
		System.out.println("用户类型: " + cachedValue.getType());
	}

	@Test
	void testCachedValueWithNullValue() {
		// 测试空值
		long ttl = 60;

		CachedValue cachedValue = CachedValue.ofNull(ttl);

		// 验证空值被正确处理
		assertNull(cachedValue.getValue());
		assertEquals(Object.class, cachedValue.getType());
		assertEquals(ttl, cachedValue.getTtl());

		System.out.println("空值缓存: " + cachedValue.getValue());
		System.out.println("空值类型: " + cachedValue.getType());
	}

	// 测试用的简单类
	private record TestUser(String name, int age) {

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null || getClass() != obj.getClass()) return false;
			TestUser user = (TestUser) obj;
			return age == user.age && name.equals(user.name);
		}

		@Override
		@NotNull
		public String toString() {
			return "TestUser{name='" + name + "', age=" + age + "}";
		}
	}
}