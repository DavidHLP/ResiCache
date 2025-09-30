package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;

import jakarta.annotation.Resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = {SpringCacheRedis.class, RedisCacheAutoConfiguration.class})
@TestPropertySource(
        properties = {
            "spring.data.redis.host=192.168.1.111",
            "spring.data.redis.port=6379",
            "spring.data.redis.password=Alone117",
            "logging.level.com.david.spring.cache.redis=DEBUG",
        })
public class BasicCacheTest {

    @Resource private BasicCacheTestService testService;

    @Test
    @DisplayName("测试ttl自定义功能")
    public void test() {
        Long id = 1L;
        User user = testService.getUser(id);
        user = testService.getUser(id);
    }

    @Test
    @DisplayName("测试ttl的雪崩防护功能")
    public void test2() {
        Long id = 2L;
        User user = testService.getUser(id);
        user = testService.getUser(id);
    }
}
