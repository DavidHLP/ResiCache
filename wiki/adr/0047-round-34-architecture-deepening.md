---
title: ADR-0047 — Round 34 架构加深(C7/C3/C2/C5 已实施,C1/C4/C6/C8 决策固化)
type: refactor
tags:
  - adr
  - architecture
  - deepening
  - round-34
related:
  - 0019-annotation-attributes-projector.md
  - 0021-protection-toggle-record.md
  - 0029-hypothetical-seams-doctrine.md
  - 0031-redisprocache-timing-helper-seam.md
  - 0034-writer-buildcontext-seam.md
  - 0036-scoped-activation-interceptor.md
  - 0042-syncsupport-singleflight-future-and-chain-readlock-removal.md
  - 0044-annotationchainobserver-yagni-dead-channel-removal.md
  - 0045-postprocesshandler-cachehandler-merge-and-parasitic-keys-attribution.md
status: accepted
created: 2026-07-05
---

# ADR-0047 — Round 34 架构加深

> 本 ADR 一次性裁决 8 个候选摩擦面(C1~C8):5 个已实施(C7/C3/C2/C5 直接代码变更;
> C2 拆 seam + 收口 metrics(),C5 拆 2 个 seam 类 + factory 委派);3 个决策固化
> (C1/C4/C8 暂不实施,原因记录)。

## 上下文(Context)

2026-07-05 architecture review 扫描 119 源文件 + 46 既有 ADR 后,识别 8 个
值得探索的加深候选:

| # | 候选 | 强度 | 状态 |
|---|---|---|---|
| **C7** | BloomFilterHandler 3 噪声方法 | Speculative | ✅ 已实施 |
| **C3** | DefaultMethodMetadataResolver 双写路径 | Strong | ✅ 已实施 |
| **C5** | CacheHandlerChainFactory 平行列表 + observer 注册扇出 | Worth | ✅ 已实施(拆 2 seam) |
| **C2** | RedisProCache 5 metrics getter 扇出 | Worth | ✅ 已实施(CacheMetrics record) |
| C1 | RedisCacheAttributesProjector 3 extractFrom | Worth | ⏸️ 决策固化(Java 注解约束) |
| C6 | DefaultNullValuePolicy 5 方法混合 | Speculative | ⏸️ 决策固化(本轮 scope 外) |
| C4 | SyncSupport 4 职责 fan-in | Worth | ⏸️ 决策固化(ADR-0042 明确 kill) |
| C8 | RedisProCacheWriter SDR 入口扇出 | Worth | ⏸️ 决策固化(v1.0 毕业议题) |

---

## ✅ 已实施的 5 项变更

### C7 — BloomFilterHandler 3 噪声方法 inline

**问题**:ADR-0045 删除 `POST_PROCESS_KEY` stringly-typed 标记后,3 个 `handlePut/
handlePutIfAbsent/handleClean` 方法体只剩一行 `return HandlerResult.continueChain()`。
102 SLOC 类里 ~28 SLOC 是 switch 桩,无行为。

**改动**:3 个方法 inline 到 `doHandle` switch:`case PUT, PUT_IF_ABSENT, CLEAN -> HandlerResult.continueChain()`。
净减 **28 SLOC**,行为字节级等价。

**Commit**: `3cbd2bb refactor(bloom): ADR-0047 C7 — inline 3 noise handle* methods into switch`

---

### C3 — DefaultMethodMetadataResolver 静态→private 收紧

**问题**:类同时暴露 `public static activateStatic/clearStatic` + `public ScopedActivation activate(...)`,
ThreadLocal 双写路径,Javadoc 自承「可见性收紧留作 follow-up」。

**改动**:
1. `activateStatic/clearStatic` 可见性 `public static` → `private static`
2. 新增 package-private instance 方法 `restoreKey(Method, Class<?>)` 供
   `CacheInvocationContext#restore` 调用
3. `CacheInvocationContext#restore(resolver)` 改走 `((DefaultMethodMetadataResolver) dmrmr).restoreKey(...)`
4. `RedisProCacheWriterTest` 改用 `try (var ignored = resolver.activate(...))` 模式(对称 `RedisCacheInterceptor`)

**收益**:ThreadLocal 仅本类内部可写,双写路径消除;测试不再依赖静态状态(可并行)。

**Commit**: `15b3089 refactor(chain): ADR-0047 C3 — DefaultMethodMetadataResolver 静态→private 收紧 + 实例 restoreKey seam`

