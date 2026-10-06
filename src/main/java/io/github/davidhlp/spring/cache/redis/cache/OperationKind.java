package io.github.davidhlp.spring.cache.redis.cache;

import org.springframework.cache.interceptor.CacheOperation;

/** Active method-policy namespaces in the registration index. */
enum OperationKind {

    /** {@link RedisCacheableOperation} —— @RedisCacheable / @Cacheable 路径 */
    CACHEABLE("CACHE", RedisCacheableOperation.class),

    /** {@link RedisCachePutOperation} —— @RedisCachePut 路径 */
    CACHE_PUT("PUT", RedisCachePutOperation.class);

    /** Registry key tag */
    private final String tag;

    /** 期望的 operation 类型 —— 用于 register 写入时类型校验,get 查询时 instance-of 安全转型 */
    private final Class<? extends CacheOperation> operationType;

    OperationKind(String tag, Class<? extends CacheOperation> operationType) {
        this.tag = tag;
        this.operationType = operationType;
    }

    /** @return registry key tag labels ("CACHE" / "PUT") */
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
     * <p>{@link CacheOperation#REMOVE} / {@link CacheOperation#CLEAN} 返回 {@code null}:
     * 驱逐元数据不携带 chain 侧策略(无 TTL 写入、无 bloom 回填),查了也是必然未命中
     * —— 不做这次无谓的 register 查询。
     *
     * @param operation 链侧操作类型({@code chain.CacheOperation},与本文件已 import 的
     *                  Spring {@code CacheOperation} 同名,故用全限定名)
     * @return 该操作应查询的注册命名空间;{@code null} = 该操作无方法级策略可解析
     */
    @org.springframework.lang.Nullable
    public static OperationKind forCacheOperation(
            io.github.davidhlp.spring.cache.redis.chain.CacheOperation operation) {
        return switch (operation) {
            case GET -> CACHEABLE;
            case PUT, PUT_IF_ABSENT -> CACHE_PUT;
            case REMOVE, CLEAN -> null;
        };
    }
}
