# ADR-0009: Chain Engine 抽出 — 责任链推进与观测收口到单一 seam

- **Status**: Proposed
- **Date**: 2026-06-30
- **Deciders**: DavidHLP
- **Related**: ADR-0002 / ADR-0007 / ADR-0008 /
  `wiki/architecture/chain-of-responsibility.md` /
  `wiki/architecture/handler-result-control.md` /
  `wiki/modules/observability.md`

## Context

责任链的"推进引擎"被切成两半,observability 关注点散在 2 个类 4 处:

| 位置 | 关注点 | 文件 |
|---|---|---|
| `CacheHandlerChain.execute` | head handle + post-process + Timer + MDC requestId stamp | `chain/CacheHandlerChain.java` |
| `AbstractCacheHandler.handle` | 中间节点推进 + skip-remaining 物化 + decision switch + DEBUG log + fired counter | `chain/AbstractCacheHandler.java` |

WS-1.4 计划(ADR-0008)v0.1.0 引 Observation Span 时,需要在两处同时加代码才能 trace
per-handler span — 切 2 处 seam 必漏一边。

事实核验(2026-06-30,基于 working tree):

1. **5+ handler 子类 doHandle 100% 兼容** — `BloomFilterHandler` / `SyncLockHandler` /
   `EarlyExpirationHandler` / `TtlHandler` / `NullValueHandler` / `ActualCacheHandler`
   的 doHandle 实现均只读 `context`,返回 `HandlerResult`,无对 `getNext()` /
   `firedCounter` 基类模板代码的依赖。
2. **唯一例外**:`SyncLockHandler.executeChainInLock` 锁内手动 `getNext().handle()` —
   走 `engine.executeChainFragment` 显式入口解决。
3. **observability 三件套现状**:MDC `requestId` 键 + `resicache.chain.execute` Timer +
   `resicache.handler.fired` counter + `[chain]` DEBUG 日志,分布在 2 个文件 4 处。
4. **PostProcessHandler** 只被 `BloomFilterHandler` 1 个 handler 实现;契约需保留
   (PUT 成功后加 key 必须走 `afterChainExecution`,不能在 `doHandle` 内联)。

## Decision

### D1:抽 `ChainEngine` seam(主推进者)

新增 `chain/ChainEngine.java`:`@Component` 注入,`ObjectProvider<MeterRegistry>` 优雅
降级;`execute(CacheContext)` 一次扫完所有节点,集中 skip-remaining 短路、decision switch、
节点级 DEBUG / fired counter / Timer 包裹 / MDC stamp、post-process phase;暴露
`executeChainFragment(CacheContext, CacheHandler from)` 给 `SyncLockHandler` 锁内路径。

### D2:抽 `ChainObserver` seam(观测注入点)

新增 `chain/ChainObserver.java` 接口(4 钩子,default no-op):
- `onChainStart(CacheContext)` / `onChainEnd(CacheContext, CacheResult)` 管整条链边界
  (MDC + Timer 必须,aroundChain 正交);
- `beforeNode(CacheHandler, CacheContext)` / `afterNode(CacheHandler, CacheContext,
  HandlerResult)` 管每节点粒度(DEBUG + counter + Span child,perNode 正交)。

内置 4 observer:`NoOpChainObserver`(测试用)/ `MDCStampChainObserver` /
`FiredCounterChainObserver` / `ChainDebugLogChainObserver` / `ChainTimerChainObserver`
(从 `CacheHandlerChain` 与 `AbstractCacheHandler` 提取)。

**WS-1.4 升级路径**:`SpanObserver implements ChainObserver`(~50 SLOC 新文件),Engine
零修改,4 内置 observer 零修改,5+ handler 子类零修改 — 这是 D1-D2 的 leverage 兑现。

### D3:`AbstractCacheHandler` 退化为 default impl(~170 SLOC → ~50 SLOC)

只保留 `next` 字段 / `getNext()` / `setNext()` / `attachMeterRegistry()` /
`firedCounter` 字段 / 抽象 `shouldHandle()` / `doHandle()`。handle 模板代码全部迁到
`ChainEngine.executeNode`。

