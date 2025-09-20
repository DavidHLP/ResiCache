package com.david.spring.cache.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
public class SpringCacheRedis {
    public static void main(String[] args) {
        SpringApplication.run(SpringCacheRedis.class, args);
    }
}
