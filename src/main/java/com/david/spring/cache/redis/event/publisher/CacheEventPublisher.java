package com.david.spring.cache.redis.event.publisher;

import com.david.spring.cache.redis.event.CacheEvent;
import com.david.spring.cache.redis.event.CacheEventListener;
import com.david.spring.cache.redis.event.CacheEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存事件发布器
 * 负责管理监听器和发布事件
 */
@Slf4j
@Component
public class CacheEventPublisher implements InitializingBean, DisposableBean {

	private final List<CacheEventListener> listeners = new CopyOnWriteArrayList<>();
	private final AtomicLong publishedEvents = new AtomicLong(0);
	private final AtomicLong droppedEvents = new AtomicLong(0);
	private final AtomicLong processingErrors = new AtomicLong(0);
	// 可配置参数
	@Value("${cache.event.thread-pool.core-size:2}")
	private int corePoolSize;
	@Value("${cache.event.thread-pool.max-size:8}")
	private int maxPoolSize;
	@Value("${cache.event.thread-pool.queue-size:1000}")
	private int queueSize;
	@Value("${cache.event.thread-pool.keep-alive:60}")
	private long keepAliveSeconds;
	@Value("${cache.event.async.enabled:true}")
	private boolean asyncEnabled;
	@Value("${cache.event.batch.enabled:false}")
	private boolean batchEnabled;
	@Value("${cache.event.batch.size:100}")
	private int batchSize;
	@Value("${cache.event.batch.timeout:1000}")
	private long batchTimeoutMs;
	private ThreadPoolExecutor asyncExecutor;

	@Override
	public void afterPropertiesSet() {
		// 创建可配置的线程池
		this.asyncExecutor = new ThreadPoolExecutor(
				corePoolSize,
				maxPoolSize,
				keepAliveSeconds,
				TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(queueSize),
				r -> {
					Thread t = new Thread(r, "cache-event-" + System.currentTimeMillis());
					t.setDaemon(true);
					return t;
				},
				// 拒绝策略：丢弃并记录
				(r, executor) -> {
					droppedEvents.incrementAndGet();
					log.warn("Event dropped due to queue capacity limit. Dropped events: {}", droppedEvents.get());
				}
		);

		log.info("缓存事件发布器初始化完成: coreSize={}, maxSize={}, queueSize={}, async={}, batch={}",
				corePoolSize, maxPoolSize, queueSize, asyncEnabled, batchEnabled);
	}

	@Override
	public void destroy() {
		shutdown();
	}

	/**
	 * 注册事件监听器
	 *
	 * @param listener 监听器
	 */
	public void registerListener(CacheEventListener listener) {
		listeners.add(listener);
		listeners.sort((l1, l2) -> Integer.compare(l1.getOrder(), l2.getOrder()));
		log.info("Registered cache event listener: {}", listener.getClass().getSimpleName());
	}

	/**
	 * 移除事件监听器
	 *
	 * @param listener 监听器
	 */
	public void removeListener(CacheEventListener listener) {
		listeners.remove(listener);
		log.info("Removed cache event listener: {}", listener.getClass().getSimpleName());
	}

	/**
	 * 同步发布事件
	 *
	 * @param event 事件
	 */
	public void publishEvent(CacheEvent event) {
		if (listeners.isEmpty()) {
			return;
		}

		publishedEvents.incrementAndGet();

		for (CacheEventListener listener : listeners) {
			if (supportsEvent(listener, event)) {
				try {
					listener.onCacheEvent(event);
				} catch (Exception e) {
					processingErrors.incrementAndGet();
					log.warn("Error processing cache event by listener {}: {}",
							listener.getClass().getSimpleName(), e.getMessage());
				}
			}
		}
	}

	/**
	 * 异步发布事件
	 *
	 * @param event 事件
	 */
	public void publishEventAsync(CacheEvent event) {
		if (listeners.isEmpty()) {
			return;
		}

		if (!asyncEnabled) {
			// 如果异步被禁用，则同步处理
			publishEvent(event);
			return;
		}

		try {
			asyncExecutor.submit(() -> publishEvent(event));
		} catch (RejectedExecutionException e) {
			droppedEvents.incrementAndGet();
			log.warn("Failed to submit event for async processing, queue might be full. Event dropped: {}",
					event.getEventType());
		}
	}

	/**
	 * 判断监听器是否支持该事件
	 */
	private boolean supportsEvent(CacheEventListener listener, CacheEvent event) {
		CacheEventType[] supportedTypes = listener.getSupportedEventTypes();
		if (supportedTypes == null || supportedTypes.length == 0) {
			return true; // 支持所有事件类型
		}

		for (CacheEventType type : supportedTypes) {
			if (type == event.getEventType()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 获取已注册的监听器数量
	 */
	public int getListenerCount() {
		return listeners.size();
	}

	/**
	 * 获取发布的事件总数
	 */
	public long getPublishedEventCount() {
		return publishedEvents.get();
	}

	/**
	 * 获取丢弃的事件总数
	 */
	public long getDroppedEventCount() {
		return droppedEvents.get();
	}

	/**
	 * 获取处理错误总数
	 */
	public long getProcessingErrorCount() {
		return processingErrors.get();
	}

	/**
	 * 获取线程池状态
	 */
	public ThreadPoolStatus getThreadPoolStatus() {
		if (asyncExecutor == null) {
			return new ThreadPoolStatus(0, 0, 0, 0, 0, 0);
		}

		return new ThreadPoolStatus(
				asyncExecutor.getCorePoolSize(),
				asyncExecutor.getMaximumPoolSize(),
				asyncExecutor.getActiveCount(),
				asyncExecutor.getPoolSize(),
				asyncExecutor.getTaskCount(),
				asyncExecutor.getCompletedTaskCount()
		);
	}

	/**
	 * 重置统计信息
	 */
	public void resetStatistics() {
		publishedEvents.set(0);
		droppedEvents.set(0);
		processingErrors.set(0);
		log.info("Event publisher statistics reset");
	}

	/**
	 * 关闭事件发布器
	 */
	public void shutdown() {
		if (asyncExecutor != null) {
			asyncExecutor.shutdown();
			try {
				if (!asyncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
					log.warn("Event publisher did not terminate gracefully, forcing shutdown");
					asyncExecutor.shutdownNow();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				asyncExecutor.shutdownNow();
			}
		}
		log.info("Cache event publisher shutdown completed");
	}

	/**
	 * 线程池状态信息
	 */
		public record ThreadPoolStatus(int corePoolSize, int maxPoolSize, int activeCount, int currentPoolSize,
		                               long taskCount, long completedTaskCount) {


	}
}