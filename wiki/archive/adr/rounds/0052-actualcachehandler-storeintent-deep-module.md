---
title: Round 38 — ActualCacheHandler 写路径样板收敛 → StoreIntent 私有深模块
type: adr
tags:
  - adr
  - seam-deepening
  - round-38
  - chain-package
related: [0033-cacheoutput-typed-decisions, 0029-single-adapter-hypothetical-seams-acceptance, 0051-round37-f2-f3-f4-rejection-and-stale-javadoc-fix, 0047-round-34-architecture-deepening]
status: stable
created: 2026-07-06
updated: 2026-07-06
---

# ADR-0052 — Round 38:ActualCacheHandler 写路径 TTL / storeValue 样板收敛 → `StoreIntent` 私有深模块

> ADR-0051(Round 37)末尾遗嘱:**「metrics / resolver / nullvalue 三域旧 friction 已被多轮 ADR 充分覆盖,下一轮 `/improve-codebase-architecture` 应扫描**新域**」**。
> 本轮按遗嘱扫 **chain 写路径**(此前 37 轮从未触及 `ActualCacheHandler.handlePut / handlePutIfAbsent` 的内部结构),发现 ADR-0033 安装的 `TtlDecision` / `NullDecision` 在消费侧的"决议物化"逻辑形成 **2 处近镜像样板**,过 single-adapter hypothetical seam 门槛(ADR-0029),落实为 `StoreIntent` 私有深模块。

## 上下文 (Context)

`ActualCacheHandler` 是责任链终态 handler(PUT / PUT_IF_ABSENT / GET / REMOVE / CLEAN 五路分发)。
ADR-0033 把原 `CacheOutput` 共享可变袋拆为类型化 `TtlDecision` / `NullDecision` /
`PrefetchDecision` 后,**唯一消费者**就是 `handlePut` + `handlePutIfAbsent`。

通读两个写方法发现消费侧有 **3 段近镜像样板**:

```java
// 样板 A — storeValue 解析,5 行 × 2 处完全相同:
Object storeValue = context.getNullDecision() != null
        ? context.getNullDecision().storeValue()
        : null;
if (storeValue == null) {
    storeValue = context.getDeserializedValue();
}

// 样板 B — TTL 分支 + CachedValue 构造 + Redis 重载选择,13 行 × 2 处近镜像
//   (handlePut 用 valueOperations.set / handlePutIfAbsent 用 setIfAbsent,其余完全同构)
if (context.getTtlDecision() != null && context.getTtlDecision().shouldApplyTtl()) {
    long ttl = context.getTtlDecision().finalTtl();
    cachedValue = CachedValue.of(storeValue, ttl);
    valueOperations.set[k](context.getRedisKey(), cachedValue, Duration.ofSeconds(ttl));
} else {
    cachedValue = CachedValue.of(storeValue, -1);
    valueOperations.set[k](context.getRedisKey(), cachedValue);
}
```

`getTtlDecision()` 在 `handlePut` 内被读 **5 次**(含 log.debug)、在 `handlePutIfAbsent`
内被读 **3 次**;`-1` 永久缓存哨兵与 `Duration.ofSeconds` 单位映射散落两处。

## 删除测试 (Deletion Test)

```
假设:不抽 StoreIntent,把样板保持 inline。
└─ 复杂度测度:
   ├─ TTL 分支样板:13 行 × 2 site = 26 行,任一一处改 TTL 语义须同步改两处(漂移风险)
   ├─ storeValue 解析:5 行 × 2 site = 10 行,NullDecision fallback 语义改须同步改两处
   └─ set / setIfAbsent 重载选择 + -1 哨兵 + Duration 映射各散 2 处

抽 StoreIntent + 2 resolve helper 后:
   ├─ TTL 分支样板:0 inline(收口到 resolveStoreIntent + StoreIntent.applyPut/applyPutIfAbsent)
   ├─ storeValue 解析:0 inline(收口到 resolveStoreValue)
   └─ set / setIfAbsent 重载选择 + -1 哨兵 + Duration 映射:各 1 处
```

**deletion test 判据**:把 helper 删掉、内联回去 → 36 行样板重复回归,复杂度**上升**。
故本 seam **浓缩复杂度**(非搬家),过 ADR-0029「single-adapter hypothetical seam
接受策略」门槛(2 site = real seam,非 1-site hypothetical)。

与 ADR-0051 驳回的 F4(NullValueEncoder 签名升级)对比:F4 是**1 行 debug log** 升级,
ROI 不足;本轮是 **36 行真实镜像样板**消除 + 「存储意图」隐含概念命名,ROI 充分。

## 备选路径与驳回 (Alternatives Rejected)

| 路径 | 方案 | 裁决 |
|------|------|------|
| **W** | 5 个 handle* 方法套一个大模板方法(Assert + log + try/catch + errorHandler) | **驳回**:5 方法成功语义异构(GET 返值 / PUT_IF_ABSENT 读现存 / REMOVE·CLEAN 各异),硬套会牺牲清晰度,净复杂度上升 |
| **Y** | 把 `materialize(storeValue)` 推到 `TtlDecision` / `NullDecision` record 上 | **驳回**:扩张 ADR-0033 刚刚安装的 record 公开 API,在 F2/F3/F4 刚被驳回的极度保守期不合时宜;record 当前定位是"类型化跨 handler 消息",添消费侧物化方法改变其角色,ROI 不足 |
| **Z** | 把 `resolveStoreValue` / `resolveStoreIntent` 推到 `CacheContext` 上 | **驳回**:`CacheContext` 是 ADR-0033 确立的 locality-first context DTO(input 不可变 + 类型化决策 + 控制流标记),塞消费侧物化逻辑违反其设计意图,且会让 context 反向认知 `CachedValue` 构造细节 |
| **X**(采用) | `ActualCacheHandler` 私有 `StoreIntent` record + 2 私有 resolve helper | 见下「决策」 |

## 决策 (Decision)

### 1. 新增私有 record `StoreIntent(CachedValue cachedValue, @Nullable Duration ttl)`

PUT / PUT_IF_ABSENT 两个写路径共享的「写什么 + 写多久」不可变决议物。封装三件原本散落的复杂度:

- Spring Data Redis 的 `set` / `setIfAbsent` 各有「带 Duration」与「不带 Duration」两条重载 →
  `ttl == null` 走无 Duration 重载、`ttl != null` 走三参重载。重载选择在 `applyPut` / `applyPutIfAbsent`
  内部收口,调用方只看到两个语义方法。
- `CachedValue.of(value, -1)` 永久缓存哨兵仅在 `resolveStoreIntent` 的 skipped/缺席分支产生。
- `Duration.ofSeconds(finalTtl)` 单位映射仅在 `resolveStoreIntent` 产生。

### 2. 新增私有 `resolveStoreValue(CacheContext)`

`NullDecision` 在场且 `storeValue()` 非 null → 取决策值;否则(NullDecision 缺席或显式 `of(null)`)
→ 沿用 `deserializedValue`。把 5 行 × 2 处的 null-fallback 样板塌缩为单点。

### 3. 新增私有 `resolveStoreIntent(CacheContext, storeValue)`

`TtlDecision` 在场且 `shouldApplyTtl()` → 物化为带 TTL 的 `StoreIntent`(CachedValue 携 `finalTtl`、
Duration 为 `ofSeconds(finalTtl)`);否则 → 物化为永久缓存(CachedValue 携 `-1`、Duration 为 `null`)。
把 13 行 × 2 处的 TTL 分支 + CachedValue 构造样板塌缩为单点。

### 4. `handlePut` / `handlePutIfAbsent` 调用方塌缩

```java
// handlePut 核心写路径(原 20+ 行 → 现 2 行):
StoreIntent intent = resolveStoreIntent(context, resolveStoreValue(context));
intent.applyPut(valueOperations, context.getRedisKey());

// handlePutIfAbsent 核心写路径(原 20+ 行 → 现 2 行):
StoreIntent intent = resolveStoreIntent(context, resolveStoreValue(context));
if (intent.applyPutIfAbsent(valueOperations, context.getRedisKey())) { ... }
```

## 影响面 / SLOC 对比

