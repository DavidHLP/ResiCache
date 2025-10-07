package com.david.spring.cache.redis.core.writer.support.refresh;

/**
 * 协调异步预刷新执行并跟踪正在进行的任务。
 */
public interface PreRefreshExecutor {

    /**
     * 提交一个预刷新任务到执行器
     *
     * @param key  任务关联的键
     * @param task 要执行的任务
     */
	void submit(String key, Runnable task);

    /**
     * 取消指定键的预刷新任务
     * @param key 要取消的任务关联的键
	 */
	void cancel(String key);

    /**
     * 获取执行器的统计信息
     * @return 包含执行器状态的字符串
	 */
    String getStats();

    /**
     * 获取当前活跃的任务数量
     * @return 正在执行的任务数量
	 */
	int getActiveCount();

    /**
     * 关闭执行器，停止接受新任务
	 */
	void shutdown();
}
