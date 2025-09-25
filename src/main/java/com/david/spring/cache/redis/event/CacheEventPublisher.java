package com.david.spring.cache.redis.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 缓存事件发布器
 * 负责管理监听器和发布事件
 */
@Slf4j
@Component
public class CacheEventPublisher {

    private final List<CacheEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4,
            r -> {
                Thread t = new Thread(r, "cache-event-" + System.currentTimeMillis());
                t.setDaemon(true);
                return t;
            });

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

        for (CacheEventListener listener : listeners) {
            if (supportsEvent(listener, event)) {
                try {
                    listener.onCacheEvent(event);
                } catch (Exception e) {
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

        asyncExecutor.submit(() -> publishEvent(event));
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
     * 关闭事件发布器
     */
    public void shutdown() {
        asyncExecutor.shutdown();
        log.info("Cache event publisher shutdown completed");
    }
}