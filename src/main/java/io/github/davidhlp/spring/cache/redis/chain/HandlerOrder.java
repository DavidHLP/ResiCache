package io.github.davidhlp.spring.cache.redis.chain;



/**
 * Handler 执行顺序枚举
 *
 * 定义标准顺序，确保责任链按正确顺序执行。
 * 间隔 100，便于插入新的 Handler。
 *
 * <p>本枚举同时是每个 slot 的身份事实源：顺序值、配置禁用名(kebab-case)、观测标签。
 */
public enum HandlerOrder {
    BLOOM_FILTER(100, "bloom-filter", "布隆过滤器-防穿透", "BloomFilterHandler"),
    SYNC_LOCK(200, "sync-lock", "分布式锁-防击穿", "SyncLockHandler"),
    EARLY_EXPIRATION(250, "early-expiration", "提前过期-热key保护", "EarlyExpirationHandler"),
    TTL(300, "ttl", "TTL计算", "TtlHandler"),
    NULL_VALUE(400, "null-value", "空值处理", "NullValueHandler"),
    ACTUAL_CACHE(500, "actual-cache", "实际缓存操作", "ActualCacheHandler");

    private final int order;
    /** 配置禁用名称(kebab-case),handler 禁用标识的单一事实源 */
    private final String disableName;
    private final String description;
    /** 观测标签,链日志与 Micrometer {@code handler} tag 取值的单一事实源 */
    private final String handlerTag;

    HandlerOrder(int order, String disableName, String description, String handlerTag) {
        this.order = order;
        this.disableName = disableName;
        this.description = description;
        this.handlerTag = handlerTag;
    }

    public int getOrder() {
        return order;
    }

    /**
     * 配置禁用名称(kebab-case),作为 handler 禁用标识的单一事实源。
     *
     * <p>链装配经内部 {@code cache/HandlerIdentity} 从 {@code @HandlerPriority} 注解关联的
     * {@link HandlerOrder} 反查此名称,而非从类名派生——
     * 这样 handler 类重命名不会导致 {@code resi-cache.disabled-handlers} 配置或
     * {@code protection.enabled=false} 短路静默失效。
     */
    public String getDisableName() {
        return disableName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 观测标签(handler 简名),链日志与 Micrometer {@code handler} tag 的单一事实源。
     *
     * <p>取值与历史一致,但由本枚举声明而非 {@code Class#getSimpleName()} 派生:重命名 handler
     * 类不再静默改变已上线的 metric tag 维度,改动此值才是改变(并由
     * {@code HandlerIdentityContractTest} 钉住)。
     */
    public String getHandlerTag() {
        return handlerTag;
    }
}
