---
title: Round 37 — F2/F3/F4 后续候选复审驳回 + metrics seam stale Javadoc 修复
type: adr
tags:
  - adr
  - false-seam-rejection
  - stale-javadoc
  - round-37
  - metrics
related: [0050-cachedvalue-builder-fortest-seam, 0029-single-adapter-hypothetical-seams-acceptance, 0047-round-34-architecture-deepening, 0035-async-snapshot-resolver-attribution, 0048-nullvalueencoder-type-support-collaboration-seam]
status: stable
created: 2026-07-05
updated: 2026-07-05
---

# ADR-0051 — Round 37:Round 36 后续候选 F2/F3/F4 复审驳回 + metrics seam stale Javadoc 修复

> 本轮是 Round 36(ADR-0050)的后续闭环。ADR-0050 实施 F1 时,把
> `/improve-codebase-architecture` 扫出的另外 3 个候选(F2/F3/F4)列为
> Round 37+ 后备,分别预告 ADR-0051/0052/0053。**本轮对三者做完整通读复审,
> 裁决全部驳回(false seam / 语义正交 / ROI 不足)**,仅落实 F2 候选中
> 唯一真实的子项 —— ADR-0047 C2 收口 getter 后遗留的 stale Javadoc 断链。

## 上下文 (Context)

ADR-0050 末尾列出的 3 个后续候选:

| 候选 | 原始评级 | 原始 Solution 蓝图 |
|------|---------|-------------------|
| **F2** | Worth exploring | `RedisProCacheWriter.getCacheStatistics(name)` 从 `RedisProCache.metrics()` 派生 Spring `CacheStatistics` + scrub stale Javadoc |
| **F3** | Worth exploring / 需 contract | `MethodMetadataResolver.activate` ↔ `runWithSnapshot` 公开双轨融合为 `ScopedSnapshot.enter(...)` |
| **F4** | Speculative | `NullValueEncoder.encodeForReturn` 3-string 签名 → 1 对象(CacheContext value object / MDC) |

本轮按项目铁律「决策前完整通读核心文件」对三者逐一做 deletion test +
依赖方向分析 + 红蓝博弈,结论:**三者的「Problem 描述」均把合理分层误诊
为「双轨重复」,对应的「Solution 蓝图」会引入真实耦合或破坏既有 ADR 决策**。

## 复审方法

对每个候选:
1. 完整 Read 所涉核心文件(接口 + 默认实现 + 全部调用点),不基于 grep 片段推断;
2. 跑 deletion test —— 删除/合并后复杂度是「浓缩」还是「搬家 + 增耦合」?
3. 检查依赖方向 —— 合并是否会引入反向依赖 / 跨层耦合?
4. 交叉引用既有 ADR —— 是否与已固化的决策冲突?

## F2 裁决:驳回 Solution 蓝图(false seam)

### 通读结论

`RedisProCacheWriter.getCacheStatistics(name)`(line 227-229)与
`RedisProCache.metrics()`(line 259-265)**是两套合理分层的独立统计体系**,
不是「双轨重复」:

| 维度 | `getCacheStatistics` | `metrics()` |
|------|----------------------|-------------|
| 类型 | SDR 框架 `org.springframework.data.redis.cache.CacheStatistics` | 项目自有 record `CacheMetrics` |
| 数据源 | `CacheStatisticsCollector statistics` 字段 | `RedisProCache` 的 4 Counter + 3 Timer(Micrometer) |
| 累积者 | SDR 框架在 `RedisCacheWriter` 包装层自动 record | `RedisProCache` 在 `get/put/evict` override 手动 `safeIncrement` |
| 暴露层 | cacheWriter 层(Redis 字节级 GET/PUT) | cache 语义层(含 bloom/lock/nullvalue 链层决策) |
| 调用方 | Spring Boot Actuator(cache metrics endpoint) | Micrometer `/actuator/metrics/resicache.*` + 内部 `metrics()` |

### 为什么不能合并

