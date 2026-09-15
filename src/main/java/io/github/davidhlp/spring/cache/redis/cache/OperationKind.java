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

    /**
     * 链侧操作 → 注册命名空间 —— {@code register} 查询的唯一映射点。
     *
     * <p>exhaustive {@code switch}(无 default)使新增 {@link CacheOperation} 时编译期失败,
     * 而不是运行期静默解析不出策略。
     *
     * <p>{@link CacheOperation#REMOVE} / {@link CacheOperation#CLEAN} 映射到
     * {@link #CACHE_EVICT}:驱逐元数据不含 chain 侧策略(无 TTL 写入、无 bloom 回填),
     * 查询结果按「无方法级策略」处理。
     *
     * @param operation 链侧操作类型({@code chain.CacheOperation},与本文件已 import 的
     *                  Spring {@code CacheOperation} 同名,故用全限定名)
     * @return 该操作应查询的注册命名空间
     */
    public static OperationKind forCacheOperation(
            io.github.davidhlp.spring.cache.redis.chain.CacheOperation operation) {
        return switch (operation) {
            case GET -> CACHEABLE;
            case PUT, PUT_IF_ABSENT -> CACHE_PUT;
            case REMOVE, CLEAN -> CACHE_EVICT;
        };
    }
}
