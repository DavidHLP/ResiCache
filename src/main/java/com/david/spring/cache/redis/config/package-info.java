/// Redis缓存配置包
/// 本包按照单一职责原则组织配置类：
/// 1. [com.david.spring.cache.redis.config.RedisCacheAutoConfiguration] - 主配置入口，负责启用缓存和导入子配置
/// 2. [com.david.spring.cache.redis.config.RedisConnectionConfiguration] - 连接和模板配置，负责Redis连接池和序列化
/// 3. [com.david.spring.cache.redis.config.RedisProCacheConfiguration] - 缓存核心组件配置，负责缓存管理器和写入器
/// 4. [com.david.spring.cache.redis.config.RedisCacheRegistryConfiguration] - 注册器配置，负责缓存操作元数据管理
/// 5. [com.david.spring.cache.redis.config.RedisProxyCachingConfiguration] - 代理拦截器配置，负责AOP切面和注解解析
/// 配置类间依赖关系：
/// - RedisCacheAutoConfiguration 作为入口点导入其他配置
/// - RedisConnectionConfiguration 提供基础连接和模板
/// - RedisCacheConfiguration 依赖连接配置提供的模板
/// - RedisProxyCachingConfiguration 依赖缓存配置提供的管理器
/// - RedisCacheRegistryConfiguration 独立提供注册服务
@NonNullApi
package com.david.spring.cache.redis.config;

import org.springframework.lang.NonNullApi;
