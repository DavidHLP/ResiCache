package io.github.davidhlp.spring.cache.redis.config;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Redisson 是真正的可选依赖.
 *
 * <p>当 {@code RedissonClient} 不在 classpath 时:
 * <ul>
 *   <li>{@link RedissonConfiguration}(类级 {@code @ConditionalOnClass})不被加载,
 *       其 {@code redissonClient} bean 不注册——证明类级条件在类加载前生效;</li>
 *   <li>整个上下文不会因 {@code NoClassDefFoundError} 启动失败。</li>
 * </ul>
 *
 * <p>用 {@link FilteredClassLoader} 在运行时从 classpath 隐藏
 * {@code RedissonClient},模拟"用户未引入 Redisson"的场景。
 */
class RedissonOptionalConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void redissonConfigurationIsSkippedWhenRedissonAbsentFromClasspath() {
        try (FilteredClassLoader classLoader = new FilteredClassLoader(RedissonClient.class)) {
            runner.withClassLoader(classLoader)
                    .withUserConfiguration(RedissonConfiguration.class)
                    .run(context -> {
                        // 上下文不失败(无 NoClassDefFoundError)
                        assertThat(context).hasNotFailed();
                        // 类级 @ConditionalOnClass 生效:redissonClient bean 不存在
                        assertThat(context).doesNotHaveBean(RedissonClient.class);
                    });
        } catch (Exception e) {
            throw new AssertionError("Context should start without Redisson on classpath", e);
        }
    }
}
