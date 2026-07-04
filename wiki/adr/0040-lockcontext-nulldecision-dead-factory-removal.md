---
title: ADR-0040 LockContext.noLock + NullDecision.passthrough 死工厂删除
type: adr
status: accepted
created: 2026-07-04
related: [0033, 0039, 0011]
---

# ADR-0040:LockContext.noLock + NullDecision.passthrough 死工厂删除

## Context

ADR-0039 删除了 `CacheResult` 的零调用死工厂(`rejectedByBloomFilter()` 等)。Round 30 系统扫描 `chain/model/` 下 record 的语义别名工厂,定位两处 YAGNI 死工厂(ADR-0033 typed decision + LockContext 引入时的漏网):

- **`chain/model/LockContext.noLock()`** —— 无参"不需要锁"工厂(`syncLock=false / lockKey=null / timeoutSeconds=0`),全仓 grep `.noLock()` **零输出**。唯一构造点 `SyncLockHandler:153` 用 `LockContext.builder()`,从不调 `noLock()`。
- **`chain/model/NullDecision.passthrough()`** —— 无参"无转换"工厂(`new NullDecision(null)`),全仓 grep `.passthrough()` **零输出**。生产者 `NullValueHandler:84` 始终用 `NullDecision.of(storeValue)`(`storeValue==null` 已涵盖 passthrough 语义),消费者 `ActualCacheHandler` 读 `getNullDecision().storeValue()`。

两者均为 record 的语义别名工厂,零调用。与 ADR-0039 `CacheResult.miss()`(保留 —— 有调用方消费"未命中"语义)相反,本 pair 的 deletion test 是"零调用 ⇒ 删"。

## Decision

**byte-equivalent 死工厂删除**:

1. 删 `LockContext.noLock()` + javadoc(保留 `of(...)` 工厂 + `requiresLock()` + Lombok `@Builder`);
2. 删 `NullDecision.passthrough()` + javadoc(保留 `of(...)` 工厂,`of(null)` 仍合法,等同原 passthrough 语义);
3. 同步 `wiki/adr/0033` D2 代码示例(删 `passthrough` 行,源码与文档一致)。

## 路径裁决(The Only Path)

存在 X(同时删 `of(...)` 工厂让调用方直接 `new NullDecision(value)`,激进深化)与 Y(只删零调用死工厂,保守深化)歧路。**彻底扼杀 X**:`of(...)` 有 4 处调用(`NullValueHandler:84` 生产者 + `ActualCacheHandlerTest` 3 处),删它触动生产者 + test,且 record 直接构造 `new NullDecision(null)` 可读性低于 `NullDecision.of(null)`、无收益。**直接采用 Y**:死工厂零调用,byte-equivalent 零风险。

## 内部红蓝博弈(CR & Fix)

Plan 阶段 Python 全仓扫描定位两候选后,CR 自审三轮防御:

1. **精确 grep `.noLock()` / `.passthrough()`**(全仓 main+test)—— 零输出。排除 `ActualCacheHandlerTest.fromRedisKey_noPrefix_passthrough`(测试方法名碰巧含 "passthrough",非 `NullDecision.passthrough()` 调用);
2. **专有 test 类核实**(`NullDecisionTest` / `LockContextTest`)—— 不存在,排除断言风险;
3. **ADR-0033 文档核实**—— line 82 D2 代码示例含 `passthrough()`,Fix:同步删除该行(否则文档撒谎,违 ADR-0025 同款 interface-implementation-gap 纪律)。

## Consequences

- **正面 一致性**:`chain/model/` record 工厂纪律统一("零调用 ⇒ 删"),对齐 ADR-0037/0038/0039 死代码扫尾系列;
- **正面 诚实**:ADR-0033 示例与源码同步(不再含已删 `passthrough`);
- **正面 一致性**:`NullDecision` 与 `TtlDecision`(ADR-0033 sibling)对齐 —— 后者只有 `of(...)` 工厂,前者现同样只 `of(...)`;
- **规模**:净 −15 SLOC(2 main + 1 wiki 同步);
- **负面**:无(`LockContext.builder()` 覆盖 `noLock()` 语义;`NullDecision.of(null)` 等价覆盖 `passthrough()` 语义)。

## deletion test

| 对象 | 删除效果 | 裁决 |
|---|---|---|
| `LockContext.noLock()` | 浓缩(死工厂,零调用,`builder()` 等价) | 删 |
| `NullDecision.passthrough()` | 浓缩(死工厂,零调用,`of(null)` 等价) | 删 |

## 测试影响

零。两方法零调用,无 test 断言。`NullValueHandlerTest` + `ActualCacheHandlerTest` 路径测试全绿(byte-equivalent 验证)。

## 参考

- ADR-0033:`NullDecision` typed decision 引入(`passthrough()` 漏网源头)
- ADR-0039:`CacheResult` 死工厂删除(同款 byte-equivalent 扫尾,本 ADR 同构续篇)
- ADR-0011:`CacheKeys` 键派生 seam(LockContext 所属 breakdown 域前序)
