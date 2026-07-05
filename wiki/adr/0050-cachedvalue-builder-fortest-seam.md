---
title: Round 36 — CachedValue dual-construction rail collapse + forTest seam
type: adr
tags:
  - adr
  - dead-code-removal
  - seam-deepening
  - round-36
  - cache-package
related: [0047-round-34-architecture-deepening, 0049-c6-nullvalueencoder-implementation]
status: stable
created: 2026-07-05
updated: 2026-07-05
---

# ADR-0050 — Round 36:CachedValue 双轨构造收敛 + `forTest` seam 抽出

> 本轮是 Round 35(ADR-0049 完成 C6 实施)之后,由 `/improve-codebase-architecture`
> 命令产出的 deepen candidates 中 **F1** 的实施记录。
> ADR-0047 C6 委派(round 35 已结案)路线已被 ADR-0049 实施,ADR-0050 是
> Round 35 之后浮现的"深度化副作用 + 上一轮遗漏点"——`CachedValue.builder()`
> 双轨死路径。

## 上下文 (Context)

`CachedValue` 是 cache 包的核心 wire DTO,对所有 5 大防护机制 + 编解码通路
负责。它在 `a5ab55b` refactor 之后经历过多次 seam 收敛(`Expiry` 内嵌类、
字段布局稳定化、`@JsonTypeInfo` 序列化保护等),但它的**构造入口**始终保持双轨:

```java
public static CachedValue of(Object value, long ttl) { ... }   // 生产唯一 seam
public static CachedValueBuilder builder() { ... }              // 测试专用,75 行死代码
public static class CachedValueBuilder { ... 9 setters + build() ... }
```

Round 30 之前已经做过 `CacheResult` 死字段清理(ADR-0039),Round 31 / 33 / 38
也做过若干 YAGNI 删除。但 `CachedValueBuilder` 作为 round 1 时期为了方便
测试"任意字段覆盖"的便利设施,**从未被任何 ADR 触及**。

## 删除测试 (Deletion Test)

```
删除 CachedValueBuilder + builder() 方法
└─ 调用方扫描:
   ├─ 生产源码(grep "CachedValue.builder|CachedValueBuilder" src/main):
   │  └─ 0 hits
   ├─ 测试源码(grep 同上,src/test):
   │  └─ 3 hits(2 个 helper + 1 个直接调用)
   └─ 测试覆盖验证:
      └─ 3 处调用都可被新的 forTest(value, ttl, createdTime, version, expired)
         1-liner 替代,无功能差异
```

结论:**删除会浓缩复杂度**(双轨合并到单一生产 seam + 单一测试 seam),
符合删除测试判据。

## 决策 (Decision)

### 1. 删 `CachedValueBuilder` 整个静态内嵌类(74 行)

理由:仅剩 3 处测试 helper 使用。9 个 setter 中 4 个是冗余字段
(`type` 由 `value.getClass()` 派生,`startNanoTime`/`lastAccessTime`/`visitTimes`
由生产 seam 默认填充语义等价)。

### 2. 删 `CachedValue.builder()` 静态工厂方法(3 行)

随 builder 类一并消失。

### 3. 新增 `@VisibleForTesting` 的 `forTest(value, ttl, createdTime, version, expired)` 工厂

```java
/**
 * 仅供测试使用：用指定 createdTime / version / expired 三维覆盖构造 CachedValue
 * (type / startNanoTime 仍按 of() 默认自动派生,避免与生产 seam 行为漂移)。
 *
 * <p>替换被删除的 CachedValueBuilder(...)。Visible for testing:
 * 因测试类分布在不同包(chain / protection.refresh / serialization),
 * 使用 public 以便跨包访问;调用契约由 Javadoc 与单元测试约束,
 * 生产代码严禁引用,唯一生产 seam 仍是 {@link #of(Object, long)}。
 */
public static CachedValue forTest(@Nullable Object value, long ttl,
                                  long createdTime, long version, boolean expired) { ... }
```

关键设计点:
- **5 参**(从原 builder 的 9 字段降维):只暴露真正需要测试 override 的三维
  (createdTime / version / expired);type / startNanoTime / lastAccessTime
  / visitTimes / ttl 走生产 seam 同样的派生逻辑,确保测试构造与生产构造
  在这 5 个字段上语义等价。
- **`public static` 而非包私有**:测试分布在 3 个不同包(
  `chain`、`protection.refresh`、`serialization`),跨包访问的纯测试
  seam 必须 `public`;但 Javadoc 中明确禁止生产代码引用。
- **`@Nullable Object value`**:与 `of()` 保持签名一致(允许 null,
  走 Jackson NullValue 占位路径)。

### 4. 测试侧改动

3 处 `CachedValue.builder()` 改为 `CachedValue.forTest(...)` 1-liner 委派,
字段 arg 顺序按 helper 语义传:

| 原 helper 字段矩阵 | 新 `forTest` 参数 |
|------|------|
| `value`, `type=String.class`(冗余), `ttl`, `createdTime`, `startNanoTime=System.nanoTime()`(语义等价), `version=1L`, `expired=false` 默认 | `forTest("test-value", ttl, createdTime, 1L, false)` |
| 同上,但 `expired=true` 用于"cache expired"分支测试 | `forTest("test-value", 60, ..., 1L, true)` |

## 影响面 / SLOC 对比

| 项 | Round 35(前) | Round 36(本 ADR) | 净变化 |
|------|------|------|------|
| `CachedValue.java` 总行数 | 263 | 218 | **-45** |
| `CachedValueBuilder` 内嵌类 | 73 行 | 0 | -73 |
| 测试源(builder call sites) | 3 处 7-line 链式 | 3 处 1-line 委派 | -18 测试行 |
| 生产 seam `of()` 签名 | 不变 | 不变 | 0 |
| CachedValue 字段布局 / 序列化字节 | 不变 | 不变 | 0 |
| `forTest()` 新增公开方法 | 0 | 1 | +1 |

净负 **-62 SLOC** (不含 Javadoc)。

## 验证状态

- ✅ `./mvnw test-compile`(intellij jdk 21 编译链)绿
- ✅ 744 单测全绿(0 fail / 0 err / 17 skipped Docker integration)
- ✅ 影响的 5 个 test 类 53 test 方法 100% 绿:
  - `EarlyExpirationHandlerTest`(替换 2 处)
  - `EarlyExpirationHandlerRaceConditionTest`(替换 1 处 helper)
  - `ActualCacheHandlerTest`(使用 `of()` 不受影响,验证生产 seam)
  - `SecureJacksonRedisSerializerTest`(使用 `of()` 不受影响,验证序列化字节不变)
  - `SecureJacksonSerializerFactoryTest`(使用 `of()` 不受影响)
- ✅ Checkstyle 0 violation
- ⚠ Maven 构建期需要 JAVA_HOME=java-21(本机环境 WSL 走 `-Djava.version=17`
  override 兜底,JDK 21 安装落地后可直接 `mvn test` 不带 override)

## 相关 ADR

- **前置**: ADR-0039(CacheResult 死字段删除,本 ADR 是同模式续篇);
  ADR-0047(Round 34 决策固化),本 ADR 来自其"扫描未触及域"补遗。
- **后续候选**:
  - ADR-0051(Round 37 候选): `RedisProCacheWriter.getCacheStatistics` ↔
    `RedisProCache.metrics()` 双轨访问 seam 合并 + 残留 stale 文档清理
    (F2 候选,Explore agent 标记 Worth exploring)。
  - ADR-0052(Round 38 候选): `MethodMetadataResolver.activate` ↔
    `runWithSnapshot` 公开双轨融合为单一 `ScopedSnapshot` seam
    (F3 候选,Speculative / 需新 contract)。
  - ADR-0053(Round 39 候选,Speculative): `NullValueEncoder.encodeForReturn`
    `(value, cacheName, key)` 三字符串签名为 1 对象的 seam 升级
    (F4 候选,签名审计层面)。
