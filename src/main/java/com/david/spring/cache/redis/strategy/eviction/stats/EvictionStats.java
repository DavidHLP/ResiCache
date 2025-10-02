package com.david.spring.cache.redis.strategy.eviction.stats;

import org.springframework.lang.NonNull;

/** 淘汰策略统计信息 */
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
}
