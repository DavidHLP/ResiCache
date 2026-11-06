package io.github.davidhlp.spring.cache.redis.protection.bloom.filter;

/**
 * 用于保护缺失键的缓存布隆过滤器抽象。
 */
public interface BloomIFilter {

    /**
     * 将键添加到指定缓存的布隆过滤器中。
     */
    void add(String cacheName, String key);

    /**
     * 检查布隆过滤器是否可能包含该键。
     */
    boolean mightContain(String cacheName, String key);

    /**
     * Whether negative results are authoritative for the complete data-source membership.
     *
     * <p>Defaults to false: cache-write observations alone cannot establish completeness.
     * Implementations returning true must maintain membership across all writers and instances,
     * and return false during initialization, rebuilds, data loss, or uncertain writes.
     * A false result permits ordinary Redis/data-source lookup instead of rejecting the key.
     *
     * @param cacheName cache whose membership is being checked
     * @return true only while negative membership decisions are safe
     */
    default boolean isMembershipComplete(String cacheName) {
        return false;
    }

    /** 清空指定缓存的布隆过滤器。 */
    void clear(String cacheName);
}
