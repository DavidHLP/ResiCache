package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.config.CachingEnablementValidation;
import io.github.davidhlp.spring.cache.redis.config.RedisCacheAutoConfiguration;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
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
                .withConfiguration(AutoConfigurations.of(RedisCacheAutoConfiguration.class))
                .withPropertyValues(
                        "resi-cache.enabled=false",
                        "resi-cache.metrics.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(
                                    io.github.davidhlp.spring.cache.redis.cache.RedisCacheHealthIndicator.class);
                    // 启用门只在运行时装配根声明一次:关闭主开关即不再导入启用校验
                    assertThat(context)
                            .doesNotHaveBean(
                                    CachingEnablementValidation.CachingEnabledValidator.class);
                });
    }

    @Test
    void metricsDisabled_doesNotRegisterMeters_afterCacheOperation() throws Exception {
        assertMetricsAssembly(false, registry -> assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getName().startsWith("resicache"))
                .isEmpty());
    }

    @Test
    void metricsEnabled_registersCacheMeter_afterCacheOperation() throws Exception {
        assertMetricsAssembly(true, registry -> assertThat(
                registry.find("resicache.cache.put").timer()).isNotNull());
    }

    @Test
    void metricsEnabled_withoutMeterRegistry_keepsNoOpChoice() throws Exception {
        try (org.springframework.boot.test.context.FilteredClassLoader classLoader =
                new org.springframework.boot.test.context.FilteredClassLoader(
                        org.redisson.api.RedissonClient.class)) {
            new ApplicationContextRunner()
                    .withClassLoader(classLoader)
                    .withConfiguration(AutoConfigurations.of(RedisCacheAutoConfiguration.class))
                    .withPropertyValues("resi-cache.metrics.enabled=true")
                    .withBean(RedisProCacheWriter.class,
                            () -> org.mockito.Mockito.mock(RedisProCacheWriter.class))
                    .withBean(RedisConnectionFactory.class,
                            () -> org.mockito.Mockito.mock(RedisConnectionFactory.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(MeterRegistry.class);
                        assertThat(context).hasSingleBean(ResolvedMetrics.class);
                        assertThat(context.getBean(ResolvedMetrics.class).meterRegistry())
                                .isSameAs(ResolvedMetrics.NOOP_REGISTRY);
                    });
        }
    }

    private void assertMetricsAssembly(
            boolean enabled,
            Consumer<SimpleMeterRegistry> assertion) throws Exception {
        try (org.springframework.boot.test.context.FilteredClassLoader classLoader =
                new org.springframework.boot.test.context.FilteredClassLoader(
                        org.redisson.api.RedissonClient.class)) {
            new ApplicationContextRunner()
                    .withClassLoader(classLoader)
                    .withConfiguration(AutoConfigurations.of(RedisCacheAutoConfiguration.class))
                    .withPropertyValues("resi-cache.metrics.enabled=" + enabled)
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withBean(RedisProCacheWriter.class,
                            () -> org.mockito.Mockito.mock(RedisProCacheWriter.class))
                    .withBean(RedisConnectionFactory.class,
                            () -> org.mockito.Mockito.mock(RedisConnectionFactory.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        Cache cache = context.getBean(CacheManager.class).getCache("metricsProbe");
                        assertThat(cache).isNotNull();
                        cache.put("key", "value");
                        assertion.accept((SimpleMeterRegistry) context.getBean(MeterRegistry.class));
                    });
        }
    }

    @Test
    void productionConfiguration_hasNoRootComponentScan() {
        assertThat(RedisProCacheConfiguration.class.isAnnotationPresent(ComponentScan.class)).isFalse();
    }
    @Test
    void productionConfiguration_importsInternalConfigurationsExplicitly() {
        org.springframework.context.annotation.Import configurationImport =
                RedisProCacheConfiguration.class.getAnnotation(
                        org.springframework.context.annotation.Import.class);

        assertThat(configurationImport).isNotNull();
        assertThat(configurationImport.value())
                .containsExactlyInAnyOrder(
                        RedisProxyCachingConfiguration.class,
                        ResolvedMetricsConfiguration.class);
    }

    @Test
    void entry_componentScan_excludesOperatorBoundaryByClass() {
        ComponentScan scan = RedisCacheAutoConfiguration.class.getAnnotation(ComponentScan.class);
        assertThat(scan).isNotNull();
        assertThat(java.util.Arrays.stream(scan.excludeFilters())
                .filter(filter -> filter.type() == FilterType.ASSIGNABLE_TYPE)
                .flatMap(filter -> java.util.Arrays.stream(filter.classes()))
                .toList())
                .containsExactly(SerializationMigrationOperatorConfiguration.class);
    }

    @Test
    void entry_componentScan_usesNoOwnershipNamePattern() {
        // 仅保留同包测试类过滤;bean 归属不再由类名正则表达
        ComponentScan scan = RedisCacheAutoConfiguration.class.getAnnotation(ComponentScan.class);
        assertThat(java.util.Arrays.stream(scan.excludeFilters())
                .filter(filter -> filter.type() == FilterType.REGEX)
                .flatMap(filter -> java.util.Arrays.stream(filter.pattern()))
                .toList())
                .containsExactly(".*Test.*");
    }

    @Test
    void autoConfigurationImports_registerOnlyTheRuntimeRoot() throws Exception {
        try (java.io.InputStream imports = RedisCacheAutoConfiguration.class.getResourceAsStream(
                "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(imports).as("auto-configuration imports resource").isNotNull();
            assertThat(new java.io.BufferedReader(
                            new java.io.InputStreamReader(imports, java.nio.charset.StandardCharsets.UTF_8))
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList())
                    .containsExactly(RedisCacheAutoConfiguration.class.getName());
        }
    }

    @Test
    void defaultAssembly_doesNotInstallOperatorMigrationEngine() throws Exception {
        try (org.springframework.boot.test.context.FilteredClassLoader classLoader =
                new org.springframework.boot.test.context.FilteredClassLoader(
                        org.redisson.api.RedissonClient.class)) {
            new ApplicationContextRunner()
                    .withClassLoader(classLoader)
                    .withConfiguration(AutoConfigurations.of(RedisCacheAutoConfiguration.class))
                    .withBean(RedisConnectionFactory.class,
                            () -> org.mockito.Mockito.mock(RedisConnectionFactory.class))
                    .run(context -> assertThat(context)
                            .doesNotHaveBean(
                                    io.github.davidhlp.spring.cache.redis.serialization.migration
                                            .SerializationMigrationCli.SerializationMigrationRunner.class));
        }
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
                        assertThat(context).hasBean("redisCacheOperationSource");
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
                        assertThat(context).hasSingleBean(CacheHandlerChainFactory.class);
                        assertThat(context).hasSingleBean(ChainEngine.class);
                        assertThat(context).doesNotHaveBean(CacheHandlerChain.class);
                    });
        }
    }

    @Test
    void operationSource_wiringProvidesRegisterForSnapshotRegistration() throws Exception {
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
                        RedisCacheOperationSource source = context.getBean(
                                "redisCacheOperationSource", RedisCacheOperationSource.class);
                        Method method = SpringWiredService.class.getMethod("read", String.class);

                        assertThat(source.getCacheOperations(method, SpringWiredService.class))
                                .isNotEmpty();
                        assertThat(context.getBean(RedisCacheRegister.class)
                                .getSnapshot(method, SpringWiredService.class))
                                .isNotNull();
                    });
        }
    }

    static class SpringWiredService {
        @RedisCacheable("spring-wiring-cache")
        public String read(String value) {
            return value;
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
    void standardObserverBeans_areDeclaredWithOrder() {
        var observerMethods = java.util.Arrays.stream(
                        RedisProCacheConfiguration.class.getDeclaredMethods())
                .filter(method -> io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver.class
                        .isAssignableFrom(method.getReturnType()))
                .toList();

        assertThat(observerMethods).hasSize(4);
        assertThat(observerMethods).allSatisfy(method -> {
            assertThat(method.getAnnotation(Bean.class))
                    .as("observer factory must be a bean method")
                    .isNotNull();
            assertThat(method.getAnnotation(org.springframework.core.annotation.Order.class))
                    .as("observer bean must be ordered")
                    .isNotNull();
        });
        assertThat(observerMethods)
                .extracting(method -> method.getAnnotation(
                        org.springframework.core.annotation.Order.class).value())
                .containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    void replaceableDefaults_backOffByContractType() {
        assertThat(conditionOn("bloomIFilter").value()).contains(BloomIFilter.class);
    }

    @Test
    void everyDefaultBeanDeclaresBackoff() {
        java.util.List<Method> beanMethods = java.util.Arrays.stream(
                        RedisProCacheConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .toList();

        assertThat(beanMethods).isNotEmpty();
        // 标准 observer 是叠加钩子(用户 observer 与它们共存),不是可替换默认 bean;
        // 该集合由 standardObserverBeans_areDeclaredWithOrder 固定为 4 个。
        // 其余每个 @Bean 方法都必须按类型 back off —— 新增服务 bean 缺少注解除即失败。
        assertThat(beanMethods)
                .filteredOn(method -> !io.github.davidhlp.spring.cache.redis.chain.observer
                        .ChainObserver.class.isAssignableFrom(method.getReturnType()))
                .allSatisfy(method -> assertThat(method.getAnnotation(ConditionalOnMissingBean.class))
                        .as("default bean method %s must back off by type", method.getName())
                        .isNotNull());
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
