package com.david.spring.cache.redis.core.writer.support.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 通过首先协调JVM内的线程，然后在可用时升级到分布式锁来处理同步执行。
 */
@Slf4j
@Component
public class SyncSupport {

	private final List<LockManager> distributedManagers;
	private final ConcurrentMap<String, Object> localMonitors = new ConcurrentHashMap<>();

    /**
     * 构造函数
     *
     * @param lockManagers 锁管理器列表
     */
	public SyncSupport(List<LockManager> lockManagers) {
        lockManagers.sort((o1, o2) -> o2.getOrder() - o1.getOrder());
		this.distributedManagers = List.copyOf(lockManagers);
    }

    /**
     * 执行同步操作
     *
     * @param key            缓存键
     * @param loader         数据加载器
     * @param timeoutSeconds 超时时间（秒）
     * @param <T>            返回值类型
     * @return 执行结果
     */
	public <T> T executeSync(String key, Supplier<T> loader, long timeoutSeconds) {
		Object monitor = localMonitors.computeIfAbsent(key, k -> new Object());
		synchronized (monitor) {
			if (distributedManagers.isEmpty()) {
                return loader.get();
            }

            try (LockStack lockStack = new LockStack()) {
				for (LockManager manager : distributedManagers) {
                    manager.tryAcquire(key, timeoutSeconds).ifPresentOrElse(lockStack::push, () -> {
                        log.warn("Lock manager {} failed to acquire distributed lock for key: {}", manager.getClass().getSimpleName(), key);
                        throw new RuntimeException("Failed to acquire distributed lock");
                    });
                }

                log.debug("Acquired distributed lock(s) for cache key: {} (count={})", key, lockStack.size());

				return loader.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.error("Interrupted while acquiring distributed lock for key: {}", key, e);
				return loader.get();
			} finally {
                localMonitors.remove(key, monitor);
            }
        }
    }

    /**
     * 锁堆栈类，用于管理多个锁的自动关闭
     */
    private static final class LockStack implements AutoCloseable {

        private final Deque<LockManager.LockHandle> handles = new ArrayDeque<>();

        /**
         * 将锁句柄压入堆栈
         *
         * @param handle 锁句柄
         */
        void push(LockManager.LockHandle handle) {
            handles.push(handle);
        }

        /**
         * 获取堆栈中锁的数量
         *
         * @return 锁数量
         */
        int size() {
            return handles.size();
        }

        /**
         * 关闭所有锁句柄
         */
        @Override
        public void close() {
            while (!handles.isEmpty()) {
                LockManager.LockHandle handle = handles.pop();
                try {
                    handle.close();
                } catch (Exception e) {
                    log.error("Failed to release distributed lock", e);
                }
			}
		}
	}
}
