package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.annotation.RedisCacheable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.io.Serializable;

@Slf4j
@Service
public class BasicCacheTestService {

    @RedisCacheable(value = "user", key = "#id", ttl = 300)
    public User getUser(Long id) {
        return User.builder().id(id).name("David").email("<EMAIL>").build();
    }

    @RedisCacheable(value = "user", key = "#id", ttl = 300, randomTtl = true, variance = 0.5F)
    public User getUserWithRandomTtl(Long id) {
        return User.builder().id(id).name("David").email("<EMAIL>").build();
    }

    @RedisCacheable(value = "user", key = "#id", ttl = 300, condition = "#id > 10")
    public User getUserWithCondition(Long id) {
        log.info("getUserWithCondition called with id: {}", id);
        return User.builder().id(id).name("Alice").email("<EMAIL>").build();
    }

    @RedisCacheable(value = "user", key = "#id", ttl = 300, unless = "#result.name == 'Anonymous'")
    public User getUserWithUnless(Long id) {
        log.info("getUserWithUnless called with id: {}", id);
        if (id == 999L) {
            return User.builder().id(id).name("Anonymous").email("").build();
        }
        return User.builder().id(id).name("Bob").email("<EMAIL>").build();
    }

    @RedisCacheable(
            value = "user",
            key = "#id",
            ttl = 300,
            condition = "#id > 0",
            unless = "#result == null")
    public User getUserWithConditionAndUnless(Long id) {
        log.info("getUserWithConditionAndUnless called with id: {}", id);
        if (id == 0L) {
            return null;
        }
        return User.builder().id(id).name("Charlie").email("<EMAIL>").build();
    }
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class User implements Serializable {
    private Long id;
    private String name;
    private String email;
}