### D4:`CacheHandlerChain` 退化为 thin facade(~248 SLOC → ~30 SLOC)

只保留 `@Component` 注入(back-compat `@ConditionalOnMissingBean` 兜底)、委托给
`ChainEngine.execute` / `addHandler` / `size` / `clear` / `getHandlerNames`、`MDC_REQUEST_ID_KEY`
常量(供 observer 引用)、`cachedChain` 字段(被 `RedisProCacheWriter` 引用)。

### D5:`PostProcessHandler` interface 契约保留,遍历逻辑迁入 `ChainEngine`

失败 try/catch 不污染主链的契约保留。

### D6:`SyncLockHandler` 锁内路径(切片 3,不属切片 1 范围)

`executeChainInLock` 改调 `engine.executeChainFragment(ctx, getNext())` — 切片 1 完成后
仍走原 `getNext().handle()` 路径,行为不变。

## 切片计划(3 步渐进,每步可独立回滚)

| 切片 | 范围 | 风险 | 验收 |
|---|---|---|---|
| 1 | `ChainEngine` + `ChainObserver` + NoOp + MDCStamp;AbstractCacheHandler.handle 删 DEBUG log;CacheHandlerChain 退化为 facade | 低(facade 兜底) | `CacheHandlerChainTest` + factory test + 5+ handler 测试 全过 |
| 2 | 补 FiredCounter + DebugLog + Timer observer;AbstractCacheHandler 全部迁完;factory 装配 4 observer | 中 | 切片 1 验收 + `ChainEngineTest` 6 测试 + `ChainObserverTest` 4 测试 全过 |
| 3 | SyncLockHandler 注入 ChainEngine,executeChainInLock 改 executeChainFragment | 中 | 切片 1-2 验收 + SyncLockHandlerTest 锁内集成测试 全过 |

回滚预案:任何切片失败 = 删新增文件 + 还原 1 行 facade 委派 + 还原 1 个 handle 模板
代码块。最多损失半天。

## Consequences

### 正面

- 1 处定义链推进,5+ handler 简化(零修改)
- WS-1.4 OTel/Span 新增 = 写 1 个 Observer adapter,Engine 零修改
- 4 observer 各司其职,正交(MDC 必 aroundChain,DEBUG + counter 必 perNode)
- PostProcessHandler 不再 side-channel(主链完成后立即调,无独立 phase 边界)
- ADR-0008(Observation spans)直接受益 — 升级路径打通

### 负面

- 切片 1 引入 4 个新文件 + 3 个文件改动 — 改动面 ~600 SLOC(净 + ~150 SLOC,迁移后净减 ~50 SLOC)
- facade 退化后,`ChainObserver` 4 钩子设计若不当,WS-1.4 仍需重写 — **缓解**:采用业界标准
  aroundChain + perNode 模式(OpenTelemetry / Brave API 验证),双层正交天然
- `CacheHandlerChainTest` 现有 mock 形态可能与 facade 委派不一致 — **缓解**:切片 1 不动
  test 内容,仅作 facade 回归

### 不变

- 5+ handler 子类 doHandle 完全不变
- `@HandlerPriority` + `HandlerOrder` 排序机制不变
- `PostProcessHandler` interface 契约不变
- 用户 `@ConditionalOnMissingBean(RedisProCacheWriter)` / `@Bean CacheHandlerChain` 覆盖策略不变

## 触发重评估

- 任何 5+ handler 子类 doHandle 需要读 `getNext()` 或 `firedCounter`(目前为 0,出现需开新 ADR)
- WS-1.4 升级 Span 时需要 ChainObserver 不支持的钩子(如跨进程 trace propagation)
- 用户希望 `CacheHandlerChain` 类删除(而非 facade)— 破坏 back-compat,需 v0.2.0 BREAKING

---

**最后更新**:2026-06-30
**下次维护触发**:实现切片 1 提交 + 用户批准本 ADR → Status 升 Accepted
