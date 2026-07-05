package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 责任链 protection 机制禁用集合 resolver — ADR-0047 / C5 收敛.
 *
 * <p>从 {@link CacheHandlerChainFactory#createChain()} 抽取的 4 段逻辑:
 * <ol>
 *   <li>{@code protection.enabled=false} → 全 4 个防护 handler 短路</li>
 *   <li>per-mechanism 字段(非 null)→ 单独覆盖该机制</li>
 *   <li>从 {@link HandlerOrder} 枚举派生 {@code disableName}</li>
 *   <li>收集到 {@code disabled} 集合供后续 filter 使用</li>
 * </ol>
 *
 * <p><b>平行列表消除(C5 核心交付)</b>:原 {@link CacheHandlerChainFactory}
 * 同时持有两份「4 个 protection 机制」列表:
 * <ul>
 *   <li>{@code PROTECTION_HANDLER_ORDERS}(4 个 {@code HandlerOrder} 枚举,用于
 *       {@code protection.enabled=false} 短路)</li>
 *   <li>{@code PROTECTION_TOGGLES}(4 个 {@link Toggle} record,含 getter + configPath,
 *       用于 per-mechanism 覆盖)</li>
 * </ul>
 * 两份列表的 cardinality 必须保持一致(都是 4),且第 i 项描述同一机制 —
 * 改一处忘改另一处即静默失效。本类把两份列表合成一个 {@link #TOGGLES}
 * 单一 source of truth,新增机制只追加一行。
 *
 * <p><b>不可实例化</b>:纯静态工具,与 {@link CacheHandlerChainFactory} 协作。
 *
 * @see CacheHandlerChainFactory
 * @see HandlerOrder
 */
@Slf4j
final class ChainProtectionToggleResolver {

    /**
     * 单条 protection 机制 toggle 描述 — order 字段既是短路枚举又是 disableName 派生源,
     * getter 字段是 per-mechanism 覆盖读取,configPath 字段仅用于日志。
     *
     * @param order      防护机制对应的 {@link HandlerOrder}
     * @param getter     从 {@link RedisProCacheProperties.ProtectionProperties} 读取
     *                   Boolean 字段(null = 继承 enabled,非 null = 单独覆盖)
     * @param configPath 配置文件中的 kebab-case 路径段(用于日志)
     */
    record Toggle(
            HandlerOrder order,
            Function<RedisProCacheProperties.ProtectionProperties, Boolean> getter,
            String configPath) {
    }

    /**
     * 4 个可禁用防护机制 toggle 单一事实源 — 新增第 5 个机制时仅追加一行。
     *
     * <p>注意:不含 TTL — {@code TtlHandler} 兼担基础 TTL 计算,禁用会导致
     * {@code ActualCacheHandler} 写入无 TTL 永久缓存(数据陈旧 + 内存泄漏)。
     */
    private static final List<Toggle> TOGGLES = List.of(
            new Toggle(HandlerOrder.BLOOM_FILTER,
                    RedisProCacheProperties.ProtectionProperties::getBloomFilterEnabled,
                    "bloom-filter"),
            new Toggle(HandlerOrder.SYNC_LOCK,
                    RedisProCacheProperties.ProtectionProperties::getSyncLockEnabled,
                    "sync-lock"),
            new Toggle(HandlerOrder.EARLY_EXPIRATION,
                    RedisProCacheProperties.ProtectionProperties::getEarlyExpirationEnabled,
                    "early-expiration"),
            new Toggle(HandlerOrder.NULL_VALUE,
                    RedisProCacheProperties.ProtectionProperties::getNullValueEnabled,
                    "null-value"));

    private ChainProtectionToggleResolver() {
        // 工具类,不可实例化
    }

    /**
     * 解析禁用集合 — 处理两条独立路径,追加到 {@code disabled} 集合:
     * <ul>
     *   <li>总开关 {@code protection.enabled=false} → 全 4 个防护 handler 短路</li>
     *   <li>per-mechanism 字段为 {@link Boolean#FALSE} → 单独覆盖该机制</li>
     * </ul>
     *
     * <p>输入 {@code disabled} 可含调用方已有的其他禁用项(自定义 handler),本方法
     * 仅追加,不动既有项。
     *
     * @param properties ResiCache 配置属性
     * @param disabled   输出集合(原 {@code Set<String>},调用方持有)
     */
    static void resolveDisabled(RedisProCacheProperties properties, Set<String> disabled) {
        RedisProCacheProperties.ProtectionProperties protection =
                properties == null ? null : properties.getProtection();
        if (protection == null) {
            // 无 protection 配置(测试用 mock):默认视为开启 — 不追加任何禁用项
            return;
        }

        if (!protection.isEnabled()) {
            // 总开关关闭:从单一 TOGGLES 列表派生 4 个 disableName(不再用单独的
            // PROTECTION_HANDLER_ORDERS 列表 —— 与 TOGGLES 合并,消除平行列表漂移风险)
            TOGGLES.stream()
                    .map(toggle -> toggle.order().getDisableName())
                    .forEach(disabled::add);
            log.info("Protection chain disabled by resi-cache.protection.enabled=false; "
                    + "protection handlers skipped, TTL preserved (bloom/lock/early-exp/null-value off)");
        } else {
            // per-mechanism 覆盖:遍历单一 TOGGLES 列表,把显式 FALSE 的加到 disabled
            // Boolean.FALSE.equals(null) → false(null 表示"继承 enabled")
            for (Toggle toggle : TOGGLES) {
                if (Boolean.FALSE.equals(toggle.getter().apply(protection))) {
                    disabled.add(toggle.order().getDisableName());
                    log.info("{} disabled by resi-cache.protection.{}.enabled=false",
                            toggle.order().getDescription(), toggle.configPath());
                }
            }
        }
    }

    /**
     * 当前 TOGGLES 列表大小(机制总数,恒为 4)— 仅暴露给 {@link CacheHandlerChainFactory}
     * 在初始化时校验或调试日志使用。
     *
     * @return 机制总数
     */
    static int toggleCount() {
        return TOGGLES.size();
    }

    /**
     * 仅暴露给需要派生 "所有 protection handler 枚举"的代码 — 当前无外部调用方,
     * 保留以备未来「全局禁用/全局启用」场景。
     *
     * @return 不可变的 protection handler order 列表
     */
    static List<HandlerOrder> protectionHandlerOrders() {
        return TOGGLES.stream().map(Toggle::order).toList();
    }

    /**
     * 仅用于测试断言的纯函数版本 — 不修改入参,直接派生新集合。
     *
     * <p>本方法提供无副作用的纯函数版本,便于测试断言;{@link #resolveDisabled} 是
     * mutate-in-place 版本,供 {@link CacheHandlerChainFactory} 复用现有集合。
     *
     * @param properties ResiCache 配置属性
     * @return 新创建的 disabled 集合
     */
    static Set<String> deriveDisabled(RedisProCacheProperties properties) {
        Set<String> disabled = new HashSet<>();
        resolveDisabled(properties, disabled);
        return disabled;
    }
}