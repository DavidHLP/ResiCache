package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

/**
 * Handler 执行顺序常量
 * 
 * 定义标准顺序，确保责任链按正确顺序执行。
 * 间隔 100，便于插入新的 Handler。
 */
public final class HandlerOrders {
    
    private HandlerOrders() {}
    
    /** 布隆过滤器 - 最先执行，防止缓存穿透 */
    public static final int BLOOM_FILTER = 100;
    
    /** 同步锁 - 保护后续操作，防止缓存击穿 */
    public static final int SYNC_LOCK = 200;
    
    /** TTL 计算 - 在实际存储前计算过期时间 */
    public static final int TTL = 300;
    
    /** 预刷新检查 - 检查是否需要提前刷新缓存 */
    public static final int PRE_REFRESH = 350;
    
    /** 空值处理 - 处理 null 值的缓存策略 */
    public static final int NULL_VALUE = 400;
    
    /** 实际缓存操作 - 最后执行实际的 Redis 操作 */
    public static final int ACTUAL_CACHE = 500;
}
