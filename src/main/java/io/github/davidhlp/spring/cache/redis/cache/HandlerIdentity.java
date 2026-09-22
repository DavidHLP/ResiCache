package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.HandlerOrder;
import io.github.davidhlp.spring.cache.redis.chain.HandlerPriority;

/**
 * 单个 handler 在链上的身份 —— slot、配置禁用名、观测标签 —— 一次解析,链路与观测共用。
 *
 * <p>标注 {@link HandlerPriority} 的 handler 三项全部取自 {@link HandlerOrder}(单一事实源),
 * 因此 handler 类重命名既不改变 {@code resi-cache.disabled-handlers} / protection 开关的匹配,
 * 也不改变 Micrometer {@code handler} tag 与链日志的取值。
 *
 * <p>未标注注解的 handler(宿主自定义 handler)不占据标准 slot,身份仍由类名派生 —— 这是唯一
 * 保留的类名派生路径,顺序值退到 {@link Integer#MAX_VALUE}(排在所有标准 slot 之后)。
 *
 * <p>标准 slot 的身份取值与跨 slot 的顺序要求由 {@code HandlerIdentityContractTest} 钉住。
 */
record HandlerIdentity(
        HandlerOrder slot,
        int order,
        String disableName,
        String tag) {

    static HandlerIdentity of(CacheHandler handler) {
        return of(handler.getClass());
    }

    static HandlerIdentity of(Class<? extends CacheHandler> handlerClass) {
        HandlerPriority priority = handlerClass.getAnnotation(HandlerPriority.class);
        if (priority != null) {
            HandlerOrder slot = priority.value();
            return new HandlerIdentity(
                    slot, slot.getOrder(), slot.getDisableName(), slot.getHandlerTag());
        }
        String className = handlerClass.getSimpleName();
        return new HandlerIdentity(null, Integer.MAX_VALUE, toDisableName(className), className);
    }

    /** 非标准 slot 的兼容派生:{@code BloomFilterHandler} → {@code bloom-filter}。 */
    private static String toDisableName(String className) {
        return className.replace("Handler", "")
                .replaceAll("([a-z])([A-Z])", "$1-$2")  // camelCase to kebab-case
                .toLowerCase();
    }
}
