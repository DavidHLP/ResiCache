---
title: "ADR-0030: RedisProCacheWriter.getTtl/getExpiration 两个死 protected 方法删除"
type: adr
status: accepted
date: 2026-07-03
deciders: DavidHLP
related:
  - ADR-0026
tags:
  - dead-code-removal
  - deletion-test
  - cache
  - round-21
---

# ADR-0030: RedisProCacheWriter.getTtl/getExpiration 两个死 protected 方法删除

## 状态

Accepted — 2026-07-03。

## 背景

`/improve-codebase-architecture` round 21 autocratic one-shot 扫描 `cache/` 域,在
`RedisProCacheWriter`(395 行,责任链入口 / Spring `RedisCacheWriter` 实现)末尾发现两个
**未被任何前轮 ADR(0009~0029)触及**的死代码:

```java
// 以下方法用于向后兼容，如果有其他地方调用
protected long getTtl(String redisKey) {
    Object value = valueOperations.get(redisKey);
    if (value instanceof CachedValue cachedValue) {
        return cachedValue.getTtl();
    }
    return -1;
}

protected long getExpiration(String redisKey) {
    return redisTemplate.getExpire(redisKey);
}
```

注释自承「用于向后兼容，如果有其他地方调用」——作者本人在引入时已不确定是否存在调用方。

**核实(2026-07-03,基于 working tree)**:

1. **`.getExpiration(` 全项目(main + test)零调用** —— `grep -rn '\.getExpiration(' src/`
   返回空。
2. **`.getTtl(String)` 零调用** —— `grep -rn '\.getTtl(' src/` 的全部命中均属**别的类的同名
   getter**:`RedisProCacheConfiguration.cacheConfig.getTtl()`、`CachedValue.getTtl()`、
   `CacheContext.getTtl()`、`cacheOperation.getTtl()`、测试中的 `operation.getTtl()` /
   `ctx.getTtl()` / `ac.getTtl()`;**无一**是 `redisProCacheWriter.getTtl(redisKey)`
   (即接收 `String redisKey` 参数的那个)。
3. **零子类** —— `grep -rn 'extends RedisProCacheWriter' src/` 返回空。`protected` 可见性
   在无子类时等价于 `private`,不可能被外部反射访问。
4. **零反射调用** —— `grep -rn '"getTtl"\|"getExpiration"\|getMethod("get' src/` 的命中
   均无关(`@DisplayName("getTtl")` 测的是 `RedisCacheableOperation.getTtl()`;
   `getMethod("getById"...)` 是测试服务类方法)。
5. **模块契约无记载** —— `wiki/modules/cache-core.md` 的 RedisProCacheWriter 接口描述
   未把这两个 accessor 列为契约。

## 决策

**删除两个 protected 方法 + 注释行**(净删 13 SLOC)。

理由:dead code,deletion test 干净通过 —— 删了复杂度直接消失,不在 N 处重现(零调用 +
零子类 + 零反射 + 契约无记载)。与 [[0026-round14-contextbuilder-deletion-foreachsafe-and-sealings]]
D3(删 `AttributeKey.CACHE_HIT` / `ASYNC_REFRESH_TASK_ID` 2 个死常量)同款「死代码清理」,
但本处未被任何前轮 ADR 触及,本轮新发现。

## 后果

**增益**:

- `RedisProCacheWriter` 接口更窄:消除两个「看似可调实则无人调」的 protected accessor,
  降低未来维护者误用风险(误以为它们是活契约)。
- 注释「如果有其他地方调用」的自我怀疑消除 —— 代码诚实化。
- 编译期保证:`test-compile` 通过即零引用的铁证。

**代价**:零(零行为变化、零 API 变化、零测试调整)。

**不变**:

- `RedisProCacheWriter` 的 public 契约(`RedisCacheWriter` 接口的 get / put / putIfAbsent /
  remove / evict / clean / clear / retrieve / store / clearStatistics /
  withStatisticsCollector / getCacheStatistics / supportsAsyncRetrieve)完全不变。
- 责任链入口路径完全不变。
- TTL / 过期判定路径完全不变(由 `CachedValue.getTtl()` / `CachedValue.Expiry` /
  `TtlHandler` / `EarlyExpirationHandler` 承担,与这两个方法无关)。

## 相关

- [[0026-round14-contextbuilder-deletion-foreachsafe-and-sealings]] —— D3 删 `AttributeKey`
  2 个死常量(本 ADR 同款死代码清理模式)
- [[0009-chain-engine-extraction]] —— ChainEngine 承担推进 + 观测(与 Writer 的 TTL/过期无关)
