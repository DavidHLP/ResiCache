package io.github.davidhlp.spring.cache.redis.chain;

/**
 * 缓存操作类型枚举。
 *
 * <p><b>操作子集类型化</b>:本枚举是「操作 → 哪些 handler 关心这个操作」契约的
 * 单一真理源。本枚举提供 3 个语义谓词集中表达该映射,避免散落在各 handler 中
 * 的多操作判定({@code op == PUT || op == PUT_IF_ABSENT} 等)漂移:
 * <ul>
 *   <li>{@link #isWrite()} — 写路径(PUT / PUT_IF_ABSENT),TtlHandler / NullValueHandler 用</li>
 *   <li>{@link #requiresSyncLock()} — sync-lock 关心的操作(GET / PUT / PUT_IF_ABSENT),SyncLockHandler 用</li>
 *   <li>{@link #requiresBloomPostProcess()} — bloom 后置回填关心的操作(PUT / PUT_IF_ABSENT / CLEAN),BloomFilterHandler 用</li>
 * </ul>
 *
 * <p><b>为什么枚举自身承担谓词</b>:操作枚举本身成为谓词源,新增操作时只在本枚举
 * 改一处,测试自动 pin。
 */
public enum CacheOperation {
    /** 获取缓存 */
    GET,
    /** 写入缓存 */
    PUT,
    /** 条件写入缓存（不存在时才写入） */
    PUT_IF_ABSENT,
    /** 删除缓存 */
    REMOVE,
    /** 清空缓存 */
    CLEAN;

    /**
     * 是否为写路径操作 — TtlHandler(写时计算 TTL)与 NullValueHandler(写时转换 null 存储)
     * 关心的子集。{@link #REMOVE} / {@link #CLEAN} 不参与:前者无需 TTL,后者走独立路径。
     *
     * @return {@code true} 当本操作是 PUT 或 PUT_IF_ABSENT
     */
    public boolean isWrite() {
        return this == PUT || this == PUT_IF_ABSENT;
    }

    /**
     * 是否需要分布式同步锁 — SyncLockHandler 关心的子集。{@code GET} 在 sync=true 时
     * 走 loader 路径(防 loader 击穿);{@code PUT} / {@code PUT_IF_ABSENT} 走写路径
     * (防双写竞争)。{@link #REMOVE} / {@link #CLEAN} 不参与:删除本身无击穿风险。
     *
     * @return {@code true} 当本操作是 GET / PUT / PUT_IF_ABSENT
     */
    public boolean requiresSyncLock() {
        return this == GET || this == PUT || this == PUT_IF_ABSENT;
    }

    /**
     * 是否需要 bloom 过滤器后置回填 — BloomFilterHandler 关心的子集。PUT / PUT_IF_ABSENT
     * 写入时回填 key;CLEAN 清空时清空整 bloom(布隆不支持精确删除)。{@code GET} /
     * {@code REMOVE} 不参与:GET 在 doHandle 主路径已用 bloom 短路,REMOVE 不在 bloom
     * 维护范围内(布隆只防穿透不防击穿/雪崩,失效 bloom 不会导致错误结果,只是失去防护)。
     *
     * @return {@code true} 当本操作是 PUT / PUT_IF_ABSENT / CLEAN
     */
    public boolean requiresBloomPostProcess() {
        return this == PUT || this == PUT_IF_ABSENT || this == CLEAN;
    }
}
