# ADR-0009: Chain Engine 抽出 — 责任链推进与观测收口到单一 seam

- **Status**: Proposed
- **Date**: 2026-06-30
- **Deciders**: DavidHLP
- **Related**: ADR-0002 / ADR-0007 / ADR-0008 / `wiki/architecture/chain-of-responsibility.md` / `wiki/architecture/handler-result-control.md` / `wiki/modules/observability.md` / improve-codebase-architecture 评审(`/tmp/architecture-review-1782800487.html` 候选 ② + deep-dive)

## Context

责任链的"推进引擎"被切成两半,observability 关注点散在 2 个类 4 处:

| 位置 | 关注点 | 文件:行 |
|---|---|---|
| `CacheHandlerChain.execute` | head handle + post-process phase + `resicache.chain.execute` Timer 包裹 + MDC requestId stamp | `chain/CacheHandlerChain.java:107-149` |
| `AbstractCacheHandler.handle` | 中间节点推进 + skip-remaining 物化 + decision switch + `[chain]` DEBUG 日志 + `resicache.handler.fired` counter 自增 | `chain/AbstractCacheHandler.java:78-118` |

WS-1.4 计划(ADR-0008)v0.1.0 引 Observation Span 时,需要在两处同时加代码才能 trace per-handler span — 切 2 处 seam 必漏一边。

**事实核验**(2026-06-30,基于 working tree):
1. **5+ handler 子类 doHandle 100% 兼容** — BloomFilterHandler / SyncLockHandler / EarlyExpirationHandler / TtlHandler / NullValueHandler / ActualCacheHandler 的 doHandle 实现均只读 `context`,返回 `HandlerResult`,无任何对 `getNext()` / `firedCounter` 基类模板代码的依赖。grilling 兼容硬约束满足。
2. **唯一例外**:`SyncLockHandler.executeChainInLock` 在锁内手动 `getNext().handle(context)` — 走 `engine.executeChainFragment` 显式入口解决(切片 3)。
3. **observability 三件套现状**:MDC `requestId` 键 + `resicache.chain.execute` Timer + `resicache.handler.fired` counter + `[chain]` DEBUG 日志,分布在 `CacheHandlerChain` 与 `AbstractCacheHandler` 2 个文件 4 处。
4. **PostProcessHandler** 只被 `BloomFilterHandler` 1 个 handler 实现;契约需保留(布隆 PUT 成功后加 key 必须走 `afterChainExecution`,不能在 `doHandle` 内联 — 否则会污染"主链失败"语义)。

## Decision

### D1:抽 `ChainEngine` seam(主推进者)

新增 `chain/ChainEngine.java`:
- 接受 `List<CacheHandler>` 与 `List<ChainObserver>` 不可变引用
- `execute(CacheContext)` 一次扫完所有节点,集中:
  - skip-remaining 短路
  - 决策 switch(CONTINUE / TERMINATE / SKIP_ALL)
  - 节点级 DEBUG 日志 / fired counter / Timer 包裹 / MDC stamp
  - post-process phase(`PostProcessHandler.afterChainExecution` 单独遍历,失败不污染主链)
- 暴露 `executeChainFragment(CacheContext, CacheHandler from)` 给 `SyncLockHandler` 锁内路径(切片 3)
- `@Component` 注入,`ObjectProvider<MeterRegistry>` 优雅降级

### D2:抽 `ChainObserver` seam(观测注入点)

新增 `chain/ChainObserver.java` 接口(4 钩子,default no-op):
- `onChainStart(CacheContext)` — 整条链开始(MDC stamp / Timer 起始 / Span root open)
- `beforeNode(CacheHandler, CacheContext)` — 每个被引擎求值的 handler 前(per-handler DEBUG / fired counter / Span child open)
- `afterNode(CacheHandler, CacheContext, HandlerResult)` — handler 返回后
- `onChainEnd(CacheContext, CacheResult)` — 整条链结束(Timer record / Span root close)

**双层正交**:`aroundChain` 钩子(`onChainStart`/`onChainEnd`)管整条链边界(MDC + Timer 必须),`perNode` 钩子(`beforeNode`/`afterNode`)管每节点粒度(DEBUG + counter + Span child)。

