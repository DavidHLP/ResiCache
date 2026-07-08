---
title: Round 40 — CacheOperation 多操作判定 5-site 漂移消除 → 枚举自承谓词 + 全量 pinning 测试
type: adr
tags:
  - adr
  - seam-deepening
  - round-40
  - chain-package
related: [0029-single-adapter-hypothetical-seams-acceptance, 0033-cacheoutput-typed-decisions, 0036-prefetch-decision-interceptor-activate-lua-script, 0045-postprocesshandler-cachehandler-merge-and-parasitic-keys-attribution, 0051-round37-f2-f3-f4-rejection-and-stale-javadoc-fix, 0052-actualcachehandler-storeintent-deep-module, 0053-redissonconfiguration-timeout-retry-helper]
status: stable
created: 2026-07-08
updated: 2026-07-08
---

# ADR-0054 — Round 40:CacheOperation 多操作判定 5-site 漂移消除 → 枚举自承谓词 + 全量 pinning 测试

> ADR-0051/0052/0053 接连兑现「扫新域」(写路径 / config 装配 / annotation 处理)。
> 本轮继续扫 **chain 全域**:在保护 handler 的 `shouldHandle` / `requiresPostProcess` 与 `doHandle` switch
> 中,**5 处**手写多操作谓词(`op == X || op == Y || ...`)散落 4 个 handler,无测试 pin,
> 未来加新操作易漏改某处。`/improve-codebase-architecture` 扫描器已标 Strong,本轮落地。

## 上下文 (Context)

`CacheOperation` 是 5 值枚举:`GET / PUT / PUT_IF_ABSENT / REMOVE / CLEAN`。
3 个 protection handler + bloom filter handler 的 `shouldHandle` 与 `requiresPostProcess`
各自手写多 op 谓词,经 grep `CacheOperation\.(PUT|GET|REMOVE|CLEAN|PUT_IF_ABSENT)` 锁定 **5 处**:

| # | 文件 | 方法 | 谓词 | 谓词语义 |
|---|------|------|------|---------|
| 1 | `TtlHandler.java:56-57` | `shouldHandle` | `op == PUT \|\| op == PUT_IF_ABSENT` | 写路径(写时计算 TTL) |
| 2 | `NullValueHandler.java:55-56` | `shouldHandle` | `op == PUT \|\| op == PUT_IF_ABSENT` | 写路径(写时转换 null 存储) |
| 3 | `SyncLockHandler.java:99-101` | `shouldHandle` | `op == GET \|\| op == PUT_IF_ABSENT \|\| op == PUT` | sync-lock 关心的子集 |
| 4 | `BloomFilterHandler.java:110-112` | `requiresPostProcess` | `op == PUT \|\| op == PUT_IF_ABSENT \|\| op == CLEAN` | bloom 后置回填子集 |
| 5 | `BloomFilterHandler.java:91-96` (switch default) | `doHandle` switch `default ->` | (隐式 5-of-5 exhaustive,但写了 dead `default`) | (本轮**不动**,仅记录) |

谓词 #1 与 #2 实质上是同一集合的不同别名(「写路径」);谓词 #3 / #4 是不同子集。
**完全相同的 5 行 `op == X || op == Y` 模式出现 2 次**(TtlHandler + NullValueHandler),
按 ADR-0029 早过 single-adapter hypothetical seam 门槛。

### 风险面

- **无测试 pin**:现有 `BloomFilterHandlerTest` / `SyncLockHandlerTest` / `TtlHandlerTest` /
  `NullValueHandlerTest` **未测试过 `requiresPostProcess` 谓词本身**,仅测试「put 命中场景」,
  即「`op == PUT` 触发 post-process」是测试结果而非契约 pin。
- **新操作易漏改**:若未来加 `PUT_ALL` / `EVICT_ALL` 操作,需在 4 个 handler 中各加一行
  `|| op == CacheOperation.PUT_ALL`;**单点漏改** 即静默改变行为(如 bloom 不再回填导致
  下次 miss 穿透,无单测失败、无日志)。
- **跨文件重复**:5 行 `op == X || op == Y` 出现 2 次 × 4 个 handler 共 8 处,均依赖 `import
  CacheOperation`。谓词源散在 4 个文件,无单一真理源。

### 删除测试 (Deletion Test)

```
假设:不抽谓词,保持 5 处手写。
└─ 复杂度测度:
   ├─ 5 行 op-list × 5 site = 25 行 inline 谓词
   ├─ 4 个 handler 共享 op-list 但分散持有,任意契约变更须改 5 处(漂移风险)
   └─ 新操作加 N 个 → 需改 5 × N 处,无一自动 pin

抽 3 个谓词到 CacheOperation + 全量 pinning 测试后:
   ├─ 5 行 op-list × 5 site = 0 inline(全部收口到 3 个谓词)
   ├─ 4 个 handler 各塌缩为 1 行谓词调用 + 1 行注释
   └─ 新操作加 N 个 → 仅 CacheOperation 1 文件 + 谓词方法 3 处 + pinning 测试 3 处,
      pinning 测试用 @EnumSource 自动发现未授权加入子集的情形
```

**deletion test 判据**:把 3 个谓词删掉、内联回去 → 25 行样板重复回归 + 4 handler 各 +1 漂移点,
复杂度**上升**。故本 seam **浓缩复杂度**(非搬家),过 ADR-0029 门槛(2 site = real seam;
本轮 5 site,远超门槛)。

## 备选路径与驳回 (Alternatives Rejected)

| 路径 | 方案 | 裁决 |
|------|------|------|
| **A** | 在 `CacheContext` 上加 `isWriteOperation()` / `requiresSyncLock()` 转发方法 | **驳回**:`CacheContext` 是 ADR-0033 安装的 locality-first DTO(input 不可变 + 类型化决策 + 控制流标记),塞操作谓词改变其角色,污染 ADR-0033 设计意图;且 `CacheContext` 不应反向认知 `CacheOperation` 枚举 |
| **B** | 抽 `OperationPredicates` 顶层工具类(`predicates.isWrite(op)`) | **驳回**:与「让枚举自承」相比多一层间接;Java enum 自身可挂方法,惯例是 `Enum.method()` 形式(如 `EnumSet.range(LOW, HIGH)`);`op.isWrite()` 比 `predicates.isWrite(op)` 更短链 |
| **C** | 用 `EnumSet<CacheOperation>` 静态常量 + `op ∈ WRITES.contains(op)` | **驳回**:`EnumSet` 在 hot path `shouldHandle` 上有 `HashMap` 查找开销(虽然 O(1) 但慢于 `this == X \|\| this == Y` 字节码级 `tableswitch`);且 `EnumSet.of(...)` 不可变视图是合法的但增加阅读成本;enum 自承方法被 JIT 编译为最佳字节码 |
| **D**(采用) | 枚举自承 3 个谓词 + 全量 `@EnumSource` pinning 测试 | 见下「决策」 |

## 决策 (Decision)

### 1. `CacheOperation` 自承 3 个谓词

```java
public enum CacheOperation {
    GET, PUT, PUT_IF_ABSENT, REMOVE, CLEAN;

    public boolean isWrite() {
        return this == PUT || this == PUT_IF_ABSENT;
    }
    public boolean requiresSyncLock() {
        return this == GET || this == PUT || this == PUT_IF_ABSENT;
    }
    public boolean requiresBloomPostProcess() {
        return this == PUT || this == PUT_IF_ABSENT || this == CLEAN;
    }
}
```

3 个谓词对应 3 个不同 handler 关心的不同子集,谓词名=契约语义,enum 自身为单一真理源。

### 2. 4 个 handler 调用方塌缩为 1 行

```java
// TtlHandler.shouldHandle
return context.getOperation().isWrite();

// NullValueHandler.shouldHandle(同上,共享 isWrite 谓词)
return context.getOperation().isWrite();

// SyncLockHandler.shouldHandle
if (context.getCacheOperation() == null || !context.getCacheOperation().isSync()) {
    return false;
}
return context.getOperation().requiresSyncLock();

// BloomFilterHandler.requiresPostProcess
return context.getOperation().requiresBloomPostProcess();
```

### 3. `BloomFilterHandler.doHandle` / `afterChainExecution` switch 不动

两处 switch 是 5-of-5 exhaustive(5 个 case 覆盖 5 个枚举值),dead `default ->` 是历史防御
模板残留,与本轮谓词漂移问题正交。**不在本轮动**——本轮专注「手写 op-list 漂移消除」,
不掺「dead branch 清理」(后者需独立 round 决策「是否连同 switch 全部转 if-else」)。

### 4. 移除 3 处 unused import

`TtlHandler` / `NullValueHandler` / `SyncLockHandler` 在谓词重构后不再直接用 `CacheOperation`
枚举常量(只调 `op.isWrite()` 等方法),`import ...CacheOperation;` 变 unused。checkstyle
`UnusedImports` 严格,必须移除。`BloomFilterHandler` 保留(其 `doHandle` / `afterChainExecution`
switch 仍用 `case GET` 等枚举常量)。

### 5. 新增 `CacheOperationTest` 全量 pinning 测试

`@ParameterizedTest` + `@EnumSource(value = CacheOperation.class, names = {...})` 显式列举
每个谓词的 true-set / false-set,以及子集间的一致性约束:

```java
@ParameterizedTest(name = "{0} should be a write op")
@EnumSource(value = CacheOperation.class, names = {"PUT", "PUT_IF_ABSENT"})
void writeOperations_returnTrue(CacheOperation op) {
    assertThat(op.isWrite()).isTrue();
}
```

未来新增枚举值时,若未授权加入任一子集,默认走 negative `@EnumSource` 失败;若授权加入
某子集,需显式更新 `names = {...}`——**每次授权 = 一次显式契约确认**。

