package io.github.davidhlp.spring.cache.redis.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisOperations;

/**
 * Redis缓存自动配置主入口
 *
 * <p>职责： 1. 作为Redis缓存模块的配置入口点 2. 导入各个专门的配置类 3. 确保配置加载顺序正确
 *
 * <p>注意：@EnableCaching已移除，避免与用户应用中的其他@EnableCaching冲突。
 *       用户应确保应用中已启用Spring Cache功能。
 */
@Slf4j
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass({RedisOperations.class})
@Import({
    JacksonConfig.class,
    RedisConnectionConfiguration.class,
    RedisCacheRegistryConfiguration.class,
    RedisProxyCachingConfiguration.class,
    RedisProCacheConfiguration.class
})
public class RedisCacheAutoConfiguration {

}
