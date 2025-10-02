package com.david.spring.cache.redis.strategy.eviction.stats;

/**
 * 锁池统计信息
 *
 * @param totalSize 总锁数量
 * @param activeSize 活跃锁数量
 * @param inactiveSize 不活跃锁数量
 * @param maxActiveSize 最大活跃锁数量
 * @param maxInactiveSize 最大不活跃锁数量
 * @param totalAcquires 总获取次数
 * @param totalReleases 总释放次数
 * @param cacheHits 缓存命中次数
 * @param cacheMisses 缓存未命中次数
 * @param totalEvictions 总淘汰次数
 */
public record LockPoolStats(
        int totalSize,
        int activeSize,
        int inactiveSize,
        int maxActiveSize,
        int maxInactiveSize,
        long totalAcquires,
        long totalReleases,
        long cacheHits,
        long cacheMisses,
        long totalEvictions) {

    /**
     * 计算缓存命中率
     *
     * @return 命中率 (0.0 - 1.0)
     */
    public double hitRate() {
        long total = cacheHits + cacheMisses;
        if (total == 0) {
            return 0.0;
        }
        return (double) cacheHits / total;
    }

    /**
     * 计算活跃锁使用率
     *
     * @return 使用率 (0.0 - 1.0)
     */
    public double activeUtilization() {
        if (maxActiveSize == 0) {
            return 0.0;
        }
        return (double) activeSize / maxActiveSize;
    }

    /**
     * 计算不活跃锁使用率
     *
     * @return 使用率 (0.0 - 1.0)
     */
    public double inactiveUtilization() {
        if (maxInactiveSize == 0) {
            return 0.0;
        }
        return (double) inactiveSize / maxInactiveSize;
    }

    /**
     * 计算总使用率
     *
     * @return 使用率 (0.0 - 1.0)
     */
    public double totalUtilization() {
        int maxTotal = maxActiveSize + maxInactiveSize;
        if (maxTotal == 0) {
            return 0.0;
        }
        return (double) totalSize / maxTotal;
    }

    /**
     * 估算内存占用（KB）
     *
     * @return 内存占用估算值
     */
    public long estimatedMemoryKB() {
        // 每个 ReentrantLock 约 48 字节
        return (long) totalSize * 48 / 1024;
    }

    @Override
    public String toString() {
        return String.format(
                "LockPoolStats{total=%d, active=%d/%d(%.1f%%), inactive=%d/%d(%.1f%%), "
                        + "acquires=%d, releases=%d, hits=%d, misses=%d, hitRate=%.2f%%, "
                        + "evictions=%d, memoryKB=%d}",
                totalSize,
                activeSize,
                maxActiveSize,
                activeUtilization() * 100,
                inactiveSize,
                maxInactiveSize,
                inactiveUtilization() * 100,
                totalAcquires,
                totalReleases,
                cacheHits,
                cacheMisses,
                hitRate() * 100,
                totalEvictions,
                estimatedMemoryKB());
    }
}
