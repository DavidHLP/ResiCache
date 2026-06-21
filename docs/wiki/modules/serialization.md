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
  - src/main/java/io/github/davidhlp/spring/cache/redis/serialization/TypeSupport.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/serialization/SecureNullValueDeserializer.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 安全序列化

缓存值要在 Redis 与 JVM 间往返。Jackson 默认配置存在**多态反序列化 RCE 风险**(攻击者构造 `@class` 指向危险类)。本包用白名单 + 受限 Java 序列化封堵这条攻击面。

## 三道防线

| 组件 | 职责 |
|---|---|
| `SecureJackson2JsonRedisSerializer` | 主序列化器:白名单 + 类型属性控制 |
| `TypeSupport` | 类型转换工具:`CachedValue`↔bytes、NullValue 安全往返 |
| `SecureNullValueDeserializer` | 专处理 Spring `NullValue` 的受限反序列化 |

## SecureJackson2JsonRedisSerializer

在 `defaultRedisCacheConfiguration` 装配时由 `RedisProCacheProperties.serializer` 配置(见 [[configuration]]):

- `allowedPackagePrefixes`(默认 `io.github.davidhlp`)—— 仅这些包下的类型允许反序列化,其余拒绝;
- `failOnUnknownType` —— 遇到白名单外类型时是否抛错(默认 false,降级处理);
- `typeProperty`(默认 `@class`)—— 类型判定属性名;
- `polymorphicTypingEnabled` —— 是否启用多态类型(关闭则不写入/不读取 `@class`,彻底杜绝攻击但失去多态)。

## NullValue 的特殊难题

Spring 的 `NullValue`(标记「缓存了空值」)是 **final 类 + 私有构造 + `readResolve` 单例**——**无法被 Jackson JSON 化**,只能走 Java 原生序列化往返。而原生 Java 反序列化正是 RCE 重灾区。

`TypeSupport.serializeToBytes` 的处理(`src/main/java/io/github/davidhlp/spring/cache/redis/serialization/TypeSupport.java`):

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

`CachedValue` 带 `@JsonTypeInfo(use = Id.CLASS, property = "@class")`(见 [[cache-core]]),序列化时写入实际类型。`SecureJackson2JsonRedisSerializer` 的白名单决定这个 `@class` 能否被接受——攻击者即便伪造 `@class` 指向危险类,也会被白名单拦下。

## SerializationException

反序列化失败的统一异常类型,由 [[cache-lifecycle]] 的错误处理捕获,降级为 miss 而非向上抛穿业务。

## 相关

- [[null-value]] —— `NullValue` 占位与还原
- [[configuration]] —— `serializer.*` 子配置
- [[cache-core]] —— `CachedValue` 包装
- [[auto-configuration]] —— 序列化器 bean 装配
