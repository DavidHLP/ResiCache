package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

/**
 * Handler 执行顺序枚举
 *
 * 定义标准顺序，确保责任链按正确顺序执行。
 * 间隔 100，便于插入新的 Handler。
 */
public enum HandlerOrder {
    BLOOM_FILTER(100, "布隆过滤器-防穿透"),
    SYNC_LOCK(200, "分布式锁-防击穿"),
    PRE_REFRESH(250, "预刷新-热key保护"),
    TTL(300, "TTL计算"),
    NULL_VALUE(400, "空值处理"),
    ACTUAL_CACHE(500, "实际缓存操作");

    private final int order;
    private final String description;

    HandlerOrder(int order, String description) {
        this.order = order;
        this.description = description;
    }

    public int getOrder() {
        return order;
    }

    public String getDescription() {
        return description;
    }
}
