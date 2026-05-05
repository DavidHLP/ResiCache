package io.github.davidhlp.spring.cache.redis.core.writer.support.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 通过首先协调JVM内的线程，然后在可用时升级到分布式锁来处理同步执行。
 * 
 * 使用引用计数的 Monitor 来避免过早移除导致的同步问题。
 */
@Slf4j
@Component
public class SyncSupport {

	private final List<LockManager> distributedManagers;
	private final ConcurrentMap<String, MonitorHolder> localMonitors = new ConcurrentHashMap<>();

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
		MonitorHolder holder = acquireMonitor(key);
		synchronized (holder.monitor) {
			try {
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
					throw new IllegalStateException("Thread interrupted while acquiring distributed lock for key: " + key, e);
				}
			} finally {
				releaseMonitor(key, holder);
			}
		}
    }

	/**
	 * 获取 Monitor，增加引用计数
	 */
	private MonitorHolder acquireMonitor(String key) {
		MonitorHolder holder = localMonitors.computeIfAbsent(key, k -> new MonitorHolder());
		holder.refCount.incrementAndGet();
		return holder;
	}

	/**
	 * 释放 Monitor，减少引用计数，当计数为0时移除。
	 * 使用 compute 原子性地完成检查和删除，避免 TOCTOU 竞态条件。
	 */
	private void releaseMonitor(String key, MonitorHolder holder) {
		localMonitors.compute(key, (k, existingHolder) -> {
			if (existingHolder != holder) {
				// 键已被其他 MonitorHolder 替换，保持现有引用
				return existingHolder;
			}
			if (holder.refCount.decrementAndGet() <= 0) {
				// 引用计数归零，移除该条目
				return null;
			}
			// 仍有引用，保持该条目
			return holder;
		});
	}

	/**
	 * 持有 Monitor 对象和引用计数
	 */
	private static final class MonitorHolder {
		final Object monitor = new Object();
		final AtomicInteger refCount = new AtomicInteger(0);
	}

    /**
     * 锁堆栈类，用于管理多个锁的自动关闭
     */
    private static final class LockStack implements AutoCloseable {

        private final Deque<LockManager.LockHandle> handles = new ConcurrentLinkedDeque<>();

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
