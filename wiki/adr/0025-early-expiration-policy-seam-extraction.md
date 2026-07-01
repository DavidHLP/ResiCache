---
title: "ADR-0025: early-expiration 决策 policy seam 迁出 TtlPolicy (refresh↔avalanche 跨域寄生方法 + Clock 依赖归位)"
type: adr
status: accepted
date: 2026-07-02
deciders: DavidHLP
related:
  - ADR-0024
  - ADR-0016
tags:
  - protection
  - refresh
  - avalanche
  - seam
  - interface-implementation-gap
  - isp
  - round-17
---

# ADR-0025: early-expiration 决策 policy seam 迁出 TtlPolicy (refresh↔avalanche 跨域寄生方法 + Clock 依赖归位)

## 状态

- **Status**: Accepted
- **Date**: 2026-07-02
- **Deciders**: DavidHLP
- **Related**: ADR-0024(同款跨域接缝视角,config↔refresh dead config)/ ADR-0016(ObserverRegistry 跨 engine 去重 seam —— 跨域内聚先例)

## 背景

第 17 轮架构评审延续 round 16 创新的**跨域接缝视角**(round 15 已宣告单域饱和)。round 1–16 各轮均按「责任链 5 机制」展开,但 `protection/avalanche/` 与 `protection/nullvalue/` 两域**零 ADR 触及**(bloom/breakdown/refresh 均已多轮深化)。本轮系统通读这两个盲区域的全部 6 个源文件后,定位到一处真实的**跨域寄生 seam**:

**Interface(寄生方的承诺)** —— `protection/avalanche/TtlPolicy`(`TtlPolicy.java:16-26`)声明 3 个方法,其中第 3 个 `shouldEarlyExpiration(createdTime, ttlSeconds, threshold)` 的 javadoc 自承「TtlHandler 与 EarlyExpirationHandler 依赖本接口」。

**Implementation(寄生事实)** —— 全仓 trace(`trace_path` inbound):

| 方法 | 真实消费者 | 归属域 |
|---|---|---|
| `shouldApply(Duration)` | `TtlHandler` | avalanche ✓ |
| `calculateFinalTtl(...)` | `TtlHandler` | avalanche ✓ |
| `shouldEarlyExpiration(...)` | `EarlyExpirationHandler.checkEarlyExpiration`(**唯一**) | **refresh** ✗ |

`shouldEarlyExpiration` 的**唯一消费者是 refresh 域的 `EarlyExpirationHandler`**。refresh 域反向 import `protection.avalanche.TtlPolicy` 只为调这一个方法 —— 跨包依赖方向倒置。

**决定性 deletion-test 信号**:`DefaultTtlPolicy` 唯一字段 `private final Clock clock`(**仅为 `shouldEarlyExpiration` 而存在**):

- `shouldApply` —— 不用 Clock
- `calculateFinalTtl` —— 不用 Clock(用 `ThreadLocalRandom`)
- `shouldEarlyExpiration` —— 经私有 `currentTimeMillis()` → `clock.millis()`(**唯一 Clock 消费者**)

即:一个 refresh 域的判定方法,把一个基础设施依赖(`Clock`)整体拖进了本不需要它的 avalanche 模块。

**Friction(五合一)**:

1. **跨域依赖方向倒置**:refresh → avalanche(为拿本属于自己的决策逻辑)
2. **interface 文档撒谎**(同源 ADR-0024 的 stale-facts 类):`wiki/mechanisms/ttl-jitter.md` 称「`TtlPolicy` 不只服务抖动,还服务提前过期判定」「单一策略类同时承载雪崩(抖动)与热 key(提前过期)两个相关但不同的判定,共享 `Clock`」—— 把 ISP 违反写成设计优点;`early-expiration.md` 称决策「交给 `DefaultTtlPolicy.shouldEarlyExpiration` 判断」。CI `docs-link-check` 不捕获(语义级 stale,非死链)。
3. **基础设施依赖寄生**:`Clock` bean(`RedisProCacheConfiguration.systemClock`)被 avalanche 持有却无 avalanche 用途。
4. **可替换性缺口**:`NullValuePolicy` / `LockManager` / `BloomIFilter` / `TtlPolicy` 四个机制各有自有 policy seam,唯 `refresh` 的核心决策(何时触发刷新)无自有 seam、寄生在邻居上 —— 与项目「策略替换」纪律(CLAUDE.md)不一致。

## deletion test

**正向(迁出深化)**:把 `shouldEarlyExpiration` 迁到 refresh 域自有 `EarlyExpirationPolicy` seam → `DefaultTtlPolicy` 退化为无状态(自然丢 Clock)→ refresh↔avalanche 依赖方向归正 → wiki 文档与 impl 一致 → complexity 集中到正确归属(refresh 的决策回 refresh)。

**反向(删方法而非迁出)**:直接删 `shouldEarlyExpiration` → complexity 不消失(消费者 `EarlyExpirationHandler` 需要这判定,会就地内联或重建)→ 但失去可替换 seam。→ 否决。

**反向(保留寄生)**:`TtlPolicy` 继续承载跨域方法 → complexity 不增不减,但**依赖方向倒置 + 文档撒谎 + Clock 寄生**三合一 friction 全部保留,且 `Clock` 字段永久寄生。→ 否决。

**判定信号**:`DefaultTtlPolicy` 的 `Clock` 字段**仅服务寄生方法**。删该方法 → `Clock` 字段、`currentTimeMillis()` helper、`@RequiredArgsConstructor` 全部自然消失,`DefaultTtlPolicy` 从「持 Clock 的有状态类」退化为「无状态纯 TTL 数学」。这是 deletion test 的强确认:**寄生方法在拖一个本不该在这里的依赖**。

## 决策

### D1 — 新建 `protection/refresh/EarlyExpirationPolicy` 接口(执行)

```java
public interface EarlyExpirationPolicy {
    boolean shouldRefresh(long createdTime, long ttlSeconds, double threshold);
}
```

单方法决策 seam,住 refresh 域(关注点的归属包)。改名 `shouldEarlyExpiration` → `shouldRefresh` 对齐全域 ubiquitous language(`EarlyExpirationDecision.needsRefresh()` / `scheduleAsyncRefresh` / `RefreshRetryPolicy` 全用 "refresh" 动词;接口名已含 "EarlyExpiration",方法名无需重复)。

### D2 — 新建 `protection/refresh/DefaultEarlyExpirationPolicy`(@Component,Clock 注入,执行)

```java
@Component
@RequiredArgsConstructor
public class DefaultEarlyExpirationPolicy implements EarlyExpirationPolicy {
    private final Clock clock;
    @Override
    public boolean shouldRefresh(long createdTime, long ttlSeconds, double threshold) {
        if (ttlSeconds <= 0 || threshold <= 0 || threshold >= 1) return false;
        long elapsedTime = clock.millis();   // 原 currentTimeMillis() inline
        long totalTime = ttlSeconds * 1000;
        double usedRatio = (double) elapsedTime / totalTime;
        return usedRatio >= (1 - threshold);
    }
}
```

逻辑自 `DefaultTtlPolicy.shouldEarlyExpiration` **byte-for-byte 迁入**(私有 `currentTimeMillis()` helper inline 为 `clock.millis()`,等价)。`Clock` 字段随之回归 refresh 域。

### D3 — `TtlPolicy` 删 `shouldEarlyExpiration`(执行)

接口从 3 方法缩为 2 方法(`shouldApply` / `calculateFinalTtl`),javadoc 移除「EarlyExpirationHandler 依赖本接口」+ 补 ADR-0025 迁出说明。回归纯雪崩(抖动)关注。

### D4 — `DefaultTtlPolicy` 无状态化(执行)

删除:`Clock` 字段、`currentTimeMillis()` helper、`shouldEarlyExpiration` 方法、`@RequiredArgsConstructor`、`import lombok.RequiredArgsConstructor` / `import java.time.Clock`。保留 `@Component`(`TtlHandler` 经接口注入)。类从「有状态持 Clock」退化为「无状态纯 TTL 数学」。

### D5 — `EarlyExpirationHandler` 切换依赖(执行)

字段 / 构造参数 `TtlPolicy ttlPolicy` → `EarlyExpirationPolicy earlyExpirationPolicy`;调用 `ttlPolicy.shouldEarlyExpiration(...)` → `earlyExpirationPolicy.shouldRefresh(...)`;移除 `import protection.avalanche.TtlPolicy`(新接口同包 `protection.refresh`,免 import)。refresh↔avalanche 跨包依赖**归零**。

### D6 — 歧路否决:inline 进 `EarlyExpirationHandler` 而非新建 policy(撤销)

歧路:不新建 `EarlyExpirationPolicy`,直接把 `shouldRefresh` 作为 private 方法 inline 进 `EarlyExpirationHandler`(handler 自注 `Clock`)。

**否决理由**:
1. 违反项目「每机制一可替换 policy seam」约定 —— `NullValuePolicy` / `LockManager` / `BloomIFilter` / `TtlPolicy` 四个机制均自有 policy seam 且 `@Bean` 可顶替(CLAUDE.md「策略替换」)。`refresh` 是唯一缺口,inline 会把缺口永久化。
2. 失去独立可测性 —— 当前 `shouldRefresh` 是纯函数,独立 seam 可用 `Clock.fixed()` 精确测试 9 个边界用例;inline 后只能经 handler 集成路径间接测,locality 倒退。
3. 决策点本就该可替换 —— 用户可能想自定义「何时触发刷新」策略(如基于访问频次而非纯时间占比),policy seam 是该扩展点的正确位置。

新建 `EarlyExpirationPolicy` 与 4 个 sibling policy 纪律一致,是把缺口补齐而非引入新抽象。

### D7 — 歧路否决:顺手修 `ChainEngineTest.executeFragment_skipsAroundChain` pre-existing 失败(撤销,超范围)

本轮全量回归暴露 `chain/ChainEngineTest.executeFragment_skipsAroundChain` 失败(Mockito `TooFewActualInvocations`:`chainObserver.beforeNode` 期望 2 次实际 1 次)。

**否决理由**:
1. **超 ADR-0025 范围** —— 本 ADR 是 refresh↔avalanche policy seam;ChainEngine observer 计数是 chain 域独立缺陷。
2. **已证非本 ADR 引入** —— `git stash -u` 后在干净 master 跑 `ChainEngineTest`,**同样失败**(16 tests / 1 failure / 同一用例 / 同一异常)。本 ADR diff 完全未触及 `chain/`。
3. **需独立根因分析** —— 失败涉及 `executeChainFragment` 路径的 observer 触发语义(ADR-0009 切片 3 / ADR-0022 index 推进),需判定是 test 期望错误还是 impl 行为错误,属独立调研,可能自成一 ADR。
4. **违 ADR 系列单 seam 纪律** —— 24 个 ADR 每个严格单一关注点 + byte-for-byte 等价;混修异域 bug 破坏审计链。

留作未来 round 的 chain 域排查候选。

### D8 — 歧路否决:不复活「`TtlPolicy` 同时承载两域」的统一抽象(撤销)

歧路:保留 `shouldEarlyExpiration` 在 `TtlPolicy`,理由「抖动与提前过期都关于 TTL 时间,单一策略类内聚相关判定」。

**否决理由**:这是 `ttl-jitter.md` 旧文写成「设计优点」的 ISP 违反。两个判定的**消费者不同**(抖动 → `TtlHandler` PUT 路径;提前过期 → `EarlyExpirationHandler` GET 路径)、**关注点不同**(抖动 = 写时打散;提前过期 = 读时主动刷新)、**配置入口不同**(抖动看 `variance`/`randomTtl`;提前过期看 `earlyExpirationThreshold`/`enableEarlyExpiration`)。强行内聚 = 让 `EarlyExpirationHandler` 跨包依赖 avalanche 拿本属于自己的判定。

## 后果

**增益(locality + leverage + 接口诚实 + 依赖归正)**:

1. **依赖方向归正**:refresh↔avalanche 跨包依赖**归零**(`EarlyExpirationHandler` 不再 import avalanche)。
2. **`DefaultTtlPolicy` 无状态化**:丢 `Clock` 字段 + `currentTimeMillis()` + `@RequiredArgsConstructor` → 纯 TTL 数学,可测性更强。
3. **5 机制 policy seam 齐整**:`bloom`/`breakdown`/`nullvalue`/`avalanche`/`refresh` 各有自有 policy seam + `@Bean` 可顶替 —— refresh 缺口补齐。
4. **interface 文档诚实化**:`ttl-jitter.md` / `early-expiration.md` 不再称 TtlPolicy 服务提前过期。
5. **`shouldRefresh` 独立可测**:9 边界用例 byte-for-byte 迁自旧 `ShouldEarlyExpirationTests`。

**代价**:
- 新增 2 main + 1 test 文件 —— 但它们是 5 机制 policy 模式的补齐,非冗余抽象。
- `EarlyExpirationHandler` 构造参数类型 `TtlPolicy` → `EarlyExpirationPolicy`(Spring 自动解析新 `@Component`,2 处单测 mock 类型同步)。

**不变**:
- 决策逻辑 byte-for-byte 等价(同守卫 + 同 `usedRatio >= 1-threshold` 公式 + 同 `clock.millis()`)
- `TtlHandler` 零变化(仍用 `TtlPolicy.shouldApply` / `calculateFinalTtl`)
- `systemClock` @Bean 零变化(消费方从 `DefaultTtlPolicy` 改为 `DefaultEarlyExpirationPolicy`)
- 默认行为零回归

## 规模与性质诚实声明

本 ADR 规模与 ADR-0024 同级(单域 locality + 跨域接缝性质),但**摩擦性质更立体**:ADR-0024 修「dead config + 文档撒谎」;本 ADR 修「跨域寄生方法 + 基础设施依赖寄生 + ISP 违反 + 文档把违反写成优点 + 可替换 seam 缺口」五合一。

发现路径:round 17 延续 round 16 的**跨域接缝视角**,反过来用 —— round 16 找「config 域暴露字段 ↔ 实际消费者」(dead config);round 17 找「一个域的接口方法 ↔ 唯一异域消费者」(寄生方法)。`DefaultTtlPolicy.Clock` 字段「仅服务寄生方法」是 deletion test 的天然确证信号。

## 实施

### 修改(3 main 改 + 2 main 新 + 3 test 改 + 1 test 新 + 2 wiki + 1 ADR + log/index)

**main 新建**:
- `protection/refresh/EarlyExpirationPolicy.java` —— 决策接口(`shouldRefresh`)
- `protection/refresh/DefaultEarlyExpirationPolicy.java` —— `@Component` + Clock 注入,逻辑迁入

**main 修改**:
- `protection/avalanche/TtlPolicy.java` —— 删 `shouldEarlyExpiration` + javadoc 迁出说明
- `protection/avalanche/DefaultTtlPolicy.java` —— 删 Clock/currentTimeMillis/shouldEarlyExpiration/@RequiredArgsConstructor → 无状态
- `protection/refresh/EarlyExpirationHandler.java` —— 依赖 `TtlPolicy` → `EarlyExpirationPolicy`,调用 `shouldRefresh`,移除 avalanche import

**test 新建**:
- `protection/refresh/DefaultEarlyExpirationPolicyTest.java` —— 9 边界用例(迁自 `DefaultTtlPolicyTest.ShouldEarlyExpirationTests`,方法名 `shouldEarlyExpiration_*` → `shouldRefresh_*`)

**test 修改**:
- `protection/avalanche/DefaultTtlPolicyTest.java` —— 删 `ShouldEarlyExpirationTests` 嵌套类 + 去 `@Mock Clock`
- `protection/refresh/EarlyExpirationHandlerTest.java` —— `@Mock DefaultTtlPolicy` → `@Mock EarlyExpirationPolicy`,6 处 `shouldEarlyExpiration` → `shouldRefresh`
- `protection/refresh/EarlyExpirationHandlerRaceConditionTest.java` —— 同上(5 处 stub)+ 1 处 stale 注释

**test 不动**:`protection/avalanche/TtlHandlerTest.java`、`integration/PathCAopContractIT.java`

**wiki**:
- `mechanisms/early-expiration.md` —— 决策归属改述 + source-files 换 + updated 日期
- `mechanisms/ttl-jitter.md` —— 删「TtlPolicy 服务提前过期 / 共享 Clock」段落 + Clock 注入说明改述 + 相关链改述 + updated 日期

### 验证

- `mvnw checkstyle:check` —— **0 violations**
- `mvnw test -Dtest='DefaultTtlPolicyTest,DefaultEarlyExpirationPolicyTest,EarlyExpirationHandlerTest,EarlyExpirationHandlerRaceConditionTest,TtlHandlerTest'` —— **60 tests, 0 failures**(9 迁移用例 byte-for-byte 等价)
- `mvnw test`(全量)—— 782 tests,**1 pre-existing failure**:`chain/ChainEngineTest.executeFragment_skipsAroundChain`
  - **已证非本 ADR 引入**:`git stash -u` 后在干净 master 跑 `ChainEngineTest`,**同样失败**(16 tests / 1 failure / 同一用例 / 同一 `TooFewActualInvocations: beforeNode wanted 2, was 1`)。本 ADR diff 完全未触及 `chain/`。
  - 性质:chain 域 `executeChainFragment` observer 触发语义的 pre-existing 缺陷,需独立根因分析,留作未来 round 候选(见 D7)。

## 参考

- ADR-0024:同款跨域接缝视角(config↔refresh dead config),本 ADR 反向用之
- ADR-0016:ObserverRegistry 跨 engine 去重 seam —— 跨域内聚先例
- ADR-0005:handlers 可替换长寿对冲 —— 5 机制 policy seam 齐整的纪律源头
- Ousterhout《A Philosophy of Software Design》—— deep module / "the interface is the test surface" / ISP
- `/improve-codebase-architecture` skill —— deletion test / misplaced seam 词汇
