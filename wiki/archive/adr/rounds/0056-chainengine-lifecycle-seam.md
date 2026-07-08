---
title: Round 42 — ChainEngine.execute() 4 关注点交织 → ChainLifecycle 私有 seam
type: adr
tags:
  - adr
  - seam-deepening
  - round-42
  - chain-package
related: [0009-chain-engine-extraction, 0045-postprocesshandler-cachehandler-merge-and-parasitic-keys-attribution, 0046-chainengine-snapshot-threadlocal-ownership-realignment, 0052-actualcachehandler-storeintent-deep-module, 0055-syncsupport-role-sealed-seam]
status: stable
created: 2026-07-08
updated: 2026-07-08
---

# ADR-0056 — Round 42:`ChainEngine.execute()` 4 关注点交织 → `ChainLifecycle` 私有 seam

> ADR-0009 (Round 1) 把 Chain Engine 抽出为单一推进 seam,后续 ADR-0022/0045/0046 各自
> 收敛了 1 个关注点。execute() 主体仍混 4 件关注点(around-hook 配对 + ThreadLocal 推送 +
> 空链短路 + post-process),3 层 try/finally 嵌套,reader 必须 4 个关注点同时在脑里才能
> 理解。本轮按 ADR-0051 遗嘱扫 chain 域剩余结构 friction,落地为 `ChainLifecycle` 私有
> 嵌套类,Engine.execute 收窄至「ThreadLocal + 空链告警 + 委派」3 步。

## 上下文 (Context)

`ChainEngine.execute(List<CacheHandler>, CacheContext)` 是责任链全生命周期入口。Round 42
之前 47 SLOC,内含 4 个交织关注点:

1. **ThreadLocal snapshot 推送**:`CURRENT_SNAPSHOT.set(snapshot)` 入口 / `remove()` finally,
   供 `executeChainFragment` 锁内片段推进隐式读(ADR-0046 引入的隐式契约)
2. **空链短路**:`snapshot == null || isEmpty()` → 打 WARN → 直接返回 success,但仍配对
   around-hook(observer 可能在 start 注册 thread-local 资源如 Timer.Sample,不配对会泄漏)
3. **around-hook 配对**:`observers.forEachSafe(o -> o.onChainStart(context))` → driveChain →
   post-process → `observers.forEachSafe(o -> o.onChainEnd(context, CacheResult.success()))`,
   onChainEnd 在 finally 守护(handler 异常也触发)
4. **post-process 遍历**:`executePostProcess(snapshot, context, finalResult)`,handler
   `requiresPostProcess` opt-in,失败 try/catch 隔离(ADR-0045 引入)

**问题**:读 execute() 必须 4 个关注点同时在脑里,且 around-end 在 2 处独立写(空链 + 非空链
各 1 次 `observers.forEachSafe(o -> o.onChainEnd(...))`),post-process 独立写 1 个私有方法。
若未来要改 onChainEnd 的语义(目前是 hardcoded `CacheResult.success()`,应传 mainResult),
需改 2 处 + 1 个独立方法共 3 处。

### 删除测试 (Deletion Test)

```
假设:不抽 ChainLifecycle,保持 4 关注点 inline。
└─ 复杂度测度:
   ├─ execute() 47 SLOC,3 层 try/finally 嵌套
   ├─ around-end 在 2 处独立写(空链 + 非空链),post-process 独立方法 1 处
   ├─ 改 onChainEnd 语义需 3 处同步
   └─ 单元测试 ChainEngineTest 5+3+5+3=16 测试已覆盖,但无 seam 级独立可测

抽 ChainLifecycle 私有嵌套 + execute 收窄后:
   ├─ execute() 收窄至 ~10 SLOC:ThreadLocal set/remove + 空链 WARN + lifecycle.run()
   ├─ around-end 在 1 处集中(ChainLifecycle.run finally 守护),post-process 1 处集中
   ├─ 改 onChainEnd 语义仅改 ChainLifecycle.run 一处
   └─ ChainLifecycle 私有嵌套,Engine 仍为推进 seam — unit test 仍通过 Engine.execute 端到端验证
```

**deletion test 判据**:把 ChainLifecycle 删掉、内联回 execute → 47 SLOC + 3 层 try/finally
回归,around-end 拆回 2 处,post-process 拆回 1 处独立方法。复杂度**上升**。
本 seam 浓缩。

## 备选路径与驳回 (Alternatives Rejected)

