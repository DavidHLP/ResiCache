package com.david.spring.cache.redis.event;

/**
 * 缓存事件监听器接口
 * 使用观察者模式来处理缓存事件
 */
public interface CacheEventListener {

    /**
     * 处理缓存事件
     *
     * @param event 缓存事件
     */
    void onCacheEvent(CacheEvent event);

    /**
     * 获取监听器支持的事件类型
     *
     * @return 事件类型数组
     */
    CacheEventType[] getSupportedEventTypes();

    /**
     * 获取监听器的优先级，数值越小优先级越高
     *
     * @return 优先级
     */
    default int getOrder() {
        return 0;
    }
}