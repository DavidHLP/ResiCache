package io.github.davidhlp.spring.cache.redis.core.writer.support.refresh;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 协调预刷新评估和异步执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreRefreshSupport {

    private final PreRefreshExecutor executor;

    /**
     * 提交异步预刷新任务
     *
     * @param key         刷新任务关联的键
     * @param refreshTask 要执行的刷新任务
     */
    public void submitAsyncRefresh(String key, Runnable refreshTask) {
        if (key == null || refreshTask == null) {
            log.warn("Skipping async pre-refresh submission due to missing key or task");
            return;
        }
        executor.submit(key, refreshTask);
    }

    /**
     * 取消指定键的异步预刷新任务
     *
     * @param key 需要取消刷新任务的键
     */
    public void cancelAsyncRefresh(String key) {
        if (key == null) {
            return;
        }
        executor.cancel(key);
    }

    /**
     * 获取线程池统计信息
     *
     * @return 线程池统计信息字符串
     */
    public String getThreadPoolStats() {
        return executor.getStats();
    }

    /**
     * 获取正在进行刷新的键数量
     *
     * @return 正在刷新的键数量
     */
    public int getRefreshingKeyCount() {
        return executor.getActiveCount();
    }

    /**
     * 关闭预刷新支持服务，在Bean销毁时自动调用
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
