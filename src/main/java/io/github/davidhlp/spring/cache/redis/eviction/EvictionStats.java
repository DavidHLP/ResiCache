package io.github.davidhlp.spring.cache.redis.eviction;

import org.springframework.lang.NonNull;

/**
 * 淘汰策略统计信息。
 *
 * <p><strong>Candidate B：聚合工厂方法</strong>。原 {@code TwoListEvictionStrategy.getStats()}
 * 是 9 行聚合（{@code lru.size()} + {@code lru.getActiveSize()} + ... 凑足 6 个字段）；
 * 该聚合被抽出为本类的静态工厂方法 {@link #of(TwoListLRU, int, int)}，承载"算法与统计快照"
 * 的统一接口。原 105 SLOC 的 {@code TwoListEvictionStrategy} 包装层因此失去存在意义——删除。
 */
public record EvictionStats(
        int totalEntries,
        int activeEntries,
        int inactiveEntries,
        int maxActiveSize,
        int maxInactiveSize,
        long totalEvictions) {

    @NonNull
    public String toString() {
        return String.format(
                "EvictionStats{total=%d, active=%d/%d, inactive=%d/%d, evictions=%d}",
                totalEntries,
                activeEntries,
                maxActiveSize,
                inactiveEntries,
                maxInactiveSize,
                totalEvictions);
    }

    /**
     * 从 {@link TwoListLRU} 当前状态聚合一份统计快照。
     *
     * <p>{@code maxActiveSize} / {@code maxInactiveSize} 是配置而非 LRU 内部状态，
     * 故由调用方传入（保留策略层的容量配置）；其余四个字段直接读 {@link TwoListLRU}。
     *
     * @param lru 实际的 LRU 实例
     * @param maxActiveSize 配置的 Active List 最大容量
     * @param maxInactiveSize 配置的 Inactive List 最大容量
     */
    public static EvictionStats of(TwoListLRU<?, ?> lru, int maxActiveSize, int maxInactiveSize) {
        return new EvictionStats(
                lru.size(),
                lru.getActiveSize(),
                lru.getInactiveSize(),
                maxActiveSize,
                maxInactiveSize,
                lru.getTotalEvictions());
    }
}
