package io.github.davidhlp.spring.cache.redis.cache;




import org.springframework.cache.interceptor.CacheOperation;

/**
 * 缓存操作种类枚举 —— {@link RedisCacheRegister} 的类型化 key。
 *
 * <p>本枚举把"操作种类 + tag 字符串 + 期望 operation 类型"绑定到 1 个 enum;
 * {@link RedisCacheRegister} 暴露 1 对 register/get 方法,tag 派生自动避免漂移。
 * 注解处理器调用侧用 1 行 lambda 即可路由。
 *
 * <p><b>deletion test</b>:删本枚举 → tag 字符串散落到调用方字面量,漂移风险重现。
 * enum 挣得起存在代价。
 *
 * <p><b>新增第 4 种操作类型</b>:仅追加一行 enum 常量 + 一处 register/get 内部 switch,
 * 注解处理器侧同步新增 lambda。零 tag 漂移风险。
 */
enum OperationKind {

    /** {@link RedisCacheableOperation} —— @RedisCacheable / @Cacheable 路径 */
    CACHEABLE("CACHE", RedisCacheableOperation.class),

    /** {@link RedisCachePutOperation} —— @RedisCachePut 路径 */
    CACHE_PUT("PUT", RedisCachePutOperation.class),

    /** {@link RedisCacheEvictOperation} —— @RedisCacheEvict 路径 */
    CACHE_EVICT("EVICT", RedisCacheEvictOperation.class);

    /** LRU key 的 tag 段 */
    private final String tag;

    /** 期望的 operation 类型 —— 用于 register 写入时类型校验,get 查询时 instance-of 安全转型 */
    private final Class<? extends CacheOperation> operationType;

    OperationKind(String tag, Class<? extends CacheOperation> operationType) {
        this.tag = tag;
        this.operationType = operationType;
    }

    /** @return LRU key 标签("CACHE" / "PUT" / "EVICT") */
    public String tag() {
        return tag;
    }

    /** @return 此 kind 对应的 operation 类(用于 register 类型校验 + get 安全转型) */
    public Class<? extends CacheOperation> operationType() {
        return operationType;
    }
}
