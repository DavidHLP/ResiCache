---
title: Spring Boot 自动装配
type: architecture
tags:
  - architecture
  - auto-configuration
  - starter
  - ConditionalOnMissingBean
  - 装配链
related: [configuration, cache-core, chain-of-responsibility, serialization, observability]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisCacheAutoConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisConnectionConfiguration.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/CachingEnablementValidation.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/serialization/SecureJacksonSerializerFactory.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/SerializerWhitelistStartupGuard.java
status: stable
created: 2026-06-21
updated: 2026-06-29
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
| `SELECTIVE`(默认) | 仅当同方法上也有 ResiCache 注解时才转换 — **避免双 Advisor**,选 DEFAULT 是因为大多数用户已在用 ResiCache 注解,Spring 原生注解是 ad-hoc 场景 |
| `FULL` | 转换所有 Spring 原生注解,无缝兼容老代码 — 旧项目零改动迁移路径 |
| `NONE` | 忽略原生注解,只认 ResiCache 注解 — 明确切到 ResiCache-only,场景为性能敏感(strip 转换开销) |

> ⚠️ wiki 历史曾把 `FULL` 列为默认;实际代码 `RedisProCacheProperties.nativeAnnotationMode = NativeAnnotationMode.SELECTIVE`(R2 之后),这是「避免双 Advisor」的策略选择。R5 IT 集成测试 + 全 `verify` 跑通,SELECTIVE 为默认无回归。

这让现有 `@Cacheable` 代码(走 `FULL` 模式)零改动迁移到 ResiCache。

## 版本信封:VersionEnvelope

`VersionEnvelope` 封装版本信息(如序列化格式版本),用于缓存值的兼容性判断——当序列化格式升级时,可据此识别旧版本数据。

## 覆盖默认实现的三种方式

1. **换 bean** —— 自定义 `RedisProCacheManager` / `RedisCacheConfiguration` 等同类型 `@Bean`,`@ConditionalOnMissingBean` 让你的优先。
2. **换策略实现** —— `BloomIFilter`(三实现)与 `LockManager` 都是普通 `@Component`;实现接口并声明 `@Bean`,配合 `@ConditionalOnMissingBean` 顶替默认实现(注:框架未用 Java ServiceLoader)。
3. **加 handler** —— 实现 `CacheHandler` + `@HandlerPriority`,自动并入链(见 [[add-protection-handler]])。

## 序列化装配(避免 wired/unwired 双轨 bug)

`SecureJacksonSerializerFactory`(`@Component`,R11 抽出)是 `defaultRedisCacheConfiguration` 与 `RedisConnectionConfiguration#redisCacheTemplate` **两处** 装配点的唯一入口 — 一行 `factory.create(objectMapper, properties.getSerializer())` 把 `RedisProCacheProperties.SerializerProperties` 5 字段(`allowedPackagePrefixes / failOnUnknownType / typeProperty / polymorphicTypingEnabled`)一次性映射到 `SecureJacksonRedisSerializer` 5-arg ctor。

**不要直接 `new SecureJacksonRedisSerializer(objectMapper)`** — 那是 R5 修过的 wired/unwired 双轨 bug 的根源:5-arg ctor 镜像在多装配点是维护陷阱,任何 ctor 签名变更会默默 break 其中一处。`SecureJacksonSerializerFactory` 收口,详见 [[serialization]]。

## 启动期守卫(SerializerWhitelistStartupGuard,R15)

`SerializerWhitelistStartupGuard`(`@Component`,R15)是独立于主装配链的**纯旁路守卫**:监听 `ApplicationReadyEvent`,检查 `resi-cache.serializer.allowed-package-prefixes` 是否为 `null` 或 `[]`,若空则发 WARN 日志。**不在装配链上** — 它的存在是为了在「白名单被错误清空」这个 misconfig 的 runtime `SerializationException` 发生**之前**,在 startup 日志里就给用户 hint。

谓词 `shouldWarn()` package-private 供单元测试,集成路径(实际事件投递 + log emission)由 `SerializerWhitelistStartupGuardIntegrationTest`(R20)守护。详见 [[configuration]] 与 [[serialization]]。

> 这是 ResiCache 第二个 startup 守卫;另一个是 `SyncLockProperties.localOnly` 启动期告警(在 `RedisProCacheConfiguration` 装配时,见 [[configuration]])。两者各自独立、各自防御一个 misconfig footgun,详见 [[observability]]。

## 相关

- [[configuration]] —— `RedisProCacheProperties` 全量配置项 + 启动期守卫上下文
- [[cache-core]] —— 装配出的 `RedisProCache*` 三件套
- [[chain-of-responsibility]] —— handler 如何被收集装配
- [[serialization]] —— `defaultRedisCacheConfiguration` 用的安全序列化器 + `SecureJacksonSerializerFactory` 装配工厂
- [[observability]] —— 两条 startup 守卫的 misconfig 防御设计哲学
