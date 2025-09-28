package com.david.spring.cache.redis.register;

import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;

@SpringBootTest(
		classes = {
				SpringCacheRedis.class,
				RedisCacheAutoConfiguration.class
		}
)
@TestPropertySource(properties = {
		"spring.data.redis.host=192.168.1.111",
		"spring.data.redis.port=6379",
		"spring.data.redis.password=Alone117",
		"logging.level.com.david.spring.cache.redis=DEBUG",
})
public class registerTest {
	@Resource
	private registerTestService registerTestService;

	@Resource
	private RedisCacheRegister redisCacheRegister;

	@Test
	@DisplayName("测试缓存注解")
	public void test() {
		registerTestService.getUserWithProfile(1L);

		Assert.notNull(redisCacheRegister.getCacheableOperation("user-details", "1"), "用户详情缓存未命中");
		Assert.notNull(redisCacheRegister.getCacheableOperation("user-profile", "1"), "用户档案缓存未命中");
	}
}