| 项 | Round 37(前) | Round 38(本 ADR) | 净变化 |
|----|------|------|------|
| `ActualCacheHandler.java` 总行数 | 357 | 409 | **+52**(全为 Javadoc) |
| inline TTL 分支样板 | 13 行 × 2 = 26 | 0(收口 `resolveStoreIntent`) | **-26** |
| inline storeValue 解析样板 | 5 行 × 2 = 10 | 0(收口 `resolveStoreValue`) | **-10** |
| `StoreIntent` record + 2 helper(不含 Javadoc) | 0 | ~29 | +29 |
| 代码行(不含 Javadoc)净变化 | — | — | **约 -7** |
| Javadoc(解释 deepening 理由) | — | +~59 | +59 |
| 公开 API / 公开类签名 | 不变 | 不变 | **0** |
| 序列化字节(`set` / `setIfAbsent` 重载选择 + `Duration.ofSeconds(finalTtl)` + `CachedValue.of(sv, ttl/-1)`) | 不变 | 不变 | **0** |

净 SLOC **+52**(全为 Javadoc 文档化 deepening 理由,符合项目「Javadoc 解释设计 rationale」惯例);
**复杂度代码(不含 Javadoc)约 -7 行**,且把 36 行 inline 镜像样板塌缩为命名概念 + 单点真理源。

## 字节等价 / 测试矩阵

`ActualCacheHandlerTest` 的 6 个 PUT/PUT_IF_ABSENT 测试精确 pin 了 Redis 重载选择 + Duration 值,
全数保持:

| 测试 | 输入决策 | 期望 Redis 调用 | 本轮保持 |
|------|---------|----------------|---------|
| `handlePut_withTtl_storesValueWithTtl` | `TtlDecision.applied(120)` + `NullDecision.of("storeValue")` | `set(key, cv, Duration.ofSeconds(120))` 三参重载 | ✅ |
| `handlePut_withoutTtl_storesValueWithoutTtl` | `TtlDecision.skipped()` + `NullDecision.of("storeValue")` | `set(key, cv)` 双参重载 | ✅ |
| `handlePut_noStoreValue_usesDeserializedValue` | `TtlDecision.skipped()`,无 NullDecision | `set(key, cv)` 双参重载,值=`deserializedValue` | ✅ |
| `handlePutIfAbsent_keyNotExists_storesValue` | `TtlDecision.applied(120)` | `setIfAbsent(key, cv, Duration.ofSeconds(120))` 三参重载 → true | ✅ |
| `handlePutIfAbsent_setFails_returnsExistingValue` | `TtlDecision.skipped()` | `setIfAbsent(key, cv)` 双参重载 → false → 读现存值 | ✅ |
| `handlePutIfAbsent_keyExists_returnsExistingValue` | 无 TtlDecision | `setIfAbsent(key, cv)` 双参重载(未 stub 返 null → false)→ 读现存值 | ✅ |

## 验证状态

- ✅ `./mvnw checkstyle:check` 0 violation
- ✅ `./mvnw test-compile` 绿(JDK 21)
- ✅ `ActualCacheHandlerTest` 全绿(定向 + 全量)
- ✅ **744 单测全绿(0 fail / 0 err / 17 skipped Docker integration)** — 与 Round 36/37 基线 byte-equivalent
- ✅ 序列化字节等价:`set` / `setIfAbsent` 重载选择、`Duration.ofSeconds(finalTtl)`、`CachedValue.of(sv, ttl/-1)` 全保持

## 设计纪律

- **私有而非顶层**:`StoreIntent` 当前仅 PUT / PUT_IF_ABSENT 两个消费者,未达提升为顶层类型的必要性(YAGNI)。若未来新增第 3 个写路径消费者再考虑提升。
- **不污染决策 record**:`TtlDecision` / `NullDecision` 维持 ADR-0033 安装的"被动消息"定位,不添消费侧物化方法(驳回路径 Y)。
- **不污染 CacheContext**:`CacheContext` 维持 locality-first context DTO 定位(驳回路径 Z)。
- **重载选择是 Spring Data Redis API 的真实复杂度**:`ValueOperations.set` / `setIfAbsent` 各两条重载(Duration 与否),`StoreIntent` 把这一 API 不对称性封装在记录内,调用方看不到。

## 相关 ADR

- **前置**:
  - ADR-0033(`TtlDecision` / `NullDecision` 类型化决策安装 — 本 ADR 是其**消费侧**深化,生产侧不动)
  - ADR-0029(single-adapter hypothetical seam 接受策略 — 本轮 2-site 过 real seam 门槛)
  - ADR-0051(Round 37 遗嘱:扫新域 — 本轮兑现,扫 chain 写路径)
- **后续**:无新候选挂账。`ActualCacheHandler` 写路径已收口;GET / REMOVE / CLEAN 三路因成功语义异构不套统一模板(路径 W 已驳回)。