**内置 4 observer**(`chain/observers/`):
- `NoOpChainObserver` — 空实现,测试用
- `MDCStampChainObserver` — MDC put/clear/restore(从 `CacheHandlerChain` 提取)
- `FiredCounterChainObserver` — `resicache.handler.fired` 自增(从 `AbstractCacheHandler` 提取)
- `ChainDebugLogChainObserver` — `[chain] handler={} decision={} key={} requestId={}` DEBUG 日志(从 `AbstractCacheHandler` 提取)
- `ChainTimerChainObserver` — `resicache.chain.execute` Timer 包裹(从 `CacheHandlerChain` 提取)

**WS-1.4 升级路径**:`SpanObserver implements ChainObserver`(~50 SLOC 新文件),Engine 零修改,4 内置 observer 零修改,5+ handler 子类零修改 — **这正是 D1-D2 的 leverage 兑现**。

### D3:`AbstractCacheHandler` 退化为 default impl(只留 `next` 字段 + `doHandle`/`shouldHandle` 抽象)

**目标**:~170 SLOC → ~50 SLOC,只保留:
- `private CacheHandler next;`
- `public CacheHandler getNext() / setNext(CacheHandler);`
- `public void attachMeterRegistry(MeterRegistry);`(子类的 `onAttachMetrics` 钩子保留)
- `protected Counter firedCounter;` 字段(语义 counter 仍按 handler 自身注册)
- `protected abstract boolean shouldHandle(CacheContext);`
- `protected abstract HandlerResult doHandle(CacheContext);`

**handle 模板代码**(`if skip / shouldHandle / decision switch / DEBUG log / counter / getNext().handle()`)**全部删除**,迁到 `ChainEngine.executeNode`。
`safeIncrement`、`registerCounter` helper 保留(子类用)。

### D4:`CacheHandlerChain` 退化为 thin facade

**目标**:~248 SLOC → ~30 SLOC,只保留:
- `@Component` 注入(back-compat `@ConditionalOnMissingBean` 用户覆盖)
- 委托给 `ChainEngine.execute` / `addHandler` / `size` / `clear` / `getHandlerNames`
- `MDC_REQUEST_ID_KEY` 常量保留(供 observer 引用)

`cachedChain` 字段保留(被 `RedisProCacheWriter` 引用),委派给 engine。

### D5:`PostProcessHandler` 折叠到 `ChainEngine` 内 phase,但**接口保留 public**

- `PostProcessHandler` interface 不删除 — 5+ handler 子类(仅 `BloomFilterHandler` 1 个)继续 `implements PostProcessHandler`,契约零变化
- 遍历逻辑从 `CacheHandlerChain.executePostProcess` 迁到 `ChainEngine.executePostProcess`(在 `onChainEnd` observer 之前)
- 失败 try/catch 不污染主链的契约保留

### D6:`SyncLockHandler` 锁内路径(切片 3,不属本 ADR 的"切片 1 最小可逆"范围)

`SyncLockHandler` 注入 `ChainEngine` 引用,`executeChainInLock` 改调 `engine.executeChainFragment(ctx, getNext())` — **本 ADR 不实现,留切片 3**。本 ADR 切片 1 完成后,`SyncLockHandler` 仍走原 `getNext().handle()` 路径(基类 `getNext()` 委托给 `CacheHandlerChain` 的 next 字段),行为不变。

## Consequences

### 正面
- 1 处定义链推进,5+ handler 简化(零修改)
- WS-1.4 OTel/Span 新增 = 写 1 个 Observer adapter,Engine 零修改
- 4 个内置 observer 各司其职,正交(MDC 必 aroundChain,DEBUG + counter 必 perNode)
- PostProcessHandler 不再 side-channel(主链完成后立即调,无独立 phase 边界)
- ADR-0008(Observation spans)直接受益 — 升级路径打通

### 负面 / 风险
- 切片 1 引入 4 个新文件(ChainEngine + ChainObserver + 2 内置 observer)+ 3 个文件改动 — 改动面 ~600 SLOC(净 + ~150 SLOC,迁移后净减 ~50 SLOC)
- facade 退化后,`ChainObserver` 接口的 4 钩子设计若不当,WS-1.4 仍需重写 — **缓解**:采用业界标准 aroundChain + perNode 模式(OpenTelemetry / Brave API 验证),双层正交天然
- `CacheHandlerChainTest` 现有 mock 形态可能与 facade 委派不一致 — **缓解**:本 ADR 切片 1 不动 `CacheHandlerChainTest` 内容(零修改),仅作 facade 回归

### 不变
- 5+ handler 子类 `doHandle` 实现完全不变
- `@HandlerPriority` + `HandlerOrder` 排序机制不变
- `PostProcessHandler` interface 契约不变
- `BloomFilterHandler.handleClean` → `PostProcessHandler.afterChainExecution` 联动不变
- 用户 `@ConditionalOnMissingBean(RedisProCacheWriter)` / `@Bean CacheHandlerChain` 覆盖策略不变

### 触发重评估条件
- 任何 5+ handler 子类的 doHandle 需要读 `getNext()` 或 `firedCounter`(目前为 0,出现需开新 ADR)
- WS-1.4 升级 Span 时需要 ChainObserver 不支持的钩子(如跨进程 trace propagation)— 扩 `ChainObserver` 接口或加新接口
- 用户希望 `CacheHandlerChain` 类删除(而非 facade)— 破坏 back-compat,需 v0.2.0 BREAKING 流程

## 取代 / 被取代关系

- **取代**:`CacheHandlerChain.execute` + `AbstractCacheHandler.handle` 内的 engine 双轨(均迁到 `ChainEngine.execute`)
- **被取代**:无(本 ADR 是新增 seam + 退化现有类,无被取代项)
- **增强**:`PostProcessHandler` interface(契约保留,遍历逻辑迁入 Engine)
- **不影响**:5+ handler 子类 / `CacheHandlerChainFactory` 装配流程 / `RedisProCacheWriter` 入口 / `RedisProCache` 单实例 / `CacheContext` 数据流

## 切片计划(3 步渐进,每步可独立回滚)

| 切片 | 范围 | 文件 | 风险 | 验收 |
|---|---|---|---|---|
| **1**(本 ADR 当前) | 抽 `ChainEngine` + `ChainObserver` + NoOp + MDCStamp;`AbstractCacheHandler.handle` 删除 DEBUG log;`CacheHandlerChain` 退化为 facade | 4 新 + 3 改 | 低(facade 兜底) | `CacheHandlerChainTest` + `CacheHandlerChainFactoryTest` + 5+ handler 测试 全过 |
| 2 | 补 `FiredCounterChainObserver` + `ChainDebugLogChainObserver` + `ChainTimerChainObserver`;`AbstractCacheHandler` 全部迁完(降到 default impl);`CacheHandlerChainFactory` 装配 4 observer | 0 新 + 3 改(4 observer 在切片 1 同步建) | 中(observability 单 seam 化) | 切片 1 验收 + `ChainEngineTest` 6 测试 + `ChainObserverTest` 4 测试 全过 |
| 3 | `SyncLockHandler` 注入 `ChainEngine`,`executeChainInLock` 改 `engine.executeChainFragment`;`ChainEngine` 暴露 fragment 入口 | 0 新 + 2 改 | 中(锁内路径改) | 切片 1-2 验收 + `SyncLockHandlerTest` 锁内集成测试 全过 |

**回滚预案**:任何切片失败 = 删新增文件 + 还原 1 行 facade 委派 + 还原 1 个 `AbstractCacheHandler.handle` 模板代码块。最多损失半天。

## 后续轮次引用

- 切片 2 / 3 不需新 ADR,沿本 ADR 路径
- WS-1.4 Observation Span 升级(ADR-0008 后续)需新增 `SpanObserver`,在本 ADR 框架下零 Engine 改动
- v0.2.0 同步双 Advisor 调整(ADR-0002 Path C 后续)若涉及 `SyncLockHandler.executeChainInLock` 路径,沿切片 3 改
