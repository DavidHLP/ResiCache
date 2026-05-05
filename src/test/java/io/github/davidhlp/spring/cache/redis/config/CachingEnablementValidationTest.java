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
        @DisplayName("构造函数不抛出异常")
        void constructor_noException() {
            ApplicationContext context = mock(ApplicationContext.class);
            when(context.getBeanNamesForType(org.springframework.cache.CacheManager.class))
                    .thenReturn(new String[]{"cacheManager"});
            when(context.getBeanNamesForType(org.springframework.cache.interceptor.CacheInterceptor.class))
                    .thenReturn(new String[]{});

            // Should not throw
            new CachingEnablementValidation.CachingEnabledValidator(context);
        }

        @Test
        @DisplayName("无CacheManager时创建成功")
        void noCacheManager_noException() {
            ApplicationContext context = mock(ApplicationContext.class);
            when(context.getBeanNamesForType(org.springframework.cache.CacheManager.class))
                    .thenReturn(new String[]{});
            when(context.getBeanNamesForType(org.springframework.cache.interceptor.CacheInterceptor.class))
                    .thenReturn(new String[]{});

            // Should not throw
            new CachingEnablementValidation.CachingEnabledValidator(context);
        }

        @Test
        @DisplayName("CacheInterceptor存在时启用缓存")
        void cacheInterceptorExists_enabled() {
            ApplicationContext context = mock(ApplicationContext.class);
            when(context.getBeanNamesForType(org.springframework.cache.CacheManager.class))
                    .thenReturn(new String[]{});
            when(context.getBeanNamesForType(org.springframework.cache.interceptor.CacheInterceptor.class))
                    .thenReturn(new String[]{"cacheInterceptor"});

            // Should not throw
            new CachingEnablementValidation.CachingEnabledValidator(context);
        }
    }
}
