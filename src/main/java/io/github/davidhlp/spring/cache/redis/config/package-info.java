/**
 * Redis缓存配置包.
 *
 * <p>本包按照单一职责原则组织配置类：
 * <ul>
 *   <li>RedisCacheAutoConfiguration - 主配置入口</li>
 *   <li>RedisConnectionConfiguration - 连接和模板配置</li>
 *   <li>RedisProCacheConfiguration - 缓存核心组件配置</li>
 *   <li>RedisCacheRegistryConfiguration - 注册器配置</li>
 *   <li>RedisProxyCachingConfiguration - 代理拦截器配置</li>
 * </ul>
 */
@NonNullApi
package io.github.davidhlp.spring.cache.redis.config;

import org.springframework.lang.NonNullApi;
