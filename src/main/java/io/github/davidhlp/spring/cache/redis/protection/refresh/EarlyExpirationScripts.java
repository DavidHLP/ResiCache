package io.github.davidhlp.spring.cache.redis.protection.refresh;

/**
 * 提前过期 Lua 脚本常量 seam — ADR-0036 / Round 26 (C3).
 *
 * <p>把原硬编码在 {@link EarlyExpirationHandler} 的 {@code ATOMIC_TTL_SHORTEN_SCRIPT}
 * 字面量外置为 package-private 常量,使 TTL 缩短的 CAS 语义可被独立引用/断言,
 * handler 不再持脚本字面量 —— 与 {@code RedisProCacheTimers} / {@code MetadataKeys}
 * 同款 package-private 工具 seam 先例(ADR-0031 / ADR-0032).
 *
 * <p><b>守 ADR-0029</b>:本类是 package-private 常量持有,<strong>非新 interface/adapter</strong>.
 * Lua CAS 当前是单消费者(EarlyExpirationHandler.atomicShortenTtlIfValueUnchanged),
 * 不为它造 seam —— ADR-0029 明确接受此类工具收敛.
 */
final class EarlyExpirationScripts {

    private EarlyExpirationScripts() {
        // 常量工具类,不可实例化
    }

    /**
     * 原子缩短 TTL 的 Lua 脚本(CAS:value 未变才 expire).
     *
     * <p>语义:{@code GET key},若值等于 {@code ARGV[1]}(预期值)则 {@code EXPIRE} 为
     * {@code ARGV[2]} 秒并返回 1,否则返回 0(值已变,放弃缩短).保证「检查 value 未变 → 缩短 TTL」
     * 的原子性,避免异步刷新窗口内值被覆盖后仍缩短 TTL 的竞态.
     *
     * <p>参数:
     * <ul>
     *   <li>{@code KEYS[1]} — 目标 redis key</li>
     *   <li>{@code ARGV[1]} — 预期的序列化 value(CAS 比对基准)</li>
     *   <li>{@code ARGV[2]} — 缩短后的 TTL 秒数(刷新宽限期)</li>
     * </ul>
     */
    static final String ATOMIC_TTL_SHORTEN_SCRIPT =
        "local current = redis.call('get', KEYS[1]) " +
        "if current == ARGV[1] then " +
        "    redis.call('expire', KEYS[1], ARGV[2]) " +
        "    return 1 " +
        "else " +
        "    return 0 " +
        "end";
}
