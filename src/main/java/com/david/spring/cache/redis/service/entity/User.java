package com.david.spring.cache.redis.service.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 内部类：用户实体
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class User {
	// Getters and Setters
	private Long id;
	private String name;
	private String email;
}