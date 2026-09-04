package io.github.davidhlp.spring.cache.redis.config;

import io.github.davidhlp.spring.cache.redis.protection.avalanche.TtlPolicy;
import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomHashStrategy;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import io.github.davidhlp.spring.cache.redis.protection.nullvalue.NullValuePolicy;
import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;

import java.lang.reflect.Method;

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
                                    io.github.davidhlp.spring.cache.redis.health.RedisCacheHealthIndicator.class);
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
