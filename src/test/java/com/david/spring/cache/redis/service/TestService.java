package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Getter
@Slf4j
@Service
public class TestService {

    private int getUserCallCount = 0;
    private int updateUserCallCount = 0;

    @RedisCacheable(
            cacheNames = "users",
            key = "#userId",
            ttl = 300,
            condition = "#userId > 0"
    )
    public User getUser(Long userId) {
        getUserCallCount++;
        log.info("Getting user {} from database (call count: {})", userId, getUserCallCount);

        if (userId <= 0) {
            return null;
        }

        return User.builder()
                .id(userId)
                .name("User " + userId)
                .email("user" + userId + "@example.com")
                .build();
    }

    @RedisCacheEvict(
            cacheNames = "users",
            key = "#user.id",
            condition = "#user != null"
    )
    public User updateUser(User user) {
        updateUserCallCount++;
        log.info("Updating user {} in database (call count: {})", user.getId(), updateUserCallCount);

        user.setName(user.getName() + " (Updated)");
        return user;
    }

    @RedisCaching(
            cacheable = {
                    @RedisCacheable(cacheNames = "user-details", key = "#userId", ttl = 600),
                    @RedisCacheable(cacheNames = "user-profile", key = "#userId", ttl = 300)
            }
    )
    public User getUserWithProfile(Long userId) {
        log.info("Getting user with profile {} from database", userId);

        User user = User.builder()
                .id(userId)
                .name("User " + userId)
                .email("user" + userId + "@example.com")
                .build();

        return user;
    }

    @RedisCaching(
            cacheEvict = {
                    @RedisCacheEvict(cacheNames = "user-details", key = "#userId"),
                    @RedisCacheEvict(cacheNames = "user-profile", key = "#userId")
            }
    )
    public void deleteUser(Long userId) {
        log.info("Deleting user {} from database", userId);
    }

    @RedisCacheEvict(cacheNames = "users", allEntries = true)
    public void clearAllUsers() {
        log.info("Clearing all users from database");
    }

    @RedisCacheable(
            cacheNames = "random-ttl-cache",
            key = "#key",
            ttl = 60,
            randomTtl = true,
            variance = 0.3f
    )
    public String getValueWithRandomTtl(String key) {
        log.info("Getting value with random TTL for key: {}", key);
        return "Value for " + key;
    }

    @RedisCacheable(
            cacheNames = "sync-cache",
            key = "#key",
            sync = true,
            internalLock = true
    )
    public String getSyncValue(String key) {
        log.info("Getting sync value for key: {}", key);
        try {
            Thread.sleep(100); // Simulate slow operation
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "Sync value for " + key;
    }

    @RedisCacheable(
            cacheNames = "null-cache",
            key = "#key",
            cacheNullValues = true
    )
    public String getNullableValue(String key) {
        log.info("Getting nullable value for key: {}", key);
        return "null".equals(key) ? null : "Value for " + key;
    }

	public void resetCallCounts() {
        getUserCallCount = 0;
        updateUserCallCount = 0;
    }
}