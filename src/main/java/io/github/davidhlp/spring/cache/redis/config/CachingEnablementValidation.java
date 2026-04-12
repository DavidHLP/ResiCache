package io.github.davidhlp.spring.cache.redis.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.annotation.Annotation;

/**
 * 缓存启用状态验证配置
 *
 * <p>在应用启动时检查是否已启用 @EnableCaching，
 * 如果未启用则输出警告日志，避免用户在使用 ResiCache 时发现缓存不生效。
 */
@Slf4j
@AutoConfiguration
public class CachingEnablementValidation {

    /**
     * 检查缓存是否已启用的验证器。
     * 如果未启用 @EnableCaching，输出警告日志。
     */
    @Bean
    public CachingEnabledValidator cachingEnabledValidator(ApplicationContext context) {
        return new CachingEnabledValidator(context);
    }

    public static class CachingEnabledValidator {

        public CachingEnabledValidator(ApplicationContext context) {
            checkCachingEnabled(context);
        }

        private void checkCachingEnabled(ApplicationContext context) {
            boolean cachingEnabled = detectCachingEnabled(context);
            if (!cachingEnabled) {
                log.warn(
                        "====================================================================\n"
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

        private boolean detectCachingEnabled(ApplicationContext context) {
            // 检查 Spring 容器中是否注册了 CacheInterceptor bean
            // 只有当 @EnableCaching 被添加后，Spring 才会注册这个 bean
            try {
                // 首先检查是否存在 cacheInterceptor 类型的 bean
                String[] beanNames = context.getBeanNamesForType(
                        Class.forName("org.springframework.cache.interceptor.CacheInterceptor"));
                if (beanNames.length > 0) {
                    log.debug("CacheInterceptor bean found with {} instance(s), caching is enabled", beanNames.length);
                    return true;
                }

                // 备选方案：检查是否注册了 CacheManager bean（@EnableCaching 会触发）
                String[] cacheManagerBeans = context.getBeanNamesForType(
                        Class.forName("org.springframework.cache.CacheManager"));
                if (cacheManagerBeans.length > 0) {
                    log.debug("CacheManager bean found with {} instance(s), caching is enabled", cacheManagerBeans.length);
                    return true;
                }

                log.debug("No CacheInterceptor or CacheManager beans found, caching may not be enabled");
                return false;
            } catch (ClassNotFoundException e) {
                log.debug("CacheInterceptor class not found in classpath, caching is not available");
                return false;
            }
        }
    }
}