## 影响面 / SLOC 对比

| 项 | Round 39(前) | Round 40(本 ADR) | 净变化 |
|----|------|------|------|
| `CacheOperation.java` 行数 | 10 | 64(纯 Javadoc + 3 谓词) | **+54**(全为 Javadoc) |
| `TtlHandler.shouldHandle` 谓词 | 2 行 inline | 1 行调用 + 1 行注释 | **-1** |
| `NullValueHandler.shouldHandle` 谓词 | 2 行 inline | 1 行调用 + 1 行注释 | **-1** |
| `SyncLockHandler.shouldHandle` 谓词 | 4 行 inline | 1 行调用 + 1 行注释 | **-3** |
| `BloomFilterHandler.requiresPostProcess` 谓词 | 3 行 inline | 1 行调用 + 6 行 Javadoc 更新 | **+3** |
| 5 site inline 谓词合计 | 11 行 | 0 | **-11** |
| unused import 移除 | 3 处 | 0 | 0(checkstyle 合规) |
| 新增 pinning 测试 | 0 | 1 文件 / 4 嵌套类 / 15 个 `@ParameterizedTest` | +15 测试 |
| 公开 API 增量 | 0 | `CacheOperation` 新增 3 公开方法 | 最小 |
| 行为字节等价 | (基线) | 100% 保持(契约本身不变) | **0** |

净 SLOC **+52**(全为 Javadoc + 测试);**inline 谓词代码 -11 行**,5 site 漂移点塌缩为 0。
**行为字节等价**:谓词拆分前后真值表完全相同,5 个 handler 的 `shouldHandle` / `requiresPostProcess`
返回值与 Round 39 前 bit-for-bit 一致。

## 验证状态

- ✅ `./mvnw checkstyle:check` 0 violation(3 个 unused import 移除后)
- ✅ `./mvnw test-compile` 绿(JDK 21)
- ✅ 5 个 handler 既有测试 (`TtlHandlerTest` / `NullValueHandlerTest` / `SyncLockHandlerTest` /
  `BloomFilterHandlerTest` / `ActualCacheHandlerTest`) 全绿 — 行为字节等价证明
- ✅ 新增 `CacheOperationTest` 15 个 `@ParameterizedTest` 全绿(覆盖 isWrite 5 op / requiresSyncLock
  5 op / requiresBloomPostProcess 5 op + 4 个子集一致性)
- ✅ **765 单测全绿(0 fail / 0 err / 17 skipped Docker integration)** — 与 Round 39 基线持平
- ✅ 谓词真值表:3 谓词 × 5 操作 = 15 组合,本轮重写前后 byte-for-byte 一致

## 设计纪律

- **枚举自承而非顶层工具类**:Java enum 自身可挂方法,惯例是 `Enum.method()` 形式,比
  `predicates.isWrite(op)` 短链 1 层;JIT 把 `this == X || this == Y` 编译为 `tableswitch`
  最快字节码,优于 `EnumSet.contains`。
- **不改 `CacheContext`**:维持 ADR-0033 安装的 locality-first DTO 定位(驳回路径 A)。
- **不改 BloomFilterHandler switch**:`doHandle` / `afterChainExecution` 两处 switch 的 dead
  `default ->` 与本轮谓词漂移问题正交,**不**在本轮混入其他 fold。注释记录但保留。
- **Javadoc 解释 rationale 而非复述 API**:每个谓词的 Javadoc 说明「谁关心这个子集 +
  为什么不参与」(如 REMOVE 不参与 requiresSyncLock:「删除本身无击穿风险」)。
- **YAGNI 不加 `isRemovable()`**:当前无 handler 关心 REMOVE/CLEAN 子集谓词(REMOVE 在
  `SyncLockHandler.shouldHandle` 测试中明确"returns false")。新增谓词需 ≥1 个 caller,
  否则为 false seam,违反 ADR-0029。
- **`@EnumSource` 显式列举**:用 `names = {"PUT", "PUT_IF_ABSENT"}` 显式而非 `mode = EXCLUDE`,
  避免新增操作默认进入子集而无人察觉;`EXCLUDE` 模式反而鼓励漏改。

## 相关 ADR

- **前置**:
  - ADR-0029(single-adapter hypothetical seam 接受策略 — 本轮 5-site 远超 2-site 门槛)
  - ADR-0033(`TtlDecision` / `NullDecision` 类型化决策 — 本轮是其**生产侧**语义补完:
    当初装了"消息类型",现在装"消息生产子集谓词")
  - ADR-0036(`PrefetchDecision` 类型化 — 同脉络)
  - ADR-0045(`requiresPostProcess` hook 化 — 本轮 BloomFilterHandler.requiresPostProcess
    谓词化是该 hook 的内联实现)
  - ADR-0051/0052/0053(连续三轮「扫新域」遗嘱与兑现)
- **后续**:无新候选挂账。`CacheOperation` 谓词契约定型,handler 内部漂移点归零。