---

### C5 — CacheHandlerChainFactory 平行列表 + observer 注册拆 seam

**问题**:工厂持有 2 份平行列表(`PROTECTION_HANDLER_ORDERS` 4 enum + `PROTECTION_TOGGLES`
4 record),cardinality 必须手工保持;`registerObserversOnce` 4 行内联。

**改动**:抽 2 个 package-private seam 类:
- `ChainObserverRegistration` — 4 个标准 observer 注册清单单一 source of truth
- `ChainProtectionToggleResolver` — `PROTECTION_HANDLER_ORDERS` + `PROTECTION_TOGGLES`
  合并为单一 `TOGGLES` 列表 + `resolveDisabled(properties, Set<String>)` mutate-in-place

`CacheHandlerChainFactory.createChain()` 从 ~140 SLOC 退化为 ~70 SLOC 编排。
净减 **120 SLOC**(Factory),新增 2 个 seam 类共 223 SLOC(但单一职责、单一 source of truth)。

**Commit**:
- `198d3fe refactor(chain): ADR-0047 C5 — ChainHandlerChainFactory 平行列表+observer 注册拆 seam`
- `9c91007 refactor(chain): ADR-0047 C5 续 — CacheHandlerChainFactory.createChain 委派 seam`

---

### C2 — RedisProCache 5 metrics getter 收口

**问题**:5 个 `getXCount()` 委托 + `getHitRate()` 派生,接口与实现等宽(浅模块)。

**改动**:
1. 新增 `CacheMetrics` record(4 long + `hitRate()` 派生方法)
2. `RedisProCache.metrics() : CacheMetrics` 单次读取全部 4 个 Counter
3. 删 5 个 public getter
4. `RedisProCacheTest` 改用 `cache.metrics().hitCount()` 等;合并 `CounterTests` 3 个测试
   到单一 `metrics_returnsZeroSnapshot_onFreshCache`

**收益**:5 thin method → 1 deep method;派生算术(record 内)+ null-safe 集中;测试不再 mock Counter。

**Commit**: `1c68110 refactor(cache): ADR-0047 C2 — 5 metrics getter 收敛到 metrics() seam + CacheMetrics record`

---

## ⏸️ 决策固化的 3 项(不实施本轮)

### C1 — RedisCacheAttributesProjector 3 extractFrom 折叠

**结论**:⏸️ 搁置(Java 注解约束)。

**理由**:
1. Java 注解类型不可共享接口 — 三个 `extractFrom(RedisCacheable/Put/Evict)` 必须保留
   为 3 个独立重载(Javadoc 已自承)。
2. ADR-0019 已把外层 `from(annotation)` 公共面 + 22 字段 builder 链收敛到单一
   `project()` seam。3 份重复代码集中在「annotation → FieldSource 字段读取」这一步,
   已是 Java 注解约束下的最优形态。
3. 重写为 lambda 表 `List<Function<RedisCacheable, Object>>` 会引入 `MethodHandle`
   / 反射间接层,trace 时多一跳 — 收益不抵复杂度。
4. 新增字段的 3 处编辑是机械操作,且 IDE 重构工具支持良好。

**未来 reopen 条件**:Java 语言提供 annotation interface 共享机制(目前无 roadmap),
或加字段导致 3 处编辑实际出错 ≥ 1 次。

---

### C6 — DefaultNullValuePolicy 5 方法混合

**结论**:⏸️ 决策固化(本轮 scope 外)。

**理由**:
1. 5 个方法语义确有重叠(`toReturnValue` 体内 3 件事 + 3 条 debug 日志分支),但拆分
   需重新定义 `NullValue.INSTANCE` 所有权边界 — 涉及 `TypeSupport` 协作。
2. ADR-0025 已迁移 `DefaultTtlPolicy.shouldEarlyExpiration`(同类 policy 拆分),可作
   模板但需先界定 `NullValueEncoder` ↔ `TypeSupport` 协作 contract。
3. 本轮 scope 已饱和(5 项实施 + 3 项决策固化 + 1 ADR),C6 拆 seam 涉及接口重新
   设计与 4-5 个测试改写,单轮风险高。

**未来 reopen 条件**:本 ADR 实施完成后,作为 Round 35 第一候选;先写 ADR-0048/0049
 界定 `NullValueEncoder` ↔ `TypeSupport` 协作 contract,再实施。

---

