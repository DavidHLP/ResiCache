package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.config.CachingEnablementValidation;
import io.github.davidhlp.spring.cache.redis.config.MetricsAutoConfiguration;
import io.github.davidhlp.spring.cache.redis.config.RedisCacheAutoConfiguration;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import static org.assertj.core.api.Assertions.assertThat;

class RedisProCacheConfigurationContractTest {

    @Test
    void disabledMasterSwitch_skipsResiCacheAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisCacheAutoConfiguration.class))
                .withPropertyValues("resi-cache.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RedisProCacheConfiguration.class);
                });
    }

    @Test
    void disabledMasterSwitch_alsoSkipsMetricsConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisCacheAutoConfiguration.class,
                        MetricsAutoConfiguration.class,
                        CachingEnablementValidation.class))
                .withPropertyValues(
                        "resi-cache.enabled=false",
                        "resi-cache.metrics.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(
                                    io.github.davidhlp.spring.cache.redis.cache.RedisCacheHealthIndicator.class);
                    assertThat(context)
                            .doesNotHaveBean(
                                    CachingEnablementValidation.CachingEnabledValidator.class);
                });
    }

    @Test
    void productionConfiguration_hasNoRootComponentScan() {
        assertThat(RedisProCacheConfiguration.class.isAnnotationPresent(ComponentScan.class)).isFalse();
    }

    @Test
    void entry_componentScan_isInternalRuntimePackageOnly() {
        // RM-005(DEC-003 Option A):唯一扫描点是公共入口,范围锁定内部 cache 运行时包;
        // 不得出现根包扫描,且 test-class 排除过滤保留。
        ComponentScan scan = RedisCacheAutoConfiguration.class.getAnnotation(ComponentScan.class);
        assertThat(scan).isNotNull();
        assertThat(scan.basePackages())
                .containsExactly("io.github.davidhlp.spring.cache.redis.cache");
        assertThat(scan.basePackageClasses()).isEmpty();
        assertThat(scan.excludeFilters())
                .anySatisfy(filter -> assertThat(filter.pattern()).containsExactly(".*Test.*"));
    }

    @Test
    void supportedSeams_backOffToUserBeans_andUnrelatedHostBeansAreIgnored() throws Exception {
        // RM-005 行为探针:用户提供的 LockManager/BloomIFilter 赢得 typed back-off;
        // 用户 CacheManager → 库 cacheManager + proxy advisor/interceptor 一并退场
        // (用户接管 Spring Cache,启动不失败);无关宿主 bean 不改变装配。
        // Redisson 从 classpath 过滤:RedissonClient bean 创建会主动连接 Redis,
        // 且需要 Boot DataRedisProperties —— 单元层不可用;LockManager 默认 bean 的
        // @ConditionalOnMissingBean 回归在 Redisson/容器层由 RedissonConfigurationTest 覆盖。
        try (org.springframework.boot.test.context.FilteredClassLoader classLoader =
                new org.springframework.boot.test.context.FilteredClassLoader(
                        org.redisson.api.RedissonClient.class)) {
            new ApplicationContextRunner()
                    .withClassLoader(classLoader)
                    .withConfiguration(AutoConfigurations.of(RedisCacheAutoConfiguration.class))
                    .withUserConfiguration(CustomSeamConfig.class)
                    .withBean(RedisConnectionFactory.class,
                            () -> org.mockito.Mockito.mock(RedisConnectionFactory.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(LockManager.class)).hasSize(1);
                        assertThat(context.getBeansOfType(BloomIFilter.class)).hasSize(1);
                        // 用户 CacheManager:库 cacheManager back-off,只剩用户 bean
                        assertThat(context.getBeansOfType(CacheManager.class)).hasSize(1);
                        assertThat(context.getBeansOfType(CacheManager.class))
                                .containsOnlyKeys("cacheManager");
                        // 库 proxy 随 RedisProCacheManager 一起退场(不再注入失败)
                        assertThat(context.getBeansOfType(
                                io.github.davidhlp.spring.cache.redis.cache.RedisProCacheManager.class)).isEmpty();
                        assertThat(context).doesNotHaveBean("redisCacheAdvisor");
                        assertThat(context).doesNotHaveBean("redisCacheInterceptor");
                        assertThat(context).hasBean("unrelatedHostBean");
                    });
        }
    }

    @Test
    void defaultAssembly_createsProxyAndCacheManager_withoutUserOverrides() throws Exception {
        // 无用户覆盖时的默认装配(RM-005):Redisson 从 classpath 过滤(单元层无 Redis,
        // RedissonClient bean 创建会主动连接),库 cacheManager(RedisProCacheManager)
        // 与 proxy advisor/interceptor 完整创建 —— 真实 Redis 装配回归由容器测试承担。
        try (org.springframework.boot.test.context.FilteredClassLoader classLoader =
                new org.springframework.boot.test.context.FilteredClassLoader(
                        org.redisson.api.RedissonClient.class)) {
            new ApplicationContextRunner()
                    .withClassLoader(classLoader)
                    .withConfiguration(AutoConfigurations.of(RedisCacheAutoConfiguration.class))
                    .withBean(RedisConnectionFactory.class,
                            () -> org.mockito.Mockito.mock(RedisConnectionFactory.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasBean("cacheManager");
                        assertThat(context).hasBean("redisCacheAdvisor");
                        assertThat(context).hasBean("redisCacheInterceptor");
                    });
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class CustomSeamConfig {

        @Bean
        LockManager lockManager() {
            return org.mockito.Mockito.mock(LockManager.class);
        }

        @Bean
        BloomIFilter bloomIFilter() {
            return org.mockito.Mockito.mock(BloomIFilter.class);
        }

        @Bean
        CacheManager cacheManager() {
            return org.mockito.Mockito.mock(CacheManager.class);
        }

        @Bean
        String unrelatedHostBean() {
            return "unrelated";
        }
    }

    @Test
    void standardObserverBeans_areDeclaredWithOrder() throws Exception {
        // P1-API-001-C:标准 observer 为有序 Bean,由 factory 单一装配注入 Engine。
        assertThat(RedisProCacheConfiguration.class.getDeclaredMethod(
                        "mdcStampChainObserver"))
                .isNotNull();
        assertThat(RedisProCacheConfiguration.class.getDeclaredMethod(
                        "chainDebugLogChainObserver"))
                .isNotNull();
        assertThat(RedisProCacheConfiguration.class.getDeclaredMethod(
                        "chainTimerChainObserver",
                        org.springframework.beans.factory.ObjectProvider.class))
                .isNotNull();
        assertThat(RedisProCacheConfiguration.class.getDeclaredMethod(
                        "firedCounterChainObserver",
                        org.springframework.beans.factory.ObjectProvider.class))
                .isNotNull();
        // 顺序注解:MDC(1) → DebugLog(2) → Timer(3) → FiredCounter(4)
        Method[] methods = RedisProCacheConfiguration.class.getDeclaredMethods();
        for (Method m : methods) {
            if (m.getName().endsWith("ChainObserver") || m.getName().endsWith("StampChainObserver")
                    || m.getName().equals("chainDebugLogChainObserver")) {
                org.springframework.core.annotation.Order order =
                        m.getAnnotation(org.springframework.core.annotation.Order.class);
                assertThat(order)
                        .as("observer bean 方法 %s 必须带 @Order", m.getName())
                        .isNotNull();
            }
        }
    }

    @Test
    void replaceableDefaults_backOffByContractType() {
        assertThat(conditionOn("ttlPolicy").value()).contains(TtlPolicy.class);
        assertThat(conditionOn("nullValuePolicy").value()).contains(NullValuePolicy.class);
        assertThat(conditionOn("earlyExpirationPolicy").value()).contains(EarlyExpirationPolicy.class);
        assertThat(conditionOn("bloomHashStrategy").value()).contains(BloomHashStrategy.class);
        assertThat(conditionOn("bloomIFilter").value()).contains(BloomIFilter.class);
    }

    @Test
    void everyDefaultBeanDeclaresBackoff() {
        String[] defaultBeanMethods = {
                "methodMetadataResolver",
                "cacheErrorHandler",
                "cacheOperationResolver",
                "bloomFilterConfig",
                "bloomHashStrategy",
                "ttlPolicy",
                "nullValuePolicy",
                "earlyExpirationPolicy",
                "bloomIFilter",
                "redisProCacheWriter",
                "defaultRedisCacheConfiguration",
                "cacheManager",
                "keyGenerator",
                "cacheStatisticsCollector",
                "systemClock",
                "earlyExpirationExecutor"
        };

        for (String methodName : defaultBeanMethods) {
            assertThat(conditionOn(methodName))
                    .as("default bean method %s", methodName)
                    .isNotNull();
        }
    }

    private ConditionalOnMissingBean conditionOn(String methodName) {
        for (Method method : RedisProCacheConfiguration.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method.getAnnotation(ConditionalOnMissingBean.class);
            }
        }
        throw new AssertionError("Missing auto-configuration method: " + methodName);
    }
}
