---
title: Spring Boot 自动装配
type: architecture
tags:
  - architecture
  - auto-configuration
  - starter
  - ConditionalOnMissingBean
  - 装配链
related: [configuration, cache-core, chain-of-responsibility]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisCacheAutoConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisConnectionConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/CachingEnablementValidation.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# Spring Boot 自动装配

ResiCache 是一个 Spring Boot Cache 增强 starter——引入依赖、配好 Redis 连接,**无需任何 `@EnableXxx`** 即生效。本页解释自动装配的入口、bean 装配链,以及如何覆盖默认实现。

## 入口:AutoConfiguration.imports

Spring Boot 3 的自动装配靠 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 登记。ResiCache 的入口是 `RedisCacheAutoConfiguration`,它引导整套装配。用户工程只要满足:

- classpath 有 `ResiCache` 依赖 + `spring-boot-starter-data-redis`;
- `application.yml` 里 `spring.data.redis.*` 配好连接;

ResiCache 的 `RedisProCacheManager` 就会顶替默认的 `RedisCacheManager`。

## 装配链

```
RedisCacheAutoConfiguration  (入口 / 导入 / 校验)
        │
        ├── RedisConnectionConfiguration   ── RedisTemplate / 连接工厂
        │
        └── RedisProCacheConfiguration     ── 核心装配(@Configuration)
                ├── RedisProCacheWriter        (责任链入口,持 cachedChain)
                ├── defaultRedisCacheConfiguration  (TTL/前缀/序列化器)
                ├── RedisProCacheManager        (顶替默认 CacheManager)
                └── KeyGenerator               (SimpleKeyGenerator)
```

`RedisProCacheConfiguration` 是主装配类(`@Configuration` + `@ComponentScan("...redis")` + `@EnableConfigurationProperties(RedisProCacheProperties)`),它:

1. 扫描 `io.github.davidhlp.spring.cache.redis` 下所有 `@Component`(含 6 个 handler、各 `Support`、SPI 实现),交给 Spring 容器。
2. 由 `CacheHandlerChainFactory` 收集带 `@HandlerPriority` 的 handler,装配成责任链。
3. 产出 `RedisProCacheWriter`、`RedisCacheConfiguration`、`RedisProCacheManager` 等 bean。

## 核心 bean(均可覆盖)

每个 bean 都标了 `@ConditionalOnMissingBean`,用户可自定义同类型 bean 顶替:

`src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheConfiguration.java:84`

```java
@Bean
@ConditionalOnMissingBean(CacheManager.class)
public RedisProCacheManager cacheManager(
        RedisProCacheWriter redisProCacheWriter,
        RedisCacheConfiguration defaultRedisCacheConfiguration,
        ObjectProvider<MeterRegistry> meterRegistryProvider,
        BloomSupport bloomSupport,
        RedisCacheRegister redisCacheRegister,
        SyncSupport syncSupport,
        RedisProCacheProperties properties,
        ObjectMapper objectMapper) {
    Map<String, RedisCacheConfiguration> initial = buildInitialCacheConfigurations(...);
    return new RedisProCacheManager(redisProCacheWriter, defaultRedisCacheConfiguration,
            meterRegistryProvider.getIfAvailable(), bloomSupport,
            redisCacheRegister, syncSupport, initial, properties.isTransactionAware());
}
```

- **`RedisProCacheManager`** 继承 Spring `RedisCacheManager`,`createRedisCache` / `getMissingCache` 都产出 `RedisProCache`(而非原生 `RedisCache`),见 [[cache-core]]。
- **`defaultRedisCacheConfiguration`** 应用 `resi-cache.default-ttl`、`key-prefix`、[[serialization]] 的安全序列化器。
- **`MeterRegistry`** 用 `ObjectProvider.getIfAvailable()`,actuator 不在时优雅降级(指标关闭),见 [[observability]]。

## per-cache 配置构建

`buildInitialCacheConfigurations(...)` 读 `resi-cache.caches.<name>`(见 [[configuration]]),为每个具名缓存生成独立的 `RedisCacheConfiguration`(覆盖 TTL、keyPrefix、cacheNullValues)。这层 per-cache 配置优先于全局默认。

## 启用校验:CachingEnablementValidation

`CachingEnablementValidation` 在启动期校验缓存注解的合法性(如 `@RedisCacheable` 的属性组合是否自洽),配置错误时 fail-fast,而不是运行时才暴露。

## 原生注解兼容:native-annotation-mode

`resi-cache.native-annotation-mode` 控制 ResiCache 如何对待 Spring 原生缓存注解(`@Cacheable` / `@CachePut` / `@CacheEvict`):

| 模式 | 行为 |
|---|---|
| `FULL`(默认) | 转换所有 Spring 原生注解,无缝兼容老代码 |
| `NONE` | 忽略原生注解,只认 ResiCache 注解 |
| `SELECTIVE` | 仅当同方法上也有 ResiCache 注解时才转换 |

这让现有 `@Cacheable` 代码零改动迁移到 ResiCache。

## 版本信封:VersionEnvelope

`VersionEnvelope` 封装版本信息(如序列化格式版本),用于缓存值的兼容性判断——当序列化格式升级时,可据此识别旧版本数据。

## 覆盖默认实现的三种方式

1. **换 bean** —— 自定义 `RedisProCacheManager` / `RedisCacheConfiguration` 等同类型 `@Bean`,`@ConditionalOnMissingBean` 让你的优先。
2. **换策略实现** —— `BloomIFilter`(三实现)与 `LockManager` 都是普通 `@Component`;实现接口并声明 `@Bean`,配合 `@ConditionalOnMissingBean` 顶替默认实现(注:框架未用 Java ServiceLoader)。
3. **加 handler** —— 实现 `CacheHandler` + `@HandlerPriority`,自动并入链(见 [[add-protection-handler]])。

## 相关

- [[configuration]] —— `RedisProCacheProperties` 全量配置项
- [[cache-core]] —— 装配出的 `RedisProCache*` 三件套
- [[chain-of-responsibility]] —— handler 如何被收集装配
- [[serialization]] —— `defaultRedisCacheConfiguration` 用的安全序列化器
