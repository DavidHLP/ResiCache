package io.github.davidhlp.spring.cache.redis.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动期守卫:提醒用户补回被清空的白名单.
 *
 * <p>STABILITY §1 把 {@code resi-cache.*} property keys 列为 stable surface;
 * 其中 {@code resi-cache.serializer.allowed-package-prefixes} 是 SecureJackson
 * 反序列化的安全门 —— 列表为空(null 或 [])意味着<b>所有非框架内部 type</b>的反序列化
 * 都会抛 {@code SerializationException},运行期才暴露,是最常见的 misconfig footgun。
 *
 * <p>本类在 {@link ApplicationReadyEvent} 时检查列表是否为空,若空则发 WARN 提示
 * 用户补回;谓词 {@link #shouldWarn()} package-private 便于单元测试。不动 default
 * value(默认 {@code [io.github.davidhlp]}),不改 property key,非 breaking 改动。
 *
 * <p>与 GUIDE §4 中"whitelist auto-derive"项配套:该完整项需 host app root package
 * BeanFactory 自推导 + 启动 WARN,标 ⚠️ BREAKING;本类是其中 WARN 的 scaffolding,
 * 单独可发,留待 auto-derive 落地时复用。
 */
@Slf4j
@Component
public class SerializerWhitelistStartupGuard {

    private final RedisProCacheProperties properties;

    public SerializerWhitelistStartupGuard(RedisProCacheProperties properties) {
        this.properties = properties;
    }

    /**
     * 应用启动就绪后检查白名单是否被清空.
     *
     * <p>判空标准:list 为 null 或为空列表。含空字符串 {@code ""} 不算空
     * (pre-existing {@code startsWith("")} = true 行为,不是 footgun)。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (shouldWarn()) {
            log.warn(
                "[ResiCache] resi-cache.serializer.allowed-package-prefixes is empty "
                    + "(null or []). Custom type deserialization will fail with "
                    + "SerializationException. Set the property to include your host "
                    + "application's root package (e.g. com.example.* for wildcard, or "
                    + "com.example.dto for literal). Default [io.github.davidhlp] only "
                    + "covers ResiCache framework internal types.");
        }
    }

    /**
     * 谓词:白名单是否处于"应发 WARN"状态(null 或空列表).
     *
     * <p>package-private 供单元测试,不暴露为 public API。
     */
    boolean shouldWarn() {
        List<String> prefixes =
            properties.getSerializer().getAllowedPackagePrefixes();
        return prefixes == null || prefixes.isEmpty();
    }
}
