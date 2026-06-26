package io.github.davidhlp.spring.cache.redis.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CachingEnablementValidation 单元测试
 */
@DisplayName("CachingEnablementValidation Tests")
class CachingEnablementValidationTest {

    @Nested
    @DisplayName("CachingEnabledValidator")
    class CachingEnabledValidatorTests {

        @Test
        @DisplayName("CacheInterceptor存在 → 检测为已启用")
        void cacheInterceptorExists_detectedAsEnabled() {
            ApplicationContext context = mock(ApplicationContext.class);
            when(context.getBeanNamesForType(org.springframework.cache.interceptor.CacheInterceptor.class))
                    .thenReturn(new String[]{"cacheInterceptor"});
            when(context.getBeanNamesForType(org.springframework.cache.CacheManager.class))
                    .thenReturn(new String[]{});

            assertThat(CachingEnablementValidation.CachingEnabledValidator
                    .detectCachingEnabled(context)).isTrue();
        }

        @Test
        @DisplayName("CacheManager存在(无CacheInterceptor) → 检测为已启用")
        void cacheManagerExists_detectedAsEnabled() {
            ApplicationContext context = mock(ApplicationContext.class);
            when(context.getBeanNamesForType(org.springframework.cache.interceptor.CacheInterceptor.class))
                    .thenReturn(new String[]{});
            when(context.getBeanNamesForType(org.springframework.cache.CacheManager.class))
                    .thenReturn(new String[]{"cacheManager"});

            assertThat(CachingEnablementValidation.CachingEnabledValidator
                    .detectCachingEnabled(context)).isTrue();
        }

        @Test
        @DisplayName("CacheInterceptor与CacheManager均不存在 → 检测为未启用")
        void neitherExists_detectedAsDisabled() {
            ApplicationContext context = mock(ApplicationContext.class);
            when(context.getBeanNamesForType(org.springframework.cache.interceptor.CacheInterceptor.class))
                    .thenReturn(new String[]{});
            when(context.getBeanNamesForType(org.springframework.cache.CacheManager.class))
                    .thenReturn(new String[]{});

            assertThat(CachingEnablementValidation.CachingEnabledValidator
                    .detectCachingEnabled(context)).isFalse();
        }
    }
}
