---
title: "ADR-0021: RedisCacheAttributes.applyTo(B) seam (3 fromAttributes 22-line 重复墙收敛) + ProtectionToggle Function 化 (4 disabled-handler if-block 收敛)"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0017
  - ADR-0019
tags:
  - operation
  - factory
  - chain
  - seam
  - deepening
  - round-13
  - field-mapping
  - function-binding
---

# ADR-0021: RedisCacheAttributes.applyTo(B) seam + ProtectionToggle Function 化

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0017 (Operation.fromAttributes 静态 seam) / ADR-0019 (Projector FieldSource seam) / round 13 报告

## 背景

`/improve-codebase-architecture` round 13 autocratic one-shot 扫描 `operation/` + `chain/` 两域,
基于 round 1–12 已落地 ADR-0009/0010/.../0020 状态,筛出 5 候选:

| 候选 | 评级 | 裁决 |
| --- | --- | --- |
| A · `RedisCacheAttributes.applyTo(B)` seam (3 fromAttributes 22-line 重复墙收敛) | Strong | **执行** |
| B · CacheHandlerChainFactory 4 disabled-handler if-block → ProtectionToggle 列表迭代 | Worth | **执行** (本轮顺带) |
| C · 3 Operation Builder 类 setter 模板 | Speculative | **不动** (Java 单继承;setter 2-liner;YAGNI) |
| D · 5 个 ChainObserver DRY | Speculative | **不动** (ADR-0019 C 已显式封口) |
| E · SyncSupport 234 SLOC | Speculative | **不动** (LockStack/MonitorHolder 形态正交;行为正确性优先;YAGNI) |

全文精读核实后,逐条裁决如下。

## 决策

### D1 — `RedisCacheAttributes.applyTo(B)` seam (候选 A,执行)

**问题陈述**:3 个 Operation 类的 `fromAttributes(method, key, attributes)` 静态工厂中,22 字段的
builder 链重复书写 3 遍:

- `RedisCacheableOperation.fromAttributes`(22 lines,22 builder calls)
- `RedisCachePutOperation.fromAttributes`(22 lines,**byte-for-byte 与 Cacheable 一致**仅 `expectedInsertions` 走 `long` 直传 vs `int` 窄化)
- `RedisCacheEvictOperation.fromAttributes`(14 字段子集 + 2 Evict-only 字段)

字段映射知识本应属于 `RedisCacheAttributes`(它拥有这些字段的语义),却散落在 3 个 Operation 类中 —
违反"字段映射与字段同源"原则。

```java
// ADR-0017 残留 (Cacheable 与 Put 21/22 字段 byte-for-byte 一致,仅 1 行不同)
public static RedisCacheableOperation fromAttributes(Method method, String key, RedisCacheAttributes a) {
    Builder b = builder();
    b.name(method.getName())
            .key(key)
            .cacheNames(a.getCacheNames())
            .keyGenerator(a.getKeyGenerator())
            .cacheManager(a.getCacheManager())
            .cacheResolver(a.getCacheResolver())
            .condition(a.getCondition())
            .unless(a.getUnless())
            .ttl(a.getTtl())
            .type(a.getType())
            .cacheNullValues(a.isCacheNullValues())
            .useBloomFilter(a.isUseBloomFilter())
            .expectedInsertions((int) Math.min(Integer.MAX_VALUE, Math.max(0L, a.getExpectedInsertions())))
            .falseProbability(a.getFalseProbability())
            .randomTtl(a.isRandomTtl())
            .variance(a.getVariance())
            .enableEarlyExpiration(a.isEnableEarlyExpiration())
            .earlyExpirationThreshold(a.getEarlyExpirationThreshold())
            .earlyExpirationMode(a.getEarlyExpirationMode())
            .sync(a.isSync())
            .syncTimeout(a.getSyncTimeout());
    return b.build();
}
```

**deletion test**:
- 删 3 个 `fromAttributes` 中的 builder 链 → 3 个 Operation 无法从 attributes 投影构造(契约破坏)
- 删 3 个 `applyTo(B)` 重载 → 3 个 fromAttributes 恢复 22 行 builder 链(重复墙回归)

**形态对比**:
- 重复墙:3 文件 × 22 行 builder 链(byte-for-byte 复制),新加字段触点 6(3 fromAttributes + 3 applyTo)
- 模板版:1 文件(RedisCacheAttributes)持 3 个 `applyTo(B)` 重载,3 个 fromAttributes 退化为 1 行
  委派,新加字段触点 3(RedisCacheAttributes 1 处 + 3 applyTo 1 行更新)

**决策**:在 `RedisCacheAttributes` 上新增 3 个 `applyTo(B)` 重载(Cacheable/Put/Evict),把字段映射
完全下放到字段拥有者;3 个 `fromAttributes` 退化为单行委派:

```java
// RedisCacheAttributes (字段拥有者,持有 22 字段语义)
public RedisCacheableOperation.Builder applyTo(RedisCacheableOperation.Builder b) {
    return b
            .cacheNames(cacheNames)
            .keyGenerator(keyGenerator)
            .cacheManager(cacheManager)
            .cacheResolver(cacheResolver)
            .condition(condition)
            .unless(unless)
            .ttl(ttl)
            .type(type)
            .cacheNullValues(cacheNullValues)
            .useBloomFilter(useBloomFilter)
            .expectedInsertions(narrowToInt(expectedInsertions))  // int 槽位 + 防御性裁剪
            .falseProbability(falseProbability)
            .randomTtl(randomTtl)
            .variance(variance)
            .enableEarlyExpiration(enableEarlyExpiration)
            .earlyExpirationThreshold(earlyExpirationThreshold)
            .earlyExpirationMode(earlyExpirationMode)
            .sync(sync)
            .syncTimeout(syncTimeout);
}

// RedisCacheableOperation.fromAttributes (1 行 委派)
public static RedisCacheableOperation fromAttributes(
        java.lang.reflect.Method method, String key, RedisCacheAttributes a) {
    return a.applyTo(builder().name(method.getName()).key(key)).build();
}
```

**`expectedInsertions` 窄化契约保留**:`narrowToInt(long v)` helper 抽取原表达式
`(int) Math.min(Integer.MAX_VALUE, Math.max(0L, a.getExpectedInsertions()))` byte-for-byte
(OperationFromAttributesTest 现有断言覆盖;Put/Evict Builder 的 `expectedInsertions` 是 `long` 槽位,
不走本 helper)。

### D2 — `ProtectionToggle` record + Function 化 (候选 B,执行)

**问题陈述**:`CacheHandlerChainFactory.createChain` 中 per-mechanism 4 个 if-block 是
"if FALSE → add to disabled + log" 模板的 4 次复制:

```java
// ADR-0019 D 显式延后本候选(触发条件 = 新增第 5 个 protection 机制);本轮视为"leverage 仍然真实"
if (Boolean.FALSE.equals(protection.getBloomFilterEnabled())) {
    disabled.add(HandlerOrder.BLOOM_FILTER.getDisableName());
    log.info("Bloom filter disabled by resi-cache.protection.bloom-filter.enabled=false");
}
if (Boolean.FALSE.equals(protection.getSyncLockEnabled())) {
    disabled.add(HandlerOrder.SYNC_LOCK.getDisableName());
    log.info("Sync lock disabled by resi-cache.protection.sync-lock.enabled=false");
}
if (Boolean.FALSE.equals(protection.getEarlyExpirationEnabled())) {
    disabled.add(HandlerOrder.EARLY_EXPIRATION.getDisableName());
    log.info("Early expiration disabled by resi-cache.protection.early-expiration.enabled=false");
}
if (Boolean.FALSE.equals(protection.getNullValueEnabled())) {
    disabled.add(HandlerOrder.NULL_VALUE.getDisableName());
    log.info("Null value disabled by resi-cache.protection.null-value.enabled=false");
}
```

**CR 演化**(自我攻击 → 修复):

