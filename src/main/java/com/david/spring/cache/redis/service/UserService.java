package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.service.entity.User;

import org.springframework.stereotype.Service;

@Service
public class UserService {
    @RedisCacheable(
            ttl = 60,
            key = "'user:' + #id",
            value = "User",
            type = User.class,
            condition = "#id > 10")
    public User getUser(Long id) {
        return new User(id, "David", 11L);
    }
}
