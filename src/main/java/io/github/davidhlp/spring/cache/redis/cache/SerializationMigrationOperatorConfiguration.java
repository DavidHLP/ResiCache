package io.github.davidhlp.spring.cache.redis.cache;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Operator 边界装配根：按类点名迁移 CLI 上下文所需的内部 bean.
 *
 * <p>只由 operator 入口 {@code SerializationMigrationCli} 导入;运行时装配根
 * {@code RedisCacheAutoConfiguration} 按类排除本类,因此该边界不会进入运行时上下文。
 * 内部 bean 不带组件注解,由本类显式声明 —— 类改名会编译失败,而不是静默清空 CLI 上下文。
 */
@Configuration(proxyBeanMethods = false)
@Import({
        SerializationMigrationEngine.class,
        SecureJacksonSerializerFactory.class,
        ResolvedMetricsConfiguration.class
})
public class SerializationMigrationOperatorConfiguration {
}