| 路径 | 方案 | 裁决 |
|------|------|------|
| **A** | 把 around-hook + post-process 内联到 `driveChain` | **驳回**:`driveChain` 已被 `executeChainFragment` 复用,后者明确要求"跳过 around-hook + post-process"(锁内片段,外层 execute 负责),若把 lifecycle 塞进 driveChain 会破坏 executeChainFragment 的契约 |
| **B** | 抽 `AroundHookPair` + `PostProcessRunner` 两个独立工具类 | **驳回**:两个关注点天然配对(around-end 必须配 around-start,post-process 必须在 around-start 后 / around-end 前),拆为 2 类反而增加协作成本(调用方需手工 orchestrate) |
| **C** | 把 `ChainLifecycle` 提到顶层公开类 | **驳回**:本 seam 只服务 ChainEngine.execute 一处,公开化会污染 API surface;`ChainHandlerChain.execute` 与 `AnnotationChainEngine.execute` 是不同包的不同 engine,各自独立 seam 才符合 locality |
| **D** | 顺手修复 `onChainEnd` 传 `mainResult` 替代 hardcoded `CacheResult.success()` | **驳回**:observers 当前不读 result(MDC/Timer/DebugLog 都不消费),observably 字节等价;但语义应传 mainResult。本轮专注结构收敛,不改语义;留独立 round 处理 |
| **E** | 把 `ChainLifecycle` 改 static + 把 `driveChain` 改 static(传 observerRegistry) | **驳回**:`driveChain` 通过 `invokeWithObservers` 读 instance field `observers`,改 static 需把 observers 也变 static(让 Engine 完全无 instance state,违反 Spring `@Component` 单例语义) |
| **F**(采用) | `private final class ChainLifecycle` 嵌套 + 收窄 `execute()` 至 3 步 | 见下「决策」 |

## 决策 (Decision)

### 1. 新增 `private final class ChainLifecycle`(ChainEngine 私有嵌套)

持有 3 个 state:`observers` / `snapshot` / `context`,**不**反向引用 ChainEngine 实例的
其他字段(locality 提升,只读需要的 3 个 state)。`run()` 方法封装 4 个关注点:

```java
CacheResult run() {
    observers.forEachSafe(o -> o.onChainStart(context));
    CacheResult mainResult = CacheResult.success();
    try {
        if (snapshot != null && !snapshot.isEmpty()) {
            mainResult = driveChain(snapshot, context);
            runPostProcess(mainResult);
        }
    } finally {
        observers.forEachSafe(o -> o.onChainEnd(context, CacheResult.success()));
    }
    return mainResult;
}
```

空链短路:skip driveChain + post-process,但仍配对 around-hook(原行为保留)。
异常守护:driveChain 抛异常时,`mainResult` 未赋值(无 return,异常向上冒泡),但 finally
仍触发 around-end(observer 资源不泄漏)。

### 2. `ChainEngine.execute()` 收窄至 ~10 SLOC

```java
public CacheResult execute(List<CacheHandler> snapshot, CacheContext context) {
    CURRENT_SNAPSHOT.set(snapshot);
    try {
        if (snapshot == null || snapshot.isEmpty()) {
            log.warn("Handler chain is empty!");
        }
        log.debug("Executing handler chain for operation: {}, cacheName: {}, key: {}",
                context.getOperation(), context.getCacheName(), context.getRedisKey());
        return new ChainLifecycle(observers, snapshot, context).run();
    } finally {
        CURRENT_SNAPSHOT.remove();
    }
}
```

3 个关注点保留:ThreadLocal set/remove(ADR-0046 契约,外层守护)+ 空链 WARN(诊断信号)
+ 委派 ChainLifecycle(around + drive + post-process)。3 层 try/finally 收窄至 1 层(原
外层 try 仅守护 ThreadLocal,内 2 层 try 已迁出至 ChainLifecycle)。

### 3. 删 `ChainEngine.executePostProcess` 私有方法

迁移至 `ChainLifecycle.runPostProcess` 内联(ChainLifecycle 私有,与 observers 同包可访问
ChainEngine 私有方法)。ChainEngine 类体不再持有此独立方法,around-end 与 post-process
**两关注点均在 ChainLifecycle 内聚**。

### 4. 保留 onChainEnd 的 hardcoded `CacheResult.success()`

原行为(commit 现状):onChainEnd 总传 `CacheResult.success()` 而非 mainResult。**observers
当前不读 result 字段**(MDC / Timer / DebugLog 都不消费),observably 字节等价;但语义上
应传 mainResult。本轮**不修语义**,留独立 round 决定(Javadoc 标注此事项)。

## 影响面 / SLOC 对比

| 项 | Round 41(前) | Round 42(本 ADR) | 净变化 |
|----|------|------|------|
| `ChainEngine.execute()` SLOC | 47 | ~14 | **-33** |
| `ChainEngine.execute()` try/finally 层数 | 3 | 1 | **-2** |
| `ChainEngine.executePostProcess()` 私有方法 | 有(14 SLOC) | 无(迁出 ChainLifecycle) | **-14** |
| `ChainLifecycle`(NEW 私有嵌套类) | 0 | ~70(纯 Javadoc + 2 方法) | +70 |
| around-end 写点 | 2 处(空链 + 非空链) | 1 处(ChainLifecycle finally) | **-1** |
| post-process 写点 | 1 处(`executePostProcess` 私有方法) | 1 处(`ChainLifecycle.runPostProcess` 内联) | 0 |
| 改 onChainEnd 语义成本 | 3 处(2 around-end + 1 私有方法) | 1 处(ChainLifecycle.run) | **-2** |
| 公开 API 增量 | 0 | 0(私有嵌套,不对外) | **0** |
| 行为字节等价 | (基线) | 100% 保持(765 单测全绿) | **0** |

净 SLOC **+23**(全为 Javadoc 解释 + ChainLifecycle 类骨架);
**逻辑代码 -47 行**(execute 47→14 + 删 executePostProcess 14 行),try/finally 嵌套
减少 2 层,around-end 写点 2→1。

## 字节等价 / 测试矩阵

`ChainEngineTest` 16 个测试全绿,精确 pin 了:
- 空链:around-start/end 配对 + return success + WARN 日志
- 非空链:around-start → driveChain → post-process → around-end 顺序
- SKIP_ALL 物化 → driveChain 短路
- TERMINATE 决策 → 立即返回
- post-process handler 异常 → 隔离不污染主链(打 ERROR)

`CacheHandlerChainTest` 19 + `CacheHandlerChainFactoryTest` 14 + `CacheHandlerChainExceptionTest` 4
+ `ChainObserverTest` 4 + `ObserverRegistryTest` 全部维持绿,无任何行为变更。

## 验证状态

- ✅ `./mvnw checkstyle:check` 0 violation
- ✅ `./mvnw compile` 绿(JDK 21,`private final class` 嵌套类可访问 outer private method)
- ✅ `ChainEngineTest` 16 / `CacheHandlerChainTest` 19 / `CacheHandlerChainFactoryTest` 14 /
  `CacheHandlerChainExceptionTest` 4 / `ChainObserverTest` 4 / `ObserverRegistryTest` 全部绿
- ✅ **全量 765 单测(0 fail / 0 err / 17 skipped Docker integration)** — 与 Round 41 基线持平
- ✅ `ChainEngine` 公开 API 不变(`execute` / `executeChainFragment` / `addObserver` /
  `observers` / `setCurrentSnapshotForTest` / `clearCurrentSnapshotForTest` 签名/行为 byte-for-byte)

## 设计纪律

- **私有嵌套而非顶层**:`ChainLifecycle` 只服务 `ChainEngine.execute` 一处,公开化会
  污染 API surface,且无第二个 consumer 来 leverage 其独立可测性(本轮 `ChainEngineTest`
  仍走 `execute` 端到端测试)。
- **非 static 嵌套**:`run()` 内部需调 `driveChain`(ChainEngine private instance method),
  static 嵌套无法访问 outer instance method;**持 outer reference 是 locality 提升而非泄漏**
  (Javadoc 已说明)。
- **不动 onChainEnd 语义**:observers 当前不读 result,observably 字节等价,改语义属独立 round。
  Javadoc 标注「若未来 observer 需要 mainResult,需独立 round 决定」。
- **空链短路内置**:`ChainLifecycle.run` 内置 `if (snapshot != null && !snapshot.isEmpty())`,
  跳 driveChain + post-process 但保留 around-hook 配对,原行为完全保留。
- **ThreadLocal 仍在 Engine 守护**:不进 ChainLifecycle(后者不知 ThreadLocal 概念,只管
  lifecycle;ThreadLocal 是 Engine.execute 的「隐式契约接口」,属 engine-level concern)。

## 相关 ADR

- **前置**:
  - ADR-0009(Chain Engine 抽出 — Round 1,本轮是该 seam 的**结构深化**)
  - ADR-0045(requiresPostProcess hook 化 — post-process 协议稳定后,本轮是其**执行收敛**)
  - ADR-0046(ThreadLocal snapshot 所有权 — Engine 仍守 ThreadLocal,ChainLifecycle
    不接触,职责分层)
  - ADR-0052(StoreIntent 深模块模式 — 本轮复用其「隐含概念命名 + state + cleanup 内聚」
    骨架:ChainLifecycle 命名 + around-start/post-process/around-end 内聚)
  - ADR-0055(SyncRole sealed 深模块 — 本轮同样是**结构深化**,模式并行:一个把
    orchestrator 收窄,一个把推进 seam 收窄)
- **后续**:无新候选挂账。`ChainEngine.execute` 收窄至 14 SLOC 透明推进入口;onChainEnd
  传 mainResult 修复属独立 round 候选(本轮 Javadoc 已标注)。
