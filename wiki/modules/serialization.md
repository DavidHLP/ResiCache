---
title: 安全序列化(serialization + SecureJackson)
type: modules
tags:
  - module
  - 序列化
  - Jackson
  - 白名单
  - NullValue
  - 反序列化攻击
related: [null-value, configuration, cache-core, auto-configuration]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/serialization/SecureJacksonRedisSerializer.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/serialization/SecureJacksonSerializerFactory.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/serialization/WhitelistPolicy.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/serialization/TypeSupport.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/serialization/SecureNullValueDeserializer.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/serialization/VersionEnvelope.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/serialization/SerializationException.java
status: stable
created: 2026-06-21
updated: 2026-06-29
---

# 安全序列化

缓存值要在 Redis 与 JVM 间往返。Jackson 默认配置存在**多态反序列化 RCE 风险**(攻击者构造 `@class` 指向危险类)。本包用白名单 + 受限 Java 序列化封堵这条攻击面。

## 五道防线 + 装配工厂

| 组件 | 职责 |
|---|---|
| `SecureJacksonRedisSerializer` | 主序列化器:白名单 + 类型属性控制 + 拒绝消息含 remediation hint |
| `SecureJacksonSerializerFactory` | 装配工厂:`@Component`,把 `RedisProCacheProperties.SerializerProperties` 5 字段 → 5-arg ctor 注入一处,避免装配点双轨漂移(R11 抽出) |
| `WhitelistPolicy` | 谓词:包前缀白名单匹配,支持 `.*` 通配后缀(R9) |
| `TypeSupport` | 类型转换工具:`CachedValue`↔bytes、NullValue 安全往返 |
| `SecureNullValueDeserializer` | 专处理 Spring `NullValue` 的受限反序列化 |
| `VersionEnvelope` | `{version, payload}` 线格式包络(版本迁移用,见 STABILITY §3) |
| `SerializationException` | 反序列化失败的统一异常类型 |

> 装配入口走 `SecureJacksonSerializerFactory#create(ObjectMapper, SerializerProperties)` 单方法,两处生产装配点(`RedisConnectionConfiguration#redisCacheTemplate` + `RedisProCacheConfiguration#defaultRedisCacheConfiguration`)共用。**不要直接 `new SecureJacksonRedisSerializer(objectMapper)`**——5-arg ctor 镜像多装配点是 R5 修过又会被 R11 重引入的 footgun(R5 修 wired/unwired bug 时发现,R11 抽出 factory 兜底)。

## SecureJacksonRedisSerializer

在装配时由 `RedisProCacheProperties.serializer` 配置(见 [[configuration]]):

- `allowedPackagePrefixes`(默认 `[io.github.davidhlp]`)—— 仅这些包下的类型允许反序列化,其余拒绝。**通配**:R9 起 `com.example.*` 匹配 `com.example.Foo` 与任意深度的 `com.example.sub.bar.Qux`(`WhitelistPolicy.matchesPrefix` dot-boundary 保护);
- `failOnUnknownType`(默认 `true`)—— 遇到白名单外类型时**抛 SerializationException**(不是降级);降级到 miss 由 [[cache-lifecycle]] 错误处理完成;
- `typeProperty`(默认 `@class`)—— 类型判定属性名;
- `polymorphicTypingEnabled`(默认 `false`)—— 是否启用多态类型(关闭则不写入/不读取 `@class`,彻底杜绝攻击但失去多态)。

**拒绝消息**(R3 起的 remediation hint):

> Type `com.foo.Bar` is not in deserialization whitelist. Add its package to `resi-cache.serializer.allowed-package-prefixes`.

——消息尾附 property key,降低"踩坑找不到线索"的高频 friction。

## WhitelistPolicy(R9 起)

包前缀谓词,`isClassNameAllowed(className, prefixes)` 公开方法,内部 `matchesPrefix(className, prefix)` 私有 helper(R9 加):

- `prefix` 末尾 `.*` → strip 后要求 `className.equals(pkg) || className.startsWith(pkg + ".")`(dot-boundary 保护)
- 其他形式 → literal `String.startsWith`(pre-existing 行为,无 dot-boundary)
- `null` / empty 列表 → 一律 `false`

> ⚠️ literal prefix 没有 dot-boundary 是 **intentional**:既有用户可能配置 `com.example` literal 形式并依赖它匹配 `com.exampleX.Foo`(虽然是 bug-friendly 行为);引入 dot-boundary 是 BREAKING(候选 4 仍 deferred)。wildcard 路径**有** dot-boundary 是 R9 设计选择。

## SecureJacksonSerializerFactory(R11 抽出)

`@Component`,唯一公开方法 `SecureJacksonRedisSerializer create(ObjectMapper, SerializerProperties)`,把 5 字段 `allowedPackagePrefixes / failOnUnknownType / typeProperty / polymorphicTypingEnabled / polymorphicTypingEnabled` 一次性映射到 5-arg ctor。**R5 + R11 合同**(回归测试守护):factory 默默用默认而忽略 properties → 立即被 `SecureJacksonSerializerFactoryTest` 的 negative-wiring 守护测试捕获(显式设 `[com.example.round11]` + roundtrip `CachedValue` → 必抛 SerializationException)。

不参与生产装配的 `@TestConfiguration`(`TestRedisConfiguration#redisCacheTemplate`)是 intentional 镜像,不接入 factory——test config 在 Spring 组件扫描外,接入反而 more wiring,net 更复杂。

## NullValue 的特殊难题

Spring 的 `NullValue`(标记「缓存了空值」)是 **final 类 + 私有构造 + `readResolve` 单例**——**无法被 Jackson JSON 化**,只能走 Java 原生序列化往返。而原生 Java 反序列化正是 RCE 重灾区。

`TypeSupport.serializeToBytes` 的处理:

```java
public byte[] serializeToBytes(Object value) {
    if (value instanceof NullValue) {
        return SecureNullValueDeserializer.serializeNullValue();   // 受限 Java 序列化
    }
    return objectMapper.writeValueAsBytes(value);                  // 普通对象走 JSON
}
```

## SecureNullValueDeserializer

不开放任意类反序列化,而是用 `resolveClass` **白名单仅允许 `NullValue` 一个类**——既支持往返,又杜绝任意类反序列化的 RCE。`serializeNullValue()` / 对应反序列化方法成对使用,见 [[null-value]]。

## CachedValue 的类型标记

`CachedValue` 带 `@JsonTypeInfo(use = Id.CLASS, property = "@class")`(见 [[cache-core]]),序列化时写入实际类型。`SecureJacksonRedisSerializer` 的白名单决定这个 `@class` 能否被接受——攻击者即便伪造 `@class` 指向危险类,也会被白名单拦下。

## VersionEnvelope(STABILITY §3 线格式)

`{version, payload}` 包络,版本字段让 0.0.x → 0.1.x 升级时旧数据能被识别。`STABILITY.md` §3 把 `{version, payload}` 列为 **never-change** wire format——这是 contract 承诺,不是实现细节。

## SerializationException

反序列化失败的统一异常类型,由 [[cache-lifecycle]] 的错误处理捕获,降级为 miss 而非向上抛穿业务。

## 相关

- [[null-value]] —— `NullValue` 占位与还原
- [[configuration]] —— `serializer.*` 子配置 + `SerializerWhitelistStartupGuard`(R15 启动期守卫)
- [[cache-core]] —— `CachedValue` 包装
- [[auto-configuration]] —— 序列化器 bean 装配
