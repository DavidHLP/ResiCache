package com.david.spring.cache.redis.example;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class UserService {

	public static Map<Long, User> DATABASE_DATA = new ConcurrentHashMap<>();

	static {
		DATABASE_DATA.put(1L, new User(1L, "David", "david@gmail.com", "ACTIVE", 1L));
		DATABASE_DATA.put(2L, new User(2L, "Alice", "alice@example.com", "ACTIVE", 2L));
		DATABASE_DATA.put(3L, new User(3L, "Bob", "bob@example.com", "ACTIVE", 1L));
		DATABASE_DATA.put(4L, new User(4L, "Charlie", "charlie@example.com", "INACTIVE", 3L));
		DATABASE_DATA.put(5L, new User(5L, "Diana", "diana@example.com", "ACTIVE", 2L));
		DATABASE_DATA.put(6L, new User(6L, "Ethan", "ethan@example.com", "ACTIVE", 1L));
		DATABASE_DATA.put(7L, new User(7L, "Fiona", "fiona@example.com", "ACTIVE", 4L));
		DATABASE_DATA.put(8L, new User(8L, "George", "george@example.com", "ACTIVE", 3L));
		DATABASE_DATA.put(9L, new User(9L, "Hannah", "hannah@example.com", "ACTIVE", 2L));
		DATABASE_DATA.put(10L, new User(10L, "Ivy", "ivy@example.com", "INACTIVE", 1L));
		DATABASE_DATA.put(11L, new User(11L, "Jack", "jack@example.com", "ACTIVE", 4L));
		DATABASE_DATA.put(12L, new User(12L, "Karen", "karen@example.com", "ACTIVE", 3L));
		DATABASE_DATA.put(13L, new User(13L, "Leo", "leo@example.com", "ACTIVE", 2L));
		DATABASE_DATA.put(14L, new User(14L, "Mia", "mia@example.com", "ACTIVE", 1L));
	}

	@RedisCaching(cacheable = {
			@RedisCacheable(value = "user1", key = "#id", ttl = 3600),
			@RedisCacheable(value = "user2", key = "#id", ttl = 3600),
	})
	public User get(Long id) {
		log.info("Fetching user with id: {}", id);
		User user = DATABASE_DATA.get(id);
		if (user != null) {
			log.debug("User found: {} ({})", user.getName(), user.getEmail());
		} else {
			log.warn("User not found with id: {}", id);
		}
		return user;
	}

	@RedisCaching(evict = {
			@RedisCacheEvict(value = "user1", key = "#id"),
			@RedisCacheEvict(value = "user2", key = "#id"),
	})
	public void evict(Long id) {
		log.info("Evicting user with id: {}", id);
		User removedUser = DATABASE_DATA.remove(id);
		if (removedUser != null) {
			log.debug("Successfully evicted user: {} ({})", removedUser.getName(), removedUser.getEmail());
		} else {
			log.warn("Attempted to evict non-existent user with id: {}", id);
		}
	}

	@Data
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	public static class User {
		private Long id;
		private String name;
		private String email;
		private String status;
		private Long departmentId;
	}
}