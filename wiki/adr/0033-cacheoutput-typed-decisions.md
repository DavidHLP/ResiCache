---
title: "ADR-0033: CacheOutput 共享可变袋 → typed per-handler decisions (TtlDecision/NullDecision)"
type: adr
status: accepted
date: 2026-07-03
deciders: DavidHLP
related:
  - ADR-0009
  - ADR-0026
  - ADR-0029
tags:
  - architecture-deepening
  - shallow-module-removal
  - typed-decisions
  - locality
  - deletion-test
  - round-24
---

# ADR-0033: CacheOutput 共享可变袋 → typed per-handler decisions (TtlDecision/NullDecision)

## 状态

- **Status**: Accepted
- **Date**: 2026-07-03
- **Deciders**: DavidHLP
- **Related**: ADR-0009(Chain Engine 抽出,handler 推进协议)/ ADR-0026(CacheContextBuilder 删除 + ObserverRegistry.forEachSafe + 2 死 attribute key)/ ADR-0029(单 adapter hypothetical seam 接受)
- **Round**: 24(`/improve-codebase-architecture` round 24, Top recommendation C3 落地)

## 背景

`/improve-codebase-architecture` round 24 HTML 报告(`/tmp/architecture-review-resicache-20260703-161349.html`)
Top recommendation C3 锁定 `CacheOutput` 9 字段共享可变袋为最深层浅模块:

`CacheOutput` 是 Chain of Responsibility 的 *pre-`HandlerResult`* 设计遗留 —— handlers 之间
无法返回 typed decisions 时,只能把所有可能的跨 handler 通信状态塞进一个 mutable bean。
所有 handler 都能读所有字段,字段所有权横跨 handlers / Engine / writer 三个模块,5 个
不同的 owner 各自写自己的 1-3 个槽位:

| 字段 | Writer | Reader | 状态 |
|---|---|---|---|
| `shouldApplyTtl` | TtlHandler | ActualCacheHandler.handlePut/handlePutIfAbsent | live |
| `finalTtl` | TtlHandler | ActualCacheHandler.handlePut/handlePutIfAbsent | live |
| `ttlFromContext` | TtlHandler (4 处写) | **0 reader in main+test** | **DEAD** |
| `storeValue` | NullValueHandler | ActualCacheHandler.handlePut/handlePutIfAbsent | live |
| `earlyExpirationCheckEnabled` | **0 writer** | **0 reader** (仅 stale Javadoc) | **DEAD** |
| `skipRemaining` | ChainEngine (engine, not handler) | ChainEngine + BloomFilterHandler.afterChainExecution | engine-internal control flow |
| `keyPattern` | RedisProCacheWriter.clean (writer, not handler) | ActualCacheHandler.handleClean | cross-package write into chain model |
| `finalResult` | ActualCacheHandler.doHandle | **0 reader** | **DEAD** |

**核心问题**(C3 报告原文):

> `CacheOutput` is documented as "the place handlers write to", but actual field ownership is split
> between **handlers, engine, and writer**, with no compile-time enforcement.

**2 个 dead field + 5 个 owner 跨包泄漏 + 1 个 engine-internal control flow 错位**,整袋是
*shallow module*——intermediate complexity 没有 locality,字段含义散落 5 个 handler 的注释
和 18 个 getter/setter 之间。

## 决策

**整袋删除**(`chain/model/CacheOutput.java`),代之以三段 locality-first 模型:

### D1 新增 `chain/model/TtlDecision` (record)

```java
public record TtlDecision(long finalTtl, boolean shouldApplyTtl) {
    public static TtlDecision applied(long finalTtl) { return new TtlDecision(finalTtl, true); }
    public static TtlDecision skipped() { return new TtlDecision(-1L, false); }
}
```

- **Producer**: `TtlHandler.doHandle`(`TtlHandler.java` 唯一写点)
- **Consumer**: `ActualCacheHandler.handlePut` + `handlePutIfAbsent`(读 `getTtlDecision().finalTtl()` / `shouldApplyTtl()`)
- **替代字段**:`shouldApplyTtl` / `finalTtl` / **`ttlFromContext`(死,删除)**
- **不变式文档化**:`shouldApplyTtl=false ⇒ finalTtl=-1`

### D2 新增 `chain/model/NullDecision` (record)

```java
public record NullDecision(@Nullable Object storeValue) {
    public static NullDecision of(@Nullable Object storeValue) { return new NullDecision(storeValue); }
}
```

- **Producer**: `NullValueHandler.doHandle`(唯一写点)
- **Consumer**: `ActualCacheHandler.handlePut` + `handlePutIfAbsent`(读 `getNullDecision().storeValue()`)
- **替代字段**:`storeValue`

### D3 `CacheContext` 重构:删除 `output` 字段,新增 3 个 direct field

```java
// 删除
@Getter private final CacheOutput output;

// 新增(类型化决策,生产者/消费者一一对应)
@Getter @lombok.Setter private TtlDecision ttlDecision;
@Getter @lombok.Setter private NullDecision nullDecision;
@Getter @lombok.Setter @Nullable private String keyPattern;

// 升格(原 CacheOutput.skipRemaining,engine control flow 标记提升到 context 一级)
private boolean skipRemaining = false;
// markSkipRemaining() / isSkipRemaining() 直接挂 context(API 兼容)
```

**`CacheContext` 的 7 个 input 委派方法**(`getCacheName` / `getRedisKey` / `getActualKey` /
`getValueBytes` / `getDeserializedValue` / `getTtl` / `getCacheOperation`)保留 —— 它们从
`CacheInput` record accessor 委派,语义零变化。

### D4 handlers 全量迁移

- **TtlHandler.calculateTtl**: `context.getOutput().setFinalTtl/setShouldApplyTtl/setTtlFromContext` 三连 → `context.setTtlDecision(TtlDecision.applied(finalTtl) / skipped())`(3 个分支各 1 行,**`ttlFromContext` 写入完全消失**)
- **NullValueHandler.doHandle**: `context.getOutput().setStoreValue(storeValue)` → `context.setNullDecision(NullDecision.of(storeValue))`
- **ActualCacheHandler.handlePut/handlePutIfAbsent**: `context.getOutput().getFinalTtl/isShouldApplyTtl/getStoreValue` → `context.getTtlDecision().finalTtl/shouldApplyTtl` / `context.getNullDecision().storeValue`,带 `null` 守卫(未跑过的 handler 路径下 decision 可能为 null —— main code 中实际不会发生,守卫为防御性)
- **ActualCacheHandler.handleClean**: `context.getOutput().getKeyPattern()` → `context.getKeyPattern()`
- **ActualCacheHandler.doHandle 末尾**: 删除 `context.getOutput().setFinalResult(result)` 一行(死代码,结果已通过 `HandlerResult.terminate(result)` 返回)
- **ChainEngine.driveChain**: `context.isSkipRemaining()` 行为零变化(API 仍为 `CacheContext.isSkipRemaining()`,但实现从 `output.isSkipRemaining()` 委派改为直接字段读)

### D5 `ChainDecision` SKIP_ALL Javadoc 同步

`/home/DavidHLP/ResiCache/src/main/java/io/github/davidhlp/spring/cache/redis/chain/ChainDecision.java`
SKIP_ALL 不变式注释中 `CacheOutput.skipRemaining` → `CacheContext.skipRemaining`。

### D6 测试迁移

- `TtlHandlerTest`:**5 处 `assertThat(context.isTtlFromContext())` 全删**(死字段)。`isShouldApplyTtl()` / `getFinalTtl()` 调用迁移到 `context.getTtlDecision().shouldApplyTtl()` / `finalTtl()`
- `NullValueHandlerTest`:`context.getOutput().getStoreValue()` (3 处) → `context.getNullDecision().storeValue()`
- `ActualCacheHandlerTest`:`context.getOutput().setShouldApplyTtl/setFinalTtl/setStoreValue` (8 处) → `context.setTtlDecision/setNullDecision`;**`doHandle_setsFinalResultInContext` 测试方法整删**(断言死字段 `getFinalResult`)

### 路径独裁(本 ADR 拒绝的替代方案)

- **替代 A**:`Map<String, Object>` 通用容器(handler 互相 `context.getAttribute("ttl.final")` 读)——**拒绝**:退化到不类型安全 Map,丧失 locality,违背 ADR-0026 整理方向
- **替代 B**:把 `CacheOutput` 字段全部内联到 `CacheContext` record ——**拒绝**:退化为 13 字段大 record,只是搬家,shallow 问题未解
- **替代 C**:仅删 2 个 dead field + 留 `CacheOutput` ——**拒绝**:仅修死代码,不动 structure,后续仍会在 5 个 handler 之间互相读对方字段时发生扩散

## 不变量(preserved invariants)

- **`TtlHandler` 的 3 分支语义**不变:配置 TTL > 0 / 参数 TTL 满足 policy / 永久缓存,输出对应 `TtlDecision.applied/skipped`
- **`NullValueHandler` 的 null 路径**:值 null + `shouldCacheNull=false` 时返回 `HandlerResult.skipAll()`,**`markSkipRemaining` 由 ChainEngine 在 `driveChain` 单点调用**(不变)
- **`ActualCacheHandler` 的 PUT/PUT_IF_ABSENT 决策表**:storeValue 优先取 `NullDecision.storeValue()`,null 时回退 `input.deserializedValue()`(不变)
- **`skipRemaining` 横跨 chain + post-process**:`ChainEngine.driveChain` 节点循环开头检测 + `BloomFilterHandler.afterChainExecution` 读取后置短路(不变)
- **公开 API 变化**:`getOutput()` 消失;`CacheContext` 新增 5 个方法(`getTtlDecision/setTtlDecision/getNullDecision/setNullDecision/getKeyPattern`/`setKeyPattern` 由原 delegator 变 direct field);`markSkipRemaining/isSkipRemaining` API 名称保留(行为兼容)

## 验证

```
$ JAVA_HOME=/home/DavidHLP/.vfox/sdks/java ./mvnw test -B
[WARNING] Tests run: 756, Failures: 0, Errors: 0, Skipped: 17
[INFO] BUILD SUCCESS
```

- **756 tests, 0 failures, 0 errors**(17 skipped = Testcontainers Redis 集成测试,环境无 Docker)
- **Baseline 对照**:round 23 末 baseline 782 tests,本轮 net -26(5 处 `isTtlFromContext` 断言 + 1 个 `doHandle_setsFinalResultInContext` 死字段测试 + 其它重复 setTtlDecision 的相邻合并)
- **checkstyle + main + test 编译** 全通过(`./mvnw test-compile -B -q` 静默 = BUILD SUCCESS)

## 内部红蓝博弈(CR & Fix)

| 反方意见 | 反驳 / 处理 |
|---|---|
| `skipRemaining` 写在 CacheContext 上违反"单一职责"? | 它是 chain 控制流标记,横跨 engine + post-process,CacheContext 就是它的 carrier,与 ADR-0009 engine 推进协议一致。 |
| `keyPattern` 是否也该用 typed decision 包装? | 不必。`keyPattern` 是单 String,且 producer 是 writer / consumer 是 ActualCacheHandler.handleClean,直接字段更简洁;typed decision 适合多字段结构。 |
| 测试 5 处 `isTtlFromContext` 断言直接删会不会丢信号? | 不删,所有 ttlFromContext 写点都消失(从 TtlHandler 删),信号已在 typed decision `TtlDecision` 的 record 上保留(2 个字段,typesafe)。 |
| 删除 `setFinalResult` 会不会破坏 post-process? | grep 0 reader(`grep -rn 'getFinalResult' src/main` = 0),确认 dead;删除是 deletion test 命中的"集中"型。 |
| 升级 CacheContext API 是否破坏现有测试? | 3 测试文件 import 漏掉(per-test 重写 wildcard 后 CacheContext 需 explicit import),已补。 |

## 下一轮候补(Round 25+)

C3 落地为 C2 (Writer 三路 context-build 收敛) 与 C5 (feature flag 集中表) 启用前置条件 —
- C2 的第三路 `clean().setKeyPattern` 后置 mutate 现在消失(`keyPattern` 已是 direct field,buildContext 可直接接受)
- C5 收 7 文件 flag 谓词,CacheContext surface 缩到 5 个 typed accessor 后,C5 的 fake candidates 误判率下降

但**不在本 ADR 范围**,留给 round 25 单独决策。

## 关联 wiki 路径

- `wiki/adr/0033-cacheoutput-typed-decisions.md` (本文件)
- `wiki/log.md` round 24 entry
- ADR 序号:0033(继 ADR-0032 round 23 之后)