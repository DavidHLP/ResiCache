package io.github.davidhlp.spring.cache.redis.cache;


/**
 * 提前过期 Lua 脚本常量 seam。
 *
 * <p>TTL 缩短的 CAS 语义以 package-private 常量形式外置,使
 * {@link EarlyExpirationHandler} 不持脚本字面量,可被独立引用/断言 ——
 * 与 {@code RedisProCacheTimers} / {@code MetadataKeys} 同款 package-private
 * 工具 seam 纪律。
 *
 * <p>本类是 package-private 常量持有,<strong>非 interface/adapter</strong>:
 * Lua CAS 是单消费者(EarlyExpirationHandler.atomicShortenTtlIfValueUnchanged),
 * 不为它造 seam。
 */
final class EarlyExpirationScripts {

    private EarlyExpirationScripts() {
        // 常量工具类,不可实例化
    }

    /**
     * 原子缩短 TTL 的 Lua 脚本(版本 CAS).
     *
     * <p>语义:{@code GET key},用 cjson 解析出 value 里的 {@code version} 字段,若
     * 等于 {@code ARGV[1]}(预期版本)则 {@code EXPIRE} 为 {@code ARGV[2]} 秒并返回 1,
     * 否则返回 0(值已变,放弃缩短).保证「检查 version 未变 → 缩短 TTL」的原子性,
     * 避免异步刷新窗口内值被覆盖后仍缩短 TTL 的竞态.
     *
     * <p>版本 CAS 只传 8 字节(long → string),不把整个 CachedValue 序列化值
     * (可能 N×10KB)送进 Lua 脚本;网络字节数为 O(1) —— 独立于 payload 大小。
     *
     * <p>cjson 解析失败防御:解析抛出时返回 0(放弃缩短),与"value 不匹配"
     * 行为一致(竞态 → 不缩短 → 安全)。Redis 5+ 内置 cjson,旧版本(无 cjson)的实例
     * 启动期由本脚本抛 Lua 错误 → execute 异常 → handler catch 吞 → 不缩短,行为
     * 与"value 不匹配"等价。注:ResiCache 3.x 文档要求 Redis 5+。
     *
     * <p>参数:
     * <ul>
     *   <li>{@code KEYS[1]} — 目标 redis key</li>
     *   <li>{@code ARGV[1]} — 预期的 version(ASCII 数字字符串,来自 CachedValue.version)</li>
     *   <li>{@code ARGV[2]} — 缩短后的 TTL 秒数(刷新宽限期)</li>
     * </ul>
     */
    static final String ATOMIC_TTL_SHORTEN_SCRIPT =
        "local current = redis.call('get', KEYS[1]) " +
        "if current then " +
        "    local ok, parsed = pcall(cjson.decode, current) " +
        "    if ok and parsed and parsed.version == nil then " +
        "        parsed = cjson.decode('{\"version\":0}') " +
        "    end " +
        "    if ok and parsed and tostring(parsed.version) == ARGV[1] then " +
        "        redis.call('expire', KEYS[1], ARGV[2]) " +
        "        return 1 " +
        "    end " +
        "end " +
        "return 0";
}
