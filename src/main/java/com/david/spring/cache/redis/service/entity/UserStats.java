package com.david.spring.cache.redis.service.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 内部类：用户统计
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class UserStats {
	// Getters and Setters
	private Long departmentId;
	private String role;
	private int totalUsers;
	private int activeUsers;
}