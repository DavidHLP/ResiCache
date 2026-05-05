package io.github.davidhlp.spring.cache.redis.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 缓存启用状态验证配置.
 *
 * <p>在应用启动时检查是否已启用 @EnableCaching.
 */
@Slf4j
@AutoConfiguration
public class CachingEnablementValidation {

    /**
     * 创建缓存启用验证器.
     *
     * @param context 应用上下文
     * @return 验证器实例
     */
    @Bean
    public CachingEnabledValidator cachingEnabledValidator(
            final ApplicationContext context) {
        return new CachingEnabledValidator(context);
    }

    /**
     * 缓存启用验证器.
     */
    public static class CachingEnabledValidator {

        /**
         * 构造函数.
         *
         * @param context 应用上下文
         */
        public CachingEnabledValidator(final ApplicationContext context) {
            checkCachingEnabled(context);
        }

        private void checkCachingEnabled(final ApplicationContext context) {
            final boolean cachingEnabled = detectCachingEnabled(context);
            if (!cachingEnabled) {
                log.warn("====================================================================\n"
                        + " ResiCache 警告: 未检测到 @EnableCaching 注解！\n"
                        + " 缓存 AOP 拦截功能将不会生效。\n"
                        + " \n"
                        + " 请在 Spring Boot 应用的配置类上添加 @EnableCaching 注解：\n"
                        + " \n"
                        + " @Configuration\n"
                        + " @EnableCaching\n"
                        + " public class MyCacheConfig { ... }\n"
                        + "====================================================================");
            } else {
                log.info("ResiCache: @EnableCaching 已检测到，缓存功能正常");
            }
        }

        private boolean detectCachingEnabled(final ApplicationContext context) {
            try {
                final String[] beanNames = context.getBeanNamesForType(
                        Class.forName(
                                "org.springframework.cache.interceptor.CacheInterceptor"));
                if (beanNames.length > 0) {
                    log.debug("CacheInterceptor bean found with {} instance(s), "
                            + "caching is enabled", beanNames.length);
                    return true;
                }

                final String[] cacheManagerBeans = context.getBeanNamesForType(
                        Class.forName("org.springframework.cache.CacheManager"));
                if (cacheManagerBeans.length > 0) {
                    log.debug("CacheManager bean found with {} instance(s), "
                            + "caching is enabled", cacheManagerBeans.length);
                    return true;
                }

                log.debug("No CacheInterceptor or CacheManager beans found, "
                        + "caching may not be enabled");
                return false;
            } catch (ClassNotFoundException e) {
                log.debug("CacheInterceptor class not found in classpath, "
                        + "caching is not available");
                return false;
            }
        }
    }
}
