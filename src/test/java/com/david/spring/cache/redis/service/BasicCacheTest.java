package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;
import jakarta.annotation.Resource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

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
public class BasicCacheTest {

	@Resource
	private BasicCacheTestService testService;
}