package com.david.spring.cache.redis.meta;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 缓存元数据包装器
 * 提供缓存值的额外元信息，如TTL、访问次数、创建时间等
 *
 * @author David
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CacheMata {
	/**
	 * 缓存生存时间（秒）
	 */
	private long ttl;

	/**
	 * 实际缓存值
	 */
	private Object value;

	/**
	 * 值的类型信息
	 */
	private Class<?> type;

	/**
	 * 访问次数统计
	 */
	private Long visitTimes;

	/**
	 * 缓存创建时间
	 */
	@Builder.Default
	private LocalDateTime createTime = LocalDateTime.now();

	/**
	 * 最后访问时间
	 */
	private LocalDateTime lastAccessTime;

	/**
	 * 缓存版本号，用于支持乐观锁更新
	 */
	@Builder.Default
	private Long version = 1L;

	/**
	 * 创建缓存元数据的工厂方法
	 *
	 * @param value 缓存值
	 * @param ttl   生存时间
	 * @return 缓存元数据
	 */
	public static CacheMata of(Object value, long ttl) {
		return CacheMata.builder()
				.value(value)
				.ttl(ttl)
				.type(value != null ? value.getClass() : Object.class)
				.visitTimes(0L)
				.build();
	}

	/**
	 * 创建简单缓存元数据的工厂方法
	 *
	 * @param value 缓存值
	 * @return 缓存元数据
	 */
	public static CacheMata of(Object value) {
		return of(value, -1); // -1 表示永不过期
	}

	/**
	 * 增加访问次数并更新最后访问时间
	 */
	public void incrementVisitTimes() {
		this.visitTimes = (this.visitTimes == null ? 0 : this.visitTimes) + 1;
		this.lastAccessTime = LocalDateTime.now();
	}

	/**
	 * 检查缓存是否已过期
	 *
	 * @return true 如果已过期
	 */
	@JsonIgnore
	public boolean isExpired() {
		if (ttl <= 0) {
			return false; // 永不过期
		}
		return createTime.plusSeconds(ttl).isBefore(LocalDateTime.now());
	}

	/**
	 * 更新版本号
	 */
	public void incrementVersion() {
		this.version = (this.version == null ? 0 : this.version) + 1;
	}
}