1. **反向依赖**:`RedisProCacheWriter` 不持有 `RedisProCache` 引用(恰恰相反 ——
   `RedisProCache` 构造时注入 `RedisCacheWriter`)。「让 writer 从 cache.metrics()
   派生」会破坏分层方向。
2. **语义不同**:bloom 短路返回 null 时,Micrometer `missCounter` +1,而 SDR
   `CacheStatistics` 记的可能是 hit(`super.get` 仍走)或不同语义。两套指标
   追踪**不同层的真相**,强行合并会丢失这种分层语义。
3. **框架契约**:`CacheStatisticsCollector` 由 Spring 注入,累积由 SDR 框架在
   cacheWriter 包装层自动完成。我们无法也不应接管其内部累积逻辑。
4. **违反 ADR-0029**:本项目已固化「single-adapter hypothetical seam 接受策略」
   —— 不为表面「统一」强行合并两个真正独立的接口。`getCacheStatistics` 是
   正确的 thin adapter(接口=Spring 契约,实现=委派框架注入的 collector),
   **不是 shallow module**。

### F2 真实有效子项:stale Javadoc

F2 Problem 中「Javadoc 残留 `{@link #getHitCount()}` 等被删 getter 引用」
**是真实问题**。ADR-0047 C2 删了 5 个 getter(getHitCount/getMissCount/
getPutCount/getEvictCount + getHitRate 派生),全仓扫描发现 **2 处真断链**
(其余为 `{@code}` 历史叙述,合法):

| 文件:行 | 断链内容 | 修复 |
|---------|---------|------|
| `RedisProCacheTimers.java:92` | `{@link RedisProCache#getHitCount()}` | → `{@link RedisProCache#metrics()}`(指向现存方法,语义一致:metrics() 内部对 null Counter 返回 0L) |
| `CacheMetrics.java:43` | `{@link RedisProCache#getHitRate()}` | → `{@code RedisProCache.getHitRate()}`(历史叙述文字,去掉断链 `{@link}`) |

## F3 裁决:驳回(语义正交,非双轨)

### 通读结论

`DefaultMethodMetadataResolver` 的两条公开入口**语义正交**,不是「同一
ThreadLocal 两条重复写入路径」:

| 入口 | 语义 | 调用模式 |
|------|------|---------|
| `activate(method, targetClass)` | **同步嵌套作用域入口**:追踪 previous 状态,返回 `ScopedActivation`(AutoCloseable),close 时 restore,保证嵌套调用安全 | try-with-resources(`RedisCacheInterceptor:105`) |
| `runWithSnapshot(Supplier<T>)` | **异步跨线程边界**:snapshot 提交线程的 ThreadLocal + MDC,在 commonPool 线程 restore,finally 清理防线程复用泄漏 | `CompletableFuture.supplyAsync`(`RedisProCacheWriter:105/156`) |

### 为什么不能合并

1. **正交关注点**:`activate` 回答「谁设置当前 ThreadLocal」(同步作用域入口);
   `runWithSnapshot` 回答「如何跨线程透传 ThreadLocal」(异步边界)。一个方法
   无法同时表达「嵌套 restore」与「跨线程 snapshot」两种语义而不污染调用方。
2. **既有 ADR 已深思熟虑**:ADR-0035 把 `runWithSnapshot` 归位 resolver(消除
   writer 的跨域寄生),ADR-0036 把 `activate` 接入 interceptor。两者是**不同
   round 为不同问题引入的解**,不是「双轨漂移」。
3. **「接口签名数 -1」是表面指标**:报告自承「需要先写 contract ADR 锁定语义」
   —— 这是危险信号(语义尚未清楚即想着合并)。合并会强制把两条入口的调用方
   (`RedisCacheInterceptor` + `RedisProCacheWriter`)重写为统一形态,
   净复杂度**上升**而非下降。

deletion test:**删除任一入口会让对应的同步/异步路径失去正确语义**,
复杂度不会浓缩。F3 驳回。

