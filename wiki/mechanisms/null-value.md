---
title: 空值缓存(400)
type: mechanisms
tags:
  - mechanism
  - 空值缓存
  - 防穿透
  - NULL_VALUE
  - CachedValue
related: [cache-penetration, serialization, context-data-flow, cache-lifecycle]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/nullvalue/NullValueHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/nullvalue/DefaultNullValuePolicy.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/cache/CachedValue.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 空值缓存(HandlerOrder 400)

责任链**第五档**。当业务方法返回 `null`(查不到),把这个「null」也缓存一小段时间——下次同样的查询直接命中「空值占位」,不再回源。这是防 [[cache-penetration|缓存穿透]] 的第二道防线,与 [[bloom-filter|布隆过滤器]] 互补。

## 机制定位

不存在的 key 反复查询,若每次都回源,会持续打 DB。空值缓存用一个带短 TTL 的「占位值」记住「这个 key 确实没有」,短期内直接返回 null。

`NullValueHandler` 依赖 `DefaultNullValuePolicy` 决定如何包装/还原空值,并把值统一封装进 `CachedValue`。

## CachedValue:统一值包装

所有写入 Redis 的值都包成 `CachedValue`,承载元数据:

- `value` —— 真实业务值(或空值占位);
- `createdTime` —— 写入时间(配合 `Clock`);
- `ttl` —— 该项 TTL(秒);
- `getRemainingTtl()` —— 剩余 TTL;
- `checkExpired()` —— 是否已过期;
- 配合 `DefaultNullValuePolicy` 的 `toReturnValue(...)` 还原给上层。

这套元数据也是 [[early-expiration]] 判定「该不该提前刷新」的依据(读 `createdTime` + `ttl`)。

## DefaultNullValuePolicy

`DefaultNullValuePolicy`(`@Component` 具体类,C2 后接口已删)提供:

- **写入侧**(PUT):若业务值为 `null` 且开启 `cacheNullValues`,转成空值占位 `CachedValue`(短 TTL),写进 Redis;否则正常包装。
- **读取侧**(GET):`toReturnValue(value, cacheName, redisKey)` 把读出的 `CachedValue` 还原——若检测到是空值占位,返回业务 `null`(上层据此知道「不存在」而非「未命中」)。

`ActualCacheHandler.processCacheHit` 读路径就调它(见 [[cache-lifecycle]]):

```java
byte[] result = nullValuePolicy.toReturnValue(
    cachedValue.getValue(), context.getCacheName(), context.getRedisKey());
```

## 空值 TTL

空值通常用比正常值**更短**的 TTL——既挡住短时间内的重复穿透,又不至于让「曾经不存在」的数据长期占位(数据后来可能被创建)。具体短 TTL 由 policy / 配置决定。

## 与布隆过滤器的分工

两者都防穿透,但层次不同(见 [[cache-penetration]] 的对比):

| 机制 | 擅长 | 局限 |
|---|---|---|
| [[bloom-filter]](100) | 拦截「从未存在过」的 key,零 Redis 开销 | 有假阳性;重启/清空后需重新填充 |
| 空值缓存(400) | 记住「查过但当时没有」,挡住反复查同一个空 | 需先查过一次才能缓存空值 |

实战常组合:布隆挡掉绝大多数非法 key,空值缓存兜住漏网(假阳性或布隆未覆盖)的真实空查询。

## 安全反序列化

空值占位作为 `CachedValue` 经 [[serialization|SecureJackson2JsonRedisSerializer]] 序列化。`SecureNullValueDeserializer` 专门安全地处理空值占位的反序列化,避免恶意构造的空值 payload 触发类型攻击。

## 配置

注解级(`@RedisCacheable`):

```java
@RedisCacheable(value = "users", key = "#id",
    cacheNullValues = true)    // 开启空值缓存
```

per-cache 级(`resi-cache.caches.<name>`,见 [[configuration]]):

```yaml
resi-cache:
  caches:
    users:
      cache-null-values: true
```

> Spring `RedisCacheConfiguration` 层面也可 `disableCachingNullValues()` 强制关闭——`buildInitialCacheConfigurations` 会在 `cacheNullValues=false` 时调用它(见 [[auto-configuration]])。

## 相关

- [[cache-penetration]] —— 穿透定义与两道防线的对比
- [[bloom-filter]] —— 互补的第一道防线
- [[serialization]] —— `CachedValue` 的安全序列化 / `SecureNullValueDeserializer`
- [[cache-lifecycle]] —— `toReturnValue` 在读路径的调用
