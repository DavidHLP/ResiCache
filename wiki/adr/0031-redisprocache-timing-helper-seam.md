---
title: "ADR-0031: RedisProCache 6 处 try-finally timing 样板 → RedisProCacheTimers 工具 seam"
type: adr
status: accepted
date: 2026-07-03
deciders: DavidHLP
related:
  - ADR-0029
  - ADR-0030
tags:
  - architecture-deepening
  - duplication-collapse
  - deletion-test
  - round-22
---

# ADR-0031: RedisProCache 6 处 try-finally timing 样板 → RedisProCacheTimers 工具 seam

## 状态

- **Status**: Accepted
- **Date**: 2026-07-03
- **Deciders**: DavidHLP
- **Related**: ADR-0029 / ADR-0030(rounds 20/21);本轮 Round 22 收敛后的"重复墙对冲"
- **Round**: 22

## 背景

`/improve-codebase-architecture` round 22 autocratic one-shot。Round 21 已宣告饱和但仍兑现 F1 finding。

`/improve-codebase-architecture` round 22 scan 在 `cache/` 域内发现一处 **shallow duplication**:
`RedisProCache.java`(308 SLOC)中有 **6 处字节级同构**的 timing 模板,各自起始于
`long start = System.nanoTime();` + `try { ... }` + 收尾于
`finally { safeRecord(<timer>, System.nanoTime() - start, TimeUnit.NANOSECONDS); }`。

具体 6 处(原 line number ):

1. `get(Object)` —— lines 132-145(getTimer + hit/miss counter)
2. `get(Object, Class<T>)` —— lines 147-161(getTimer + hit/miss counter)
3. `get(Object, Callable<T>)` —— lines 163-200(getTimer + bloom/sync + 异常 catch)
4. `put(Object, Object)` —— lines 255-264(putTimer + putCounter)
5. `evict(Object)` —— lines 266-275(evictTimer + evictCounter)
6. `clear()` —— lines 277-285(evictTimer only)

每处的 `safeRecord` 已部分抽到 helper(原 line 119-129),但
**helper 只 absorb 了最后一行**,重复墙依然 5×存在(`System.nanoTime` 边界 × 2 +
`try`/`finally` 样板 × 3)。4 个 `private static` helper(`registerTimer` /
`registerCounter` / `safeIncrement` / `safeRecord`)均以 `RedisProCache` 为单一消费者。

对照 Round 21 ADR-0030 同款"死代码清理"模式,但本处性质不同:**非 dead,而是 duplication**;
deletion test 不是"删代码",而是"抽 seam"。

## 决策

**新建 `cache/RedisProCacheTimers` package-private 工具类(5 个静态入口)**;`RedisProCache`
内部消除 6 处 `try-finally + System.nanoTime() + safeRecord` 样板,转为 seam 调用。

### 五个 seam 入口(全在 `RedisProCacheTimers`)

| 入口 | 行为 | 替换原 |
|---|---|---|
| `registerTimer(registry, name, desc, cacheName)` | registry=null→null,否则带 `tag("cache",...)` 注册 | 私有 `registerTimer`(原 line 97-106) |
| `registerCounter(registry, name, desc, cacheName)` | 同上但 Counter | 私有 `registerCounter`(原 line 108-117) |
| `safeIncrement(counter)` | counter=null→no-op,否则 increment | 私有 `safeIncrement`(原 line 119-123) |
| `timed(timer, Runnable body)` | timer=null→直接 run;否则 start→body→finally record | 6 处样板中**3 处 void 路径**(put/evict/clear) |
| `timedGet(timer, Supplier<T> body)` | timer=null→直接 get;否则 start→body→finally record,返回值 | 6 处样板中**3 处 return 路径**(`get` 的 3 个重载) |

### 行为保真(字节级语义对齐)

- `timer == null`(`meterRegistry` 未启用)`safeRecord(null, ..., NANOSECONDS)` 是 no-op;
  helper 短路不计算 `nanoTime` 是 no-op 的等价优化(原代码仍每调用 1 次 `nanoTime`,本优化省掉,但
  测量值记录路径 — `timer.record(...)` — 仍由 finally 推进)
- `timer != null` 时按 `start → body → finally record` 推进
- 异常不被吞 —— 仍沿 finally 释放,与原 try-finally 字节级等价(test 见
  `RedisProCacheTimersTest.bodyException_propagatesAndStillRecords` /
  `supplierException_propagatesAndStillRecords`)
- meter 名称 + tag + description 完全不变(`"resicache.cache.get/put/evict/hit/miss/..."` + `cache` tag)
  —— RedisProCacheTest 现有 14 用例(`put_recordsTimer` / `evict_recordsTimer` /
  `cache_registersMeterWithCorrectTag` / counter increment 等)已隐式覆盖
- `get(key, Callable<T> loader)` 的 try-catch-finally 双层结构改为
  外层 try-catch + 内层 `timedGet` —— 异常翻译路径(`missCounter` + RuntimeException
  forwarding + `RuntimeException("Failed to load cache value ...")` wrapping)与原 4-层结构字节级等价

### `RedisProCache` 净变更

- 删除 4 个 private static helper(`registerTimer` / `registerCounter` / `safeIncrement` /
  `safeRecord`,共 ~28 SLOC)
