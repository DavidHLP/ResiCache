package io.github.davidhlp.spring.cache.redis.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * TLS 启动期验证器.
 *
 * <p>对齐 {@code SyncSupport.warnIfNoDistributedBackend} 的 fail-loud 模式:
 * 检测 plaintext 凭证/凭据/敏感缓存值在网络上明文传输的 misconfig,要么 WARN 提醒
 * (默认) 要么 fail-fast (新属性 {@code resi-cache.redis.tls-required=true})。
 *
 * <p>不阻断默认行为 — 现有配置(<code>tlsEnabled=false</code>)仍可启动,只发 WARN;
 * 用户显式声明 {@code tls-required=true} 后才硬性 fail。这是与 {@code tls-required=false}
 * 默认值(向后兼容)的契约对称。
 *
 * <p>谓词 {@link #shouldWarn()} / {@link #shouldFail()} package-private 便于单测直接覆盖,
 * 避免只走"构造函数不抛异常"这种无法观测结果的间接测试(与
 * {@code CachingEnablementValidation.detectCachingEnabled} 模式一致)。
 */
@Slf4j
@Component
public class TlsConfigurationValidator {

    private final RedisProCacheProperties properties;

    public TlsConfigurationValidator(RedisProCacheProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动就绪后检查 TLS 配置。
     *
     * <p>三种状态:
     * <ul>
     *   <li>{@code tlsRequired=true} + {@code tlsEnabled=false} → 抛 {@link IllegalStateException}
     *       阻断启动</li>
     *   <li>{@code tlsRequired=false} + {@code tlsEnabled=false} + 设置了
     *       {@code password}/{@code username} → 发 WARN 提示</li>
     *   <li>{@code tlsEnabled=true} → 静默通过</li>
     * </ul>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (shouldFail()) {
            throw new IllegalStateException(
                    "[ResiCache] resi-cache.redis.tls-required=true but tls-enabled=false. "
                    + "Refusing to start with plaintext Redis transport. "
                    + "Either set resi-cache.redis.tls-enabled=true or relax tls-required to false.");
        }
        if (shouldWarn()) {
            log.warn("====================================================================\n"
                    + " ResiCache 警告: TLS 已禁用 (resi-cache.redis.tls-enabled=false),\n"
                    + " 但配置中含 password/username。\n"
                    + " 缓存值与凭证将以明文在网络上传输。\n"
                    + " \n"
                    + " 建议:\n"
                    + "   1. 生产环境启用 TLS:    resi-cache.redis.tls-enabled: true\n"
                    + "   2. 强校验启动(可选):    resi-cache.redis.tls-required: true\n"
                    + "   3. 关闭警告(仅开发):    resi-cache.redis.tls-required: false (默认)\n"
                    + "====================================================================");
        }
    }

    /**
     * 是否应该 fail-fast 阻断启动。
     *
     * <p>条件:用户显式声明 {@code tls-required=true} 但 {@code tls-enabled=false}。
     */
    boolean shouldFail() {
        return properties.getRedis().isTlsRequired()
                && !properties.getRedis().isTlsEnabled();
    }

    /**
     * 是否应该发 WARN 提醒。
     *
     * <p>条件:TLS 未启用 + 配置中含 password 或 username(实际有敏感数据要传)。
     */
    boolean shouldWarn() {
        var redis = properties.getRedis();
        if (redis.isTlsEnabled() || redis.isTlsRequired()) {
            return false;
        }
        boolean hasPassword = redis.getPassword() != null && !redis.getPassword().isEmpty();
        boolean hasUsername = redis.getUsername() != null && !redis.getUsername().isEmpty();
        return hasPassword || hasUsername;
    }
}
