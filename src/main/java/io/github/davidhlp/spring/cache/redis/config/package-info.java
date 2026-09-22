/**
 * Redis缓存配置包.
 *
 * <p>本包仅承载稳定自动配置/属性入口：
 * <ul>
 *   <li>RedisCacheAutoConfiguration - 主配置入口(运行时装配根,单一声明启用门)</li>
 *   <li>RedisProCacheProperties - {@code resi-cache.*} 配置契约</li>
 *   <li>CachingEnablementValidation - 启用校验,由主入口导入</li>
 * </ul>
 * <p>具体装配类位于 package-private {@code cache} runtime。
 */
@org.springframework.lang.NonNullApi
package io.github.davidhlp.spring.cache.redis.config;
