package io.github.davidhlp.spring.cache.redis.cache;


/**
 * 缓存键派生的单一权威(deep module)。
 *
 * <p>本类把"从 (cacheName, redisKey) 派生各键形态"集中一处,调用方只问形态:
 * {@link #actualKey()} / {@link #redisKey()} / {@link #bloomKey()}。集中收口杜绝
 * <b>bloom 键漂移</b>:链层 PUT 以 actualKey(剥前缀)写入过滤器,而 loader 路径若用
 * {@code createCacheKey}(带前缀)查询 —— 查的 key 永不在过滤器里(sync + bloom 组合
 * 静默返回 null)。两个 bloom 消费者(链层 {@code BloomFilterHandler} 与 loader 路径
 * {@code RedisProCache})同源派生,结构上杜绝漂移。
 *
 * <p><b>删除测试</b>:删掉本类 → actualKey/bloomKey 必须在 Writer + RedisProCache 两处
 * 重新各自推导,复杂度重现且漂移风险回归 → 本 seam 挣得起存在代价。
 *
 * <p><b>不变量</b>:bloom 键 ≡ {@link #actualKey()}。线程安全:不可变 record。
 */
record CacheKeys(String cacheName, String actualKey, String redisKey) {

    /** cacheName 与 actualKey 之间的分隔符(Redis key 格式 {@code {cacheName}::{actualKey}})。 */
    private static final String SEPARATOR = "::";

    /**
     * 从已带前缀的完整 Redis key 反推键形态。
     *
     * <p>剥 {@code {cacheName}::} 前缀得 actualKey;若 redisKey 不以该前缀开头
     * (自定义 keyPrefix 等场景),原样作为 actualKey。
     *
     * @param cacheName 缓存名称
     * @param redisKey  完整 Redis key(如经 Spring {@code createCacheKey} 产出)
     * @return 键形态(cacheName / actualKey / redisKey)
     */
    public static CacheKeys fromRedisKey(String cacheName, String redisKey) {
        String prefix = cacheName + SEPARATOR;
        String actual = redisKey.startsWith(prefix) ? redisKey.substring(prefix.length()) : redisKey;
        return new CacheKeys(cacheName, actual, redisKey);
    }

    /**
     * bloom 键形态 ≡ {@link #actualKey()};单一真理源。
     *
     * <p>链层写入({@code BloomFilterHandler.add})与查询、loader 路径查询均经此,杜绝
     * {@code createCacheKey}(带前缀)与 {@code actualKey}(剥前缀)之间的漂移。
     *
     * @return bloom 用的键(≡ actualKey)
     */
    public String bloomKey() {
        return actualKey;
    }
}