## F4 裁决:驳回(ROI < 耦合成本)

### 通读结论

`NullValueEncoder.encodeForReturn(value, cacheName, key)` 中 `cacheName`/`key`
**仅服务 1 行 `log.debug`**(line 56-59)。

### 为什么不升级签名

1. **破坏 leaf 包独立性**:`NullValueEncoder` 当前单向依赖 `TypeSupport`
   (nullvalue → serialization,无循环)。改 `CacheContext` value object 会让
   `NullValuePolicy` 接口 + `NullValueEncoder` 反向依赖 chain 包
   (`CacheContext` 位于 `chain.model`)—— **nullvalue 防护机制本应是可
   独立替换的 leaf,不应认知 chain 的数据模型**。
2. **接口契约**:`toReturnValue(value, cacheName, key)` 是 `NullValuePolicy`
   **接口方法**(ActualCacheHandler:135/243 经接口多态调用)。改签名 = 改
   策略面契约,所有自定义 `NullValuePolicy` 实现都要跟着改。
3. **MDC 替代更脆弱**:把 cacheName/key 放 MDC,encoder 从 MDC 读 —— 引入隐式
   全局状态依赖,且 encoder 在 chain 处理时序中 MDC 是否已设置取决于调用方,
   **比显式参数更难推理**。
4. **debug log 参数不构成 seam 升级理由**:3 个 string 参数是**显式、可测试、
   无隐式状态**的;为「1 行 debug 关联」引入跨包依赖,ROI 远低于耦合成本。

deletion test:删除 `cacheName`/`key` 参数 → debug log 失去定位能力;
升级为对象 → 引入 nullvalue→chain 反向依赖。两条路都不浓缩复杂度。F4 驳回。

## 决策 (Decision)

1. **F2 Solution 蓝图(getCacheStatistics ↔ metrics 合并)驳回** —— false seam,
   违反 ADR-0029 + 反向依赖,记录本 ADR 防止未来 Explore agent 重复提出。
2. **F3(activate ↔ runWithSnapshot 合并)驳回** —— 语义正交,合并违反
   ADR-0035/0036 既定分层。
3. **F4(encodeForReturn 签名升级)驳回** —— 破坏 nullvalue leaf 包独立性,
   ROI 不足。
4. **唯一落地**:修复 2 处 metrics seam stale Javadoc 断链。

## 影响面

| 项 | 变更 |
|----|------|
| `RedisProCacheTimers.java` | 1 行 Javadoc:`{@link #getHitCount()}` → `{@link #metrics()}` |
| `CacheMetrics.java` | 1 行 Javadoc:`{@link #getHitRate()}` → `{@code ...}` |
| 生产代码语义 | **0 变化**(纯注释) |
| 公开 API | **0 变化** |
| 序列化字节 | **0 变化** |

净代码变更:**+0 / -0 SLOC**(纯 Javadoc 字符替换)。

## 验证状态

- ✅ `./mvnw test-compile` 绿(Javadoc 改动不影响编译)
- ✅ `./mvnw test` 全绿(0 fail / 0 err)
- ✅ Checkstyle 0 violation
- ✅ Javadoc 断链扫描:修复后 0 处 `{@link}` 指向已删 getter

## 相关 ADR

- **前置**: ADR-0050(F1 实施 + F2/F3/F4 预告);ADR-0047 C2(5 getter 收口为
  CacheMetrics record,stale Javadoc 的根因);ADR-0029(single-adapter
  hypothetical seam 接受策略 —— F2 驳回依据);ADR-0035/0036(runWithSnapshot /
  activate 归位 —— F3 驳回依据);ADR-0048(NullValueEncoder 协作 seam ——
  F4 驳回依据)。
- **后续**: Round 36 后备路线 F2/F3/F4 全部关闭,无新候选挂账。下一轮
  `/improve-codebase-architecture` 应扫描**新域**(当前候选均来自 metrics /
  resolver / nullvalue 三域的旧 friction,已被多轮 ADR 充分覆盖)。
