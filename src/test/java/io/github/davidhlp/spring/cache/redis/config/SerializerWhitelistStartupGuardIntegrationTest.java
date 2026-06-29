package io.github.davidhlp.spring.cache.redis.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SerializerWhitelistStartupGuard 集成测试.
 *
 * <p>用 {@link ApplicationContextRunner} 启动最小 Spring 上下文,挂 Logback
 * {@link ListAppender} 捕获 guard 的 logger 输出,验证:
 * <ul>
 *   <li>{@code []} 触发 WARN,消息含
 *       {@code resi-cache.serializer.allowed-package-prefixes is empty} 与
 *       remediation hint {@code com.example.*};</li>
 *   <li>非空列表 / 默认值不触发 WARN。</li>
 * </ul>
 *
 * <p>覆盖 R15 单测 {@code SerializerWhitelistStartupGuardTest} 未覆盖的运行时
 * 路径:实际 Spring 生命周期下 {@code @EventListener(ApplicationReadyEvent.class)}
 * 是否真的发出 log。R15 单测只验 {@code shouldWarn()} 谓词,本测试验 wiring +
 * event firing + log emission 整条链。null list 路径由 R15 单测覆盖,focus
 * 在实际事件链。
 *
 * <p><b>设计权衡</b>:用 {@code withBean(...)} 显式注册 properties + guard,
 * 而非用 {@code @Configuration @Bean} — 因为 {@code @Configuration} 静态内
 * 类会被 Spring component scan 误扫到其他 IT 测试上下文,与生产
 * {@code @ConfigurationProperties} bean 名冲突。{@code withBean} 隔离在当前
 * runner 上下文内,无副作用。
 */
@DisplayName("SerializerWhitelistStartupGuard Integration Tests")
class SerializerWhitelistStartupGuardIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    private ListAppender<ILoggingEvent> listAppender;
    private Logger guardLogger;

    @BeforeEach
    void attachAppender() {
        guardLogger = (Logger) LoggerFactory.getLogger(
            SerializerWhitelistStartupGuard.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        guardLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachAppender() {
        if (guardLogger != null && listAppender != null) {
            guardLogger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    @Test
    @DisplayName("allowedPackagePrefixes 为 [] → ApplicationReadyEvent 触发 WARN,消息含 remediation hint")
    void emptyList_emitsWarnWithRemediationHint() {
        RedisProCacheProperties props = new RedisProCacheProperties();
        props.getSerializer().setAllowedPackagePrefixes(new ArrayList<>());

        runner.withBean(RedisProCacheProperties.class, () -> props)
            .withBean(SerializerWhitelistStartupGuard.class,
                () -> new SerializerWhitelistStartupGuard(props))
            .run(context -> {
                assertThat(context).hasNotFailed();
                context.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(), new String[0],
                    context.getSourceApplicationContext(),
                    java.time.Duration.ZERO));
                List<ILoggingEvent> warns = guardWarns();
                assertThat(warns).isNotEmpty();
                String msg = warns.get(0).getFormattedMessage();
                assertThat(msg)
                    .contains("resi-cache.serializer.allowed-package-prefixes")
                    .contains("is empty")
                    .contains("com.example.*")
                    .contains("com.example.dto");
            });
    }

    @Test
    @DisplayName("allowedPackagePrefixes 有值 → 不发 WARN")
    void populatedList_emitsNoWarn() {
        RedisProCacheProperties props = new RedisProCacheProperties();
        props.getSerializer().setAllowedPackagePrefixes(List.of("com.example.app"));

        runner.withBean(RedisProCacheProperties.class, () -> props)
            .withBean(SerializerWhitelistStartupGuard.class,
                () -> new SerializerWhitelistStartupGuard(props))
            .run(context -> {
                assertThat(context).hasNotFailed();
                context.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(), new String[0],
                    context.getSourceApplicationContext(),
                    java.time.Duration.ZERO));
                assertThat(guardWarns()).isEmpty();
            });
    }

    @Test
    @DisplayName("默认 [io.github.davidhlp] → 不发 WARN")
    void defaultList_emitsNoWarn() {
        RedisProCacheProperties props = new RedisProCacheProperties();   // 默认 [io.github.davidhlp]

        runner.withBean(RedisProCacheProperties.class, () -> props)
            .withBean(SerializerWhitelistStartupGuard.class,
                () -> new SerializerWhitelistStartupGuard(props))
            .run(context -> {
                assertThat(context).hasNotFailed();
                context.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(), new String[0],
                    context.getSourceApplicationContext(),
                    java.time.Duration.ZERO));
                assertThat(guardWarns()).isEmpty();
            });
    }

    private List<ILoggingEvent> guardWarns() {
        return listAppender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN
                && e.getLoggerName()
                    .equals(SerializerWhitelistStartupGuard.class.getName()))
            .toList();
    }
}
