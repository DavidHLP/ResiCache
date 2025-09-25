package com.david.spring.cache.redis.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CachedValue implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * 缓存的实际值
	 */
	private Object value;

	/**
	 * 值的类型信息
	 */
	private Class<?> type;

	/**
	 * TTL（存活时间），单位：秒
	 */
	private long ttl;

	/**
	 * 创建时间戳（毫秒）
	 */
	@Builder.Default
	private long createdTime = System.currentTimeMillis();

	/**
	 * 最后访问时间戳（毫秒）
	 */
	@Builder.Default
	private long lastAccessTime = System.currentTimeMillis();

	/**
	 * 访问次数
	 */
	@Builder.Default
	private long visitTimes = 0L;

	/**
	 * 是否已过期
	 */
	@Builder.Default
	private boolean expired = false;

	/**
	 * 版本号，用于乐观锁
	 */
	@Builder.Default
	private long version = 1L;

	/**
	 * 创建新的缓存值
	 */
	public static CachedValue of(Object value, long ttl) {
		return CachedValue.builder()
				.value(value)
				.type(value != null ? value.getClass() : Object.class)
				.ttl(ttl)
				.build();
	}

	/**
	 * 创建新的缓存值（带类型）
	 */
	public static CachedValue of(Object value, Class<?> type, long ttl) {
		return CachedValue.builder()
				.value(value)
				.type(type)
				.ttl(ttl)
				.build();
	}

	/**
	 * 创建空值缓存
	 */
	public static CachedValue ofNull(long ttl) {
		return CachedValue.builder()
				.value(null)
				.type(Object.class)
				.ttl(ttl)
				.build();
	}

	/**
	 * 检查缓存是否已过期
	 */
	@JsonIgnore
	public boolean isExpired() {
		if (expired) {
			return true;
		}
		if (ttl <= 0) {
			return false; // 永不过期
		}
		return (System.currentTimeMillis() - createdTime) > (ttl * 1000);
	}

	/**
	 * 更新访问时间和次数
	 */
	@JsonIgnore
	public void updateAccess() {
		this.lastAccessTime = System.currentTimeMillis();
		this.visitTimes++;
	}

	/**
	 * 获取剩余TTL（秒）
	 */
	@JsonIgnore
	public long getRemainingTtl() {
		if (ttl <= 0) {
			return -1; // 永不过期
		}
		long elapsed = (System.currentTimeMillis() - createdTime) / 1000;
		return Math.max(0, ttl - elapsed);
	}

	/**
	 * 获取缓存存在时间（秒）
	 */
	@JsonIgnore
	public long getAge() {
		return (System.currentTimeMillis() - createdTime) / 1000;
	}

	/**
	 * 标记为过期
	 */
	@JsonIgnore
	public void markExpired() {
		this.expired = true;
	}
}