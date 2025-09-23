package com.david.spring.cache.redis.chain;

/**
 * 缓存操作类型枚举。
 * <p>
 * 定义了缓存操作的各种状态，用于控制责任链中处理器的执行条件。
 * 不同的处理器可以根据操作类型决定是否需要执行。
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
public enum CacheOperationType {

	/**
	 * 缓存读取操作。
	 * <p>
	 * 用于 get() 方法调用时的缓存读取流程，包括：
	 * - 布隆过滤器检查
	 * - 预刷新检查
	 * - 简单读取等
	 * </p>
	 */
	READ("缓存读取"),

	/**
	 * 缓存写入操作。
	 * <p>
	 * 用于 put() 方法调用时的缓存写入流程，包括：
	 * - 数据包装
	 * - TTL设置
	 * - 防雪崩处理等
	 * </p>
	 */
	WRITE("缓存写入"),

	/**
	 * 缓存条件写入操作。
	 * <p>
	 * 用于 putIfAbsent() 方法调用时的条件写入流程。
	 * </p>
	 */
	PUT_IF_ABSENT("条件写入"),

	/**
	 * 缓存键删除操作。
	 * <p>
	 * 用于 evict() 方法调用时的单键删除流程，包括：
	 * - 立即删除
	 * - 注册表清理
	 * - 延迟双删等
	 * </p>
	 */
	EVICT("键删除"),

	/**
	 * 缓存全量清除操作。
	 * <p>
	 * 用于 clear() 方法调用时的全量清除流程，包括：
	 * - 全量删除
	 * - 注册表清理
	 * - 延迟双删等
	 * </p>
	 */
	CLEAR("全量清除"),

	/**
	 * 延迟双删操作。
	 * <p>
	 * 用于延迟双删定时任务执行时的删除流程，专门处理：
	 * - 延迟删除逻辑
	 * - 分布式锁控制
	 * - 最终一致性保证
	 * </p>
	 */
	DELAYED_DELETE("延迟双删"),

	/**
	 * 缓存刷新操作。
	 * <p>
	 * 用于预刷新或手动刷新时的缓存更新流程。
	 * </p>
	 */
	REFRESH("缓存刷新");

	private final String description;

	CacheOperationType(String description) {
		this.description = description;
	}

	/**
	 * 获取操作类型的中文描述。
	 *
	 * @return 操作描述
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * 判断是否为读取相关操作。
	 *
	 * @return true表示为读取相关操作
	 */
	public boolean isReadOperation() {
		return this == READ || this == REFRESH;
	}

	/**
	 * 判断是否为写入相关操作。
	 *
	 * @return true表示为写入相关操作
	 */
	public boolean isWriteOperation() {
		return this == WRITE || this == PUT_IF_ABSENT;
	}

	/**
	 * 判断是否为删除相关操作。
	 *
	 * @return true表示为删除相关操作
	 */
	public boolean isDeleteOperation() {
		return this == EVICT || this == CLEAR || this == DELAYED_DELETE;
	}

	/**
	 * 判断是否为延迟操作。
	 *
	 * @return true表示为延迟操作
	 */
	public boolean isDelayedOperation() {
		return this == DELAYED_DELETE;
	}

	@Override
	public String toString() {
		return String.format("%s(%s)", name(), description);
	}
}