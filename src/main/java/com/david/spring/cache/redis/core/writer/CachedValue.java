package com.david.spring.cache.redis.core.writer;

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
class CachedValue implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	private Object value;

	private Class<?> type;

	private long ttl;

	@Builder.Default
	private long createdTime = System.currentTimeMillis();

	@Builder.Default
	private long lastAccessTime = System.currentTimeMillis();

	@Builder.Default
	private long visitTimes = 0L;

	@Builder.Default
	private boolean expired = false;

	@Builder.Default
	private long version = 1L;

	public static CachedValue of(Object value, long ttl) {
		return CachedValue.builder()
				.value(value)
				.type(value != null ? value.getClass() : Object.class)
				.ttl(ttl)
				.build();
	}

	@JsonIgnore
	public boolean isExpired() {
		if (expired) {
			return true;
		}
		if (ttl <= 0) {
			return false;
		}
		return (System.currentTimeMillis() - createdTime) > (ttl * 1000);
	}

	@JsonIgnore
	public void updateAccess() {
		this.lastAccessTime = System.currentTimeMillis();
		this.visitTimes++;
	}

	@JsonIgnore
	public long getRemainingTtl() {
		if (ttl <= 0) {
			return -1;
		}
		long elapsed = (System.currentTimeMillis() - createdTime) / 1000;
		return Math.max(0, ttl - elapsed);
	}

	@JsonIgnore
	public long getAge() {
		return (System.currentTimeMillis() - createdTime) / 1000;
	}

	@JsonIgnore
	public void markExpired() {
		this.expired = true;
	}
}