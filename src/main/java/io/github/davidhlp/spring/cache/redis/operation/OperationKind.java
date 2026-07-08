package io.github.davidhlp.spring.cache.redis.operation;

import org.springframework.cache.interceptor.CacheOperation;

/**
 * 缓存操作种类枚举 —— ADR-0059 / Round 45 收敛 {@link RedisCacheRegister} 6 公开方法的
 * 类型化 key。
 *
 * <p><b>problem (背景)</b>:原 {@link RedisCacheRegister} 暴露 3 对 register/get 方法
 * (cacheable/put/evict),内部用 stringly-typed tag ({@code "CACHE"} / {@code "PUT"} /
 * {@code "EVICT"}) 区分命名空间。注释自述"为方法引用稳定保留"—— 但新增第 4 种操作类型
 * 须改 register 1 处 + 注解处理器 N 处,且 6 方法 API 难以统一维护。
 *
 * <p><b>solution</b>:本枚举把"操作种类 + tag 字符串 + 期望 operation 类型"绑定到 1 个
 * enum;{@link RedisCacheRegister} 暴露 1 对 register/get 方法(2 个而非 6 个),tag 派生
 * 自动避免漂移。注解处理器调用侧改 1 行 lambda 即可。
 *
 * <p><b>deletion test</b>:删本枚举 + 回滚到 6 方法 → register/get pair 数量回归,stringly-typed
 * tag 漂移风险重现。enum 挣得起存在代价。
 *
 * <p><b>新增第 4 种操作类型</b>:仅追加一行 enum 常量 + 一处 register/get 内部 switch,
 * 注解处理器侧同步新增 lambda。零 stringly-typed tag 漂移风险。
 */
public enum OperationKind {

    /** {@link RedisCacheableOperation} —— @RedisCacheable / @Cacheable 路径 */
    CACHEABLE("CACHE", RedisCacheableOperation.class),

    /** {@link RedisCachePutOperation} —— @RedisCachePut 路径 */
    CACHE_PUT("PUT", RedisCachePutOperation.class),

    /** {@link RedisCacheEvictOperation} —— @RedisCacheEvict 路径 */
    CACHE_EVICT("EVICT", RedisCacheEvictOperation.class);

    /** LRU key 的 tag 段 —— 与原 stringly-typed tag 字节级等价(向前兼容已部署的 register 数据) */
    private final String tag;

    /** 期望的 operation 类型 —— 用于 register 写入时类型校验,get 查询时 instance-of 安全转型 */
    private final Class<? extends CacheOperation> operationType;

    OperationKind(String tag, Class<? extends CacheOperation> operationType) {
        this.tag = tag;
        this.operationType = operationType;
    }

    /** @return LRU key 标签("CACHE" / "PUT" / "EVICT");与历史 stringly-typed tag 字节级等价 */
    public String tag() {
        return tag;
    }

    /** @return 此 kind 对应的 operation 类(用于 register 类型校验 + get 安全转型) */
    public Class<? extends CacheOperation> operationType() {
        return operationType;
    }
}