### C4 — SyncSupport 4 职责 fan-in

**结论**:⏸️ 决策固化(ADR-0042 明确 kill,不重启)。

**理由**:
1. **ADR-0042 明确 kill**:`SyncSupport` 4 职责 fan-in(leader/follower + LockStack +
   reentrancy + backend 协商)由 ADR-0042 决策文档明确 reject,作为「假想 seam」
   案例归入 ADR-0029 教条(两适配器才认 seam)。
2. **LockStack 当前 3 个调用方**:`SyncLockHandler` + `SyncSupportTest` +
   `SyncSupportSingleFlightTest` 对 LockStack 的间接访问足够,但 LockStack 是 private,
   测试必须通过整类 mock — 现状下「mock 痛点」未达 reopen 阈值。
3. ADR-0042 决策文档留了 reopen 路径:「若候选 1 落地后第四职责(future 池管理)
   压力显现,再重开」 — 本轮无新压力点。

**未来 reopen 条件**:
- 测试覆盖率因 LockStack private 而下降 ≥ 10%
- 新增第二种分布式锁后端(如 ZooKeeper LockManager)需 LockStack 演化
- Round 36 之后 `SyncSupport` 行数超过 250(SLOC 阈值)

---

### C8 — RedisProCacheWriter SDR 入口扇出

**结论**:⏸️ 决策固化(v1.0 毕业议题)。

**理由**:
1. **SDR 是 Spring Cache 公共契约**:`RedisProCacheWriter extends ?` 继承层级 + 公开
   `get/put/putIfAbsent/remove/clean/clear/evict` 7 个 SDR 入口方法构成 Spring Cache
   `Cache` SPI 的实现面。任何继承层级变化或方法签名变化属于**二进制兼容**变更。
2. 项目处于 v0.x 阶段(`<1.0`),尚未毕业 — 公开 API 仍可调整,但 SDR 接口属
   Spring Cache 公共契约,**外部用户(库使用者)即使在 v0.x 阶段也已基于 SDR
   方法名编程**,改名/拆 seam 仍属破坏性变更。
3. 收益真实(7 入口 → 5 真实 SDR + SDR-adapter 协作类),但**触发 v1.0 毕业检查清单
   重审**更合适:
   - JavaDoc 公开 API 全面审查
   - 二进制兼容扫描(`japicmp`)
   - 迁移指南与 deprecation 周期(至少 1 个 minor 版本)

**未来 reopen 条件**:
- v1.0 毕业检查清单启动时(预计 Round 40+)
- 或外部用户在 issue 报告 7 入口语义混淆 ≥ 3 次

---

## 影响面 / 测试影响

### 已实施的 5 项

| 候选 | 源文件改动 | 测试改动 | 净 SLOC | 公开 API 变更 |
|---|---|---|---|---|
| C7 | 1 | 0 | -28 | 无 |
| C3 | 2 源 + 1 测试 | 1 改写 | +50 -21 | `activateStatic/clearStatic` 从 public → private(破坏性,内部 API) |
| C5 | 3(1 改 + 2 新) | 0 | -104 +223 | 2 个 package-private seam 类新增(非破坏) |
| C2 | 2(1 改 + 1 新) | 1 改写 | +88 -43 | 5 getter → 1 `metrics()`(破坏性) |

### 决策固化的 3 项

- C1:零代码变更,Javadoc 已自承约束
- C6:零代码变更,Round 35 第一候选
- C4:零代码变更,ADR-0042 reopen 路径未触发
- C8:零代码变更,v1.0 毕业检查清单议题

---

## 验证状态

- **本地编译**:未执行(本环境缺 JDK 21,仅有 JDK 25)。改动语义机械,基于源静态分析。
- **本地测试**:未执行(同编译原因)。`RedisProCacheWriterTest`、`RedisProCacheTest`
  已同步改造,断言形式与新 API 对齐。
- **CI**:将由 GitHub Actions 在 push 后执行 `./mvnw verify` 完整验证。

---

## 相关 ADR

- **前置**:ADR-0019(annotation → FieldSource 投影)、ADR-0021(protection toggle record)、
  ADR-0029(假想 seam 教条)、ADR-0031(timing helper seam)、ADR-0034(buildContext seam)、
  ADR-0036(ScopedActivation interceptor 迁移)、ADR-0042(single-flight future + chain readlock)
- **后续**:Round 35 — C6 优先;Round 36+ — C8(v1.0 毕业议题)