---
title: 元数据持有与配置装配辅助(holder + config 辅助类)
type: modules
tags: [CacheOperationMetadataHolder, 配置装配, VersionEnvelope, CachingEnablementValidation]
related: [operations, auto-configuration, annotations]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/holder/CacheOperationMetadataHolder.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProxyCachingConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisCacheRegistryConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/CachingEnablementValidation.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/VersionEnvelope.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/JacksonConfig.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 元数据持有与配置装配辅助

两个散落但重要的辅助:`holder` 包缓存方法→操作元数据;`config` 包除 `RedisProCacheProperties`/`SecureJackson`/`RedisProCacheConfiguration` 外的几个装配类。

## CacheOperationMetadataHolder

`src/main/java/io/github/davidhlp/spring/cache/redis/holder/CacheOperationMetadataHolder.java:22`

`final` 类。AOP 拦截器(`RedisCacheInterceptor`)每次方法调用都需要该方法对应的操作元数据(`CacheOperation` 列表 + key 求值上下文等)。重复解析注解开销大,本类把这些**按方法缓存**,后续调用直接命中缓存副本。与 [[operations]] 的 `RedisCacheRegister`(按缓存名)互补:Holder 是方法粒度,Register 是缓存名粒度。

## config 包辅助装配类

> 主装配见 [[auto-configuration]],序列化见 [[serialization]],配置项见 [[configuration]]。本页讲其余几个。

### RedisProxyCachingConfiguration

`src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProxyCachingConfiguration.java:25`

装配 `RedisCacheOperationSource`(常量 `REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME = "redisCacheOperationSource"`)等代理缓存基础设施,把 ResiCache 的注解源接到 Spring Cache 的 `CacheInterceptor`。

### RedisCacheRegistryConfiguration

装配 `RedisCacheRegister`([[operations]])及注册相关的 bean。

### CachingEnablementValidation

`src/main/java/io/github/davidhlp/spring/cache/redis/config/CachingEnablementValidation.java:16`

启动期校验缓存配置合法性,内含 `CachingEnabledValidator`(`@Validated` 配套的手写校验器)。例如 `@EnableCaching` 缺失、注解属性组合矛盾时 fail-fast,而非运行时才暴露。

### VersionEnvelope

`src/main/java/io/github/davidhlp/spring/cache/redis/config/VersionEnvelope.java:24`

带 `@JsonTypeInfo(use = Id.CLASS, property = "@class")` 的版本信封,封装缓存值的版本/格式信息。序列化格式升级时,可据此识别旧版本数据,做兼容迁移或失效。

### JacksonConfig

`src/main/java/io/github/davidhlp/spring/cache/redis/config/JacksonConfig.java:21`

配置 `ObjectMapper`(注册 `JavaTimeModule` 等),为 [[serialization]] 的安全序列化器提供基础 mapper。

## 相关

- [[operations]] —— `RedisCacheRegister` 与 Holder 的分工
- [[auto-configuration]] —— 这些类的总装配入口
- [[annotations]] —— Holder 缓存的就是注解解析结果
- [[serialization]] —— `JacksonConfig` 提供的 `ObjectMapper`
