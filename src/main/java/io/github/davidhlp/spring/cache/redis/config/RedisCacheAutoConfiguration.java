package io.github.davidhlp.spring.cache.redis.config;




import io.github.davidhlp.spring.cache.redis.cache.SerializationMigrationOperatorConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisOperations;

/**
 * Redis缓存自动配置主入口(运行时装配根)
 *
 * <p>职责： 1. 作为Redis缓存模块的配置入口点 2. 导入各个专门的配置类 3. 确保配置加载顺序正确
 * 4. 单一声明 {@code resi-cache.enabled} 启用门,并导入该门所保护的启用校验
 *
 * <p>内部运行时包只扫描组件;由显式 {@code @Import} 注册的配置类不带组件注解,因此扫描不再
 * 维护类名正则 —— operator 边界装配根按类排除,类改名会编译失败而不是静默改变上下文。
 * {@code .*Test.*} 过滤仅用于隔离同包测试类,与 bean 归属无关。
 *
 * <p>注意：@EnableCaching已移除，避免与用户应用中的其他@EnableCaching冲突。
 *       用户应确保应用中已启用Spring Cache功能。
 */
@Slf4j
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@ConditionalOnClass({RedisOperations.class})
@ConditionalOnProperty(prefix = "resi-cache", name = "enabled", matchIfMissing = true)
@Import(CachingEnablementValidation.class)
@ComponentScan(
        basePackages = "io.github.davidhlp.spring.cache.redis.cache",
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = SerializationMigrationOperatorConfiguration.class),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = ".*Test.*")
        })
public class RedisCacheAutoConfiguration {

}
