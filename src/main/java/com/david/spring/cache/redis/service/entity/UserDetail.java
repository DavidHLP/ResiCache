package com.david.spring.cache.redis.service.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 内部类：用户详细信息
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class UserDetail {
	// Getters and Setters
	private Long userId;
	private String name;
	private String email;
	private String phone;
	private String address;

}