package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
 * <p>本守卫只检查列表是否为空，不推导业务包，也不修改白名单。
 * 应用需显式配置业务类型所在的包，并保留框架内部值所需的命名空间。
 */
@Slf4j
@Component
class SerializerWhitelistStartupGuard {

    private final RedisProCacheProperties properties;

    public SerializerWhitelistStartupGuard(RedisProCacheProperties properties) {
        this.properties = properties;
    }

    /**
     * 应用启动就绪后检查白名单是否被清空.
     *
     * <p>Empty lists and blank literal prefixes are diagnosed without changing matching.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        List<String> prefixes = properties.getSerializer().getAllowedPackagePrefixes();
        if (prefixes != null && prefixes.stream().anyMatch(String::isBlank)) {
            log.warn("[ResiCache] allowed-package-prefixes contains a blank prefix. "
                    + "An empty literal prefix permits every class; whitespace is matched literally. "
                    + "Configure explicit trusted packages. Matching semantics are unchanged.");
        }
        if (prefixes == null || prefixes.isEmpty()) {
            log.warn(
                "[ResiCache] resi-cache.serializer.allowed-package-prefixes is empty "
                    + "(null or []). Custom type deserialization will fail with "
                    + "SerializationException. Set the property to include your host "
                    + "application's root package (e.g. com.example.* for wildcard, or "
                    + "com.example.dto for literal). Default [io.github.davidhlp] only "
                    + "covers ResiCache framework internal types.");
        }
    }

}
