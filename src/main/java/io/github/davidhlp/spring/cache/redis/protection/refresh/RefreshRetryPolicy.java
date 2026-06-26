package io.github.davidhlp.spring.cache.redis.protection.refresh;

import lombok.extern.slf4j.Slf4j;

/**
 * 提前过期任务的重试策略：纯函数式同步重试循环，无状态、无并发，独立可单元测试。
 *
 * <p>从 {@code ThreadPoolEarlyExpirationExecutor} 抽出，将"最多 N 次、间隔 M ms 的同步重试"
 * 与线程池/去重/指标逻辑分离。任务在调用线程内同步执行（含重试间隔 sleep），
 * 由上层 {@code CompletableFuture.runAsync} 调度到线程池；失败耗尽重试后抛出
 * {@link RuntimeException}，由 {@code whenComplete} 统一记录。
 *
 * <p>提取收益（locality / 可测性）：原本埋在 360 行执行器内的重试循环，
 * 现作为纯函数可直接断言"首次成功 / 重试后成功 / 全失败抛异常 / 中断后继续重试"等路径，
 * 无需启动线程池。
 */
@Slf4j
public final class RefreshRetryPolicy {

    /** 提前过期任务最大重试次数（含首次执行） */
    public static final int MAX_RETRY_COUNT = 3;

    /** 重试间隔（毫秒） */
    public static final long RETRY_DELAY_MS = 1000;

    /**
     * 带重试执行任务：最多 {@value #MAX_RETRY_COUNT} 次，每次失败间隔 {@value #RETRY_DELAY_MS}ms。
     * 成功则返回；全部失败则抛出 {@link RuntimeException}。
     *
     * @param key  缓存键（用于日志追踪）
     * @param task 要执行的任务
     */
    public void executeWithRetry(String key, Runnable task) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_RETRY_COUNT) {
            attempt++;
            try {
                log.debug("Starting async early-expiration for key: {} (attempt {}/{})",
                        key, attempt, MAX_RETRY_COUNT);
                task.run();
                log.debug("Completed async early-expiration for key: {} (attempt {})", key, attempt);
                return; // 成功，退出
            } catch (Exception ex) {
                lastException = ex;
                log.warn("Async early-expiration failed for key: {} (attempt {}/{})",
                        key, attempt, MAX_RETRY_COUNT, ex);

                if (attempt < MAX_RETRY_COUNT) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Retry interrupted for key: {}, continuing with next attempt", key, ie);
                        continue; // 继续下一次重试而非退出循环
                    }
                }
            }
        }

        // 所有重试都失败
        if (lastException != null) {
            log.error("Async early-expiration failed after {} attempts for key: {}",
                    MAX_RETRY_COUNT, key, lastException);
            throw new RuntimeException(
                    "Pre-refresh failed after " + MAX_RETRY_COUNT + " attempts", lastException);
        }
    }
}