1. **初版设计**:`ProtectionToggle` record + 静态 `protectionHolder` 字段 + `getEnabledFor` switch。
   - **问题 1**:静态 `protectionHolder` 跨 Spring context 泄漏(测试场景下导致"上一 context 的
     protection 影响下一 context")。
   - **问题 2**:`getEnabledFor` switch + PROTECTION_TOGGLES 列表是<em>平行同源</em>定义 —
     新加 HandlerOrder 需同步改 2 处,drift 风险(漏 case 时 switch `default -> null` 静默短路)。
   - **修复**:**Function 化** — `ProtectionToggle` record 持有 `Function<ProtectionProperties, Boolean>`
     getter 字段(用方法引用直接绑定到 `ProtectionProperties::getXxxEnabled`),
     `getEnabledFor` switch 整个删除,`collectPerMechanismDisables` 直接 `toggle.getter().apply(protection)`。
   - **优势**:getter 是 record 字段,与 PROTECTION_TOGGLES 列表原子绑定;不可能 drift;无静态状态。
   - **契约**:null 仍表示"继承 enabled"(per-mechanism 字段未设),`Boolean.FALSE.equals(null) → false`
     不触发短路 — 与原 4 if-block 行为 byte-for-byte 一致。

**决策**:

```java
// 本类内嵌套 record(3 字段:order + Function getter + configPath)
private record ProtectionToggle(
        HandlerOrder order,
        Function<RedisProCacheProperties.ProtectionProperties, Boolean> getter,
        String configPath) {
}

// 4 个 protection 机制 toggle 单一事实源 — 加第 5 机制时<strong>仅追加一行</strong>
private static final List<ProtectionToggle> PROTECTION_TOGGLES = List.of(
        new ProtectionToggle(HandlerOrder.BLOOM_FILTER,
                RedisProCacheProperties.ProtectionProperties::getBloomFilterEnabled,
                "bloom-filter"),
        new ProtectionToggle(HandlerOrder.SYNC_LOCK,
                RedisProCacheProperties.ProtectionProperties::getSyncLockEnabled,
                "sync-lock"),
        new ProtectionToggle(HandlerOrder.EARLY_EXPIRATION,
                RedisProCacheProperties.ProtectionProperties::getEarlyExpirationEnabled,
                "early-expiration"),
        new ProtectionToggle(HandlerOrder.NULL_VALUE,
                RedisProCacheProperties.ProtectionProperties::getNullValueEnabled,
                "null-value"));

// 1 行委派 + 单一迭代方法(替换原 4 if-block)
private static void collectPerMechanismDisables(
        RedisProCacheProperties.ProtectionProperties protection,
        Set<String> disabled) {
    for (ProtectionToggle toggle : PROTECTION_TOGGLES) {
        if (Boolean.FALSE.equals(toggle.getter().apply(protection))) {
            disabled.add(toggle.order().getDisableName());
            log.info("{} disabled by resi-cache.protection.{}.enabled=false",
                    toggle.order().getDescription(), toggle.configPath());
        }
    }
}
```

**deletion test**:
- 删 PROTECTION_TOGGLES + collectPerMechanismDisables → createChain 恢复 4 if-block(重复墙回归)
- 删 ProtectionToggle record → 无类型表达 3 元组(order, getter, configPath),Function getter
  散落调用方,leverage 退化

## 落地影响

**文件变更**:
- 新增 0 个(全部为现有类内部扩展)
- 修改 5:
  - `RedisCacheAttributes.java`:+122 SLOC(3 applyTo 重载 + narrowToInt helper + 注释)
  - `RedisCacheableOperation.java`:+11 -30(22 行 builder 链 → 1 行委派)
  - `RedisCachePutOperation.java`:+11 -25(22 行 builder 链 → 1 行委派)
  - `RedisCacheEvictOperation.java`:+10 -30(14 行 builder 链 → 1 行委派)
  - `CacheHandlerChainFactory.java`:+79 -17(4 if-block → 1 行 + record + list + helper)
- 净变化:**+129 SLOC** (含 javadoc;code-only 净减 ~30 SLOC,3 个文件 builder 链合并)

**leverage 兑现**:
1. **新加 RedisCacheAttributes 字段**(e.g. `bloomExpectedK`):改 1 个文件 3 处(applyTo × 3)
   而非 6 处(原 3 fromAttributes + 3 applyTo)
2. **新加 protection 机制**(e.g. `bloom-batch`):改 CacheHandlerChainFactory 1 行(追加 PROTECTION_TOGGLES)
   而非 4 if-block × 3 行 = 12 行
3. **字段映射与字段定义原子同源** — `applyTo(B)` 在字段拥有者(RedisCacheAttributes)上,
   `ProtectionToggle` 持 Function getter(record 字段绑定),不可能 drift

**公开 API 变化**:**0** — 3 个 `fromAttributes` 签名/契约 byte-for-byte 不变,`createChain` 行为
byte-for-byte 不变(已由 `CacheHandlerChainFactoryTest.protectionEnabled_keepsAll` 4 个 nested
case + `OperationFromAttributesTest` 13 个 case 全部覆盖)。

**Review CR findings**(自我攻击 → 修复):
- **F1 (high,已修)**:初版用 `protectionHolder` 静态字段把 protection 传给 lambda getter —
  跨 Spring context 泄漏,且 `getEnabledFor` switch + PROTECTION_TOGGLES 列表是平行同源定义,
  drift 风险。**修复**:Function 化 — record 持 Function getter,无静态状态,无 switch。
- **F2 (medium,已修)**:初版加 `enabled == null → throw ISE` 防御性契约守卫 — 但 null 是合法状态
  (per-mechanism 字段未设 = "继承 enabled")。守卫破坏 `protectionEnabled_keepsAll` 测试。**修复**:
  删除守卫,只保留 `Boolean.FALSE.equals(...)` 短路,与原 4 if-block byte-for-byte 等价。
- **F3 (low,留 polish)**:Function 化用方法引用,Java 类型推导 `RedisProCacheProperties.ProtectionProperties::getBloomFilterEnabled`
  推断为 `Function<ProtectionProperties, Boolean>`。若未来 `getXxxEnabled` 改返回类型(primitive boolean
  vs Boolean),record 构造会编译失败,符合"早暴露优于晚暴露"原则。无需防御。

**验证**:
- `mvnw checkstyle:check` —— **0 violations**
- `mvnw verify -Dmaven.javadoc.skip=true` —— **BUILD SUCCESS, 782 tests, 0 failures, 0 errors**;
  All coverage checks have been met
- `OperationFromAttributesTest` 13 case 全过(22 字段映射 byte-for-byte)
- `CacheHandlerChainFactoryTest` 12 case 全过(4 if-block 等价行为 + 各种 disabled handler 场景)

**下一步**:无 — ADR-0021 完整落地。下轮(Round 14)可考虑候选 C 残余价值:Cacheable 与 Put 的
2 个 applyTo 在 21/22 字段上 byte-for-byte 一致,extract 共用 `bindCommon22Fields` helper 需
Functional interface 包装(Consumer<Builder> × 22 字段)或反射 — 类型推导与可读性权衡,YAGNI。
