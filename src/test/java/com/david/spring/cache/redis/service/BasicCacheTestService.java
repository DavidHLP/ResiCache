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
        log.info("getUser: {}", id);
        return User.builder().id(id).name("David").email("<EMAIL>").build();
    }

    @RedisCacheable(value = "user", key = "#id", ttl = 300, randomTtl = true, variance = 0.5F)
    public User getUserWithoutAnnotation(Long id) {
        log.info("getUser: {}", id);
        return User.builder().id(id).name("David").email("<EMAIL>").build();
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