- 6 处 try-finally wall 收敛为 seam 调用(净减 ~30 SLOC 模板代码)
- 构造器 8 行 `registerTimer(...)` / `registerCounter(...)` 调用前缀加 `RedisProCacheTimers.`
- 删 `import java.util.concurrent.TimeUnit`(`safeRecord` 移除后本类不再直接使用 TimeUnit,Checkstyle
  `UnusedImports` = error)
- 新增 Javadoc 段落 `<b>Round 22 收敛</b>(ADR-0031)` 引用 seam
- 总 net SLOC:RedisProCache 308 → ~225(-83 行 body,虽有 javadoc 增加)

### `RedisProCacheTimers` 新文件

- 5 个静态入口,1 个私有构造(private constructor 标记 utility)
- 公共 javadoc 完整说明 seam 行为契约 + 与 ADR-0031 链接
- 文件 ~95 SLOC(含 javadoc)

### 新增测试 `RedisProCacheTimersTest`

- 5 个 `@Nested` 区对应 5 个入口:registerTimer / registerCounter / safeIncrement / timed / timedGet
- 共 **10 用例**:null/non-null 分支 + 异常传播 + finally 时序
- "接口是测试面"兑现:6 处私有样板收敛后,seam 成为单一可测面;未来若新增
  metric 类型(hit-ratio / 复合 timer)只在 seam 内扩展,不污染 6 个调用点

## 后果

**增益**:

- **locality**:6 处字节级同构样板变为 6 处 1-liner seam 调用,匹配错误只看 1 处。
- **leverage**:1 个新建工具 seam,1 套测试目标(`RedisProCacheTimersTest`),未来添加
  metric 类型不增加测试面爆炸。
- **接口更窄**:`RedisProCache` 删除 4 个 private static helper,public API 与
  test surface 1:1 对齐(类不再依赖 own-private helper)。
- **删除 6 处而非"搬运"**:deletion test 通过 —— 删 helper 后 6 处样板必须在调用点重现,
  但 6 处样板已被 seam 替换,故删除 = 真实归并而不是搬家。

**代价**:

- 多一个文件(`RedisProCacheTimers.java` ~95 SLOC),但**净 -83 SLOC body**。
- 5 个新测试用例需维护 —— 但每个用例都贴 seam 契约,语义极清晰。

**不变**:

- `RedisProCache` public API:8 参构造 + `get/put/evict/clear` overrides + 5 个 getter
  全部不变。
- meter 名 + tag + description 完全保留(Spring Boot Actuator/Prometheus 用户零感知)。
- `get(key, Callable<T>)` 的 4-层 catch 异常翻译语义完全保留。
- `RedisProCacheTest` 14 用例零修改(隐式 byte-equivalent 回归)。

## 实施

### 修改文件(3 个)

- `cache/RedisProCache.java` —— 6 处样板 → seam;删除 4 private helper;删 TimeUnit import;
  Javadoc 加 Round 22 段落;308 → 225 行
- `cache/RedisProCacheTimers.java` —— 新建(95 SLOC,javadoc + 5 个静态入口)
- `cache/RedisProCacheTimersTest.java` —— 新建(150 SLOC,5 `@Nested` × 2-3 用例 = 10 用例)

### 验证

- `mvnw checkstyle:check test-compile -B` —— **BUILD SUCCESS**(0 violations)
- `mvnw test -Dtest='RedisProCacheTest,RedisProCacheTimersTest'` —— **23 tests,
  0 failures**(13 existing RedisProCacheTest 用例隐式回归 + 10 新增 Timers 用例)
- `mvnw test` —— 797 unit tests,**0 failures, 8 errors**(8 errors = `RedisCacheInterceptorTest`
  全部由 `setNext(...)` NoSuchMethodError 引起,**pre-existing**:git stash + bare master
  `280f0b4` 复现完全相同 8 errors,本 ADR diff 完全不触及 `handler/` 域,与 round 22 deepening 错位,
  留作 round 23+ 独立 handler-测试对齐工单)

## 为什么不放在 round 23+

- **饱和反向证明**:Round 21 已宣告"未触及域扫尽",本轮在饱和预言之后仍找到一处真重复
  (`cache/` 域,从 round 1-21 全程未抽 seam)—— 证明浅层模块仍可下沉,**架构深化空间尚未枯竭**。
- 与 ADR-0029"接受两个 hypothetical seam"呼应:本处是 ADR-0029 精神的镜像 —— **接受并下沉
  既存样板**,而非删除既有抽象。

## 已知 deferred(round 23+)

- `RedisCacheInterceptorTest` 8 errors —— handler 测试未对齐 ADR-0022 `setNext` 删除,
  需独立工单(handler 测试同步)
- `DefaultMethodMetadataResolver` / `CacheInvocationContext` 3 处 `instanceof Class<?>` /
  `Method` 收敛 —— 正交于 ADR-0029 single-adapter 决策,留 round 23

## 相关

- [[0030-redisprocachewriter-dead-accessors-removal]] —— Round 21,同款死代码/样板清理模式
- [[0029-single-adapter-hypothetical-seams-acceptance]] —— Round 20,可逆性对冲 vs 本 ADR 的"下沉"
- [[0026-round14-contextbuilder-deletion-foreachsafe-and-sealings]] —— Round 14 扫尽补漏
- `/improve-codebase-architecture` skill —— round 22 autocratic one-shot 触发
