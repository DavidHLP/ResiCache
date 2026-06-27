package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.chain.model.*;


/**
 * Handler 执行顺序枚举
 *
 * 定义标准顺序，确保责任链按正确顺序执行。
 * 间隔 100，便于插入新的 Handler。
 */
public enum HandlerOrder {
    BLOOM_FILTER(100, "bloom-filter", "布隆过滤器-防穿透"),
    SYNC_LOCK(200, "sync-lock", "分布式锁-防击穿"),
    EARLY_EXPIRATION(250, "early-expiration", "提前过期-热key保护"),
    TTL(300, "ttl", "TTL计算"),
    NULL_VALUE(400, "null-value", "空值处理"),
    ACTUAL_CACHE(500, "actual-cache", "实际缓存操作");

    private final int order;
    /** 配置禁用名称(kebab-case),handler 禁用标识的单一事实源 */
    private final String disableName;
    private final String description;

    HandlerOrder(int order, String disableName, String description) {
        this.order = order;
        this.disableName = disableName;
        this.description = description;
    }

    public int getOrder() {
        return order;
    }

    /**
     * 配置禁用名称(kebab-case),作为 handler 禁用标识的单一事实源。
     *
     * <p>{@link io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChainFactory} 通过
     * {@code @HandlerPriority} 注解关联的 {@link HandlerOrder} 反查此名称,而非从类名派生——
     * 这样 handler 类重命名不会导致 {@code resi-cache.disabled-handlers} 配置或
     * {@code protection.enabled=false} 短路静默失效。
     */
    public String getDisableName() {
        return disableName;
    }

    public String getDescription() {
        return description;
    }
}
