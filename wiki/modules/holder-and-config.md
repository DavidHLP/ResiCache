---
title: 方法元数据解析与配置装配辅助(metadata resolver + config 辅助类)
type: modules
tags:
  - module
  - MethodMetadataResolver
  - 配置装配
  - VersionEnvelope
  - CachingEnablementValidation
related: [operations, auto-configuration, annotations, 0002-keep-interceptor]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/MethodMetadataResolver.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/DefaultMethodMetadataResolver.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProxyCachingConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisCacheRegistryConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/CachingEnablementValidation.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/VersionEnvelope.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/JacksonConfig.java
status: stable
created: 2026-06-21
updated: 2026-06-29
---

# 方法元数据解析与配置装配辅助

两个散落但重要的辅助:`chain` 包的 `MethodMetadataResolver` 持有"当前 AOP 拦截方法"的元数据;`config` 包除 `RedisProCacheProperties`/`SecureJackson`/`RedisProCacheConfiguration` 外的几个装配类。

> **Path C(WS-1.3)重构**:本页原描述的 `holder/CacheOperationMetadataHolder`(静态 ThreadLocal 工具类)已于 Path C Step 7 删除,方法元数据的持有与访问职责迁到 Spring 托管的 `MethodMetadataResolver` Bean。决策演进见 [[0002-keep-interceptor]]。

## MethodMetadataResolver

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/MethodMetadataResolver.java`

`MethodMetadataResolver` 接口 + `DefaultMethodMetadataResolver`(`@Component`)封装"当前 AOP 拦截的缓存方法元数据"的访问。拦截器 `ResiCacheMethodInterceptor`(继承 `RedisCacheInterceptor`,见 [[cache-core]])在 `invoke` 时激活当前方法的 `AnnotatedElementKey`(method + targetClass);责任链与 `RedisProCacheWriter`(`buildContext()`/`lookupOperation()`)通过注入的 resolver 读 `currentMethod()`/`currentTargetClass()`/`currentKey()`,避免每次调用重复解析注解。

**数据所有权(Path C Step 7 后)**:ThreadLocal 从原静态工具类 `CacheOperationMetadataHolder` 迁到 `DefaultMethodMetadataResolver` 的静态字段 `CURRENT_KEY`(Spring 单例 Bean,所有实例共享同一存储)。写入 API(`activateStatic`/`clearStatic`)保持静态 —— 调用方(`RedisProCacheWriter`、拦截器、tests)在作用域内不一定持有 resolver 引用,静态 API 降低耦合;读取 API(`currentKey`/`currentMethod`/`currentTargetClass`/`currentContext`)为实例方法 —— 调用方持有 resolver 引用直接调。`finally` 中 `clearStatic()` 防 commonPool 线程复用导致 ThreadLocal 跨任务泄漏。

**为何抽接口**:把 ThreadLocal 依赖从静态类换到 Spring 托管 Bean,使后续可替换为 `ScopedValue`/TTL-aware snapshot 等非 ThreadLocal 实现(异步透传已通过 `currentContext()` 的 snapshot/restore 在 Step 6 落地),而不污染调用点。与 [[operations]] 的 `RedisCacheRegister`(按缓存名)互补:Resolver 是方法粒度,Register 是缓存名粒度。

## config 包辅助装配类

> 主装配见 [[auto-configuration]],序列化见 [[serialization]],配置项见 [[configuration]]。本页讲其余几个。

### RedisProxyCachingConfiguration

`src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProxyCachingConfiguration.java`

装配 `RedisCacheOperationSource`(常量 `REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME = "redisCacheOperationSource"`)等代理缓存基础设施,把 ResiCache 的注解源接到 Spring Cache 拦截器体系;Path C Step 5 后 advisor 的 advice 持有者为 `ResiCacheMethodInterceptor`(继承 `RedisCacheInterceptor`)。

### RedisCacheRegistryConfiguration

装配 `RedisCacheRegister`([[operations]])及注册相关的 bean。

### CachingEnablementValidation

`src/main/java/io/github/davidhlp/spring/cache/redis/config/CachingEnablementValidation.java`

启动期校验缓存配置合法性,内含 `CachingEnabledValidator`(`@Validated` 配套的手写校验器)。例如 `@EnableCaching` 缺失、注解属性组合矛盾时 fail-fast,而非运行时才暴露。

### VersionEnvelope

`src/main/java/io/github/davidhlp/spring/cache/redis/config/VersionEnvelope.java`

带 `@JsonTypeInfo(use = Id.CLASS, property = "@class")` 的版本信封,封装缓存值的版本/格式信息。序列化格式升级时,可据此识别旧版本数据,做兼容迁移或失效。

### JacksonConfig

`src/main/java/io/github/davidhlp/spring/cache/redis/config/JacksonConfig.java`

配置 `ObjectMapper`(注册 `JavaTimeModule` 等),为 [[serialization]] 的安全序列化器提供基础 mapper。

## 相关

- [[operations]] —— `RedisCacheRegister` 与 Resolver 的分工
- [[auto-configuration]] —— 这些类的总装配入口
- [[annotations]] —— Resolver 激活的元数据来自注解解析
- [[0002-keep-interceptor]] —— Path C "经 MethodMetadataResolver 解决"的决策
- [[serialization]] —— `JacksonConfig` 提供的 `ObjectMapper`
