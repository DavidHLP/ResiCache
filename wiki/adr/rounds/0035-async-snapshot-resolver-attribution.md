---
title: "ADR-0035: async snapshot/restore 跨域寄生归位 MethodMetadataResolver"
type: adr
status: accepted
date: 2026-07-03
deciders: DavidHLP
related:
  - ADR-0034
  - ADR-0025
tags:
  - architecture-deepening
  - locality
  - parasite-removal
  - seam
  - round-25
---

# ADR-0035: async snapshot/restore 跨域寄生归位 MethodMetadataResolver

## 状态

- **Status**: Accepted
- **Date**: 2026-07-03
- **Deciders**: DavidHLP
- **Related**: ADR-0034(round 25 C1)/ ADR-0025(EarlyExpirationPolicy 迁出 TtlPolicy,同型跨域寄生修复)
- **Round**: 25(`/improve-codebase-architecture` round 25, Worth exploring C2 落地)

## 背景

round 25 HTML 报告 C2(Worth exploring):`RedisProCacheWriter.withMethodMetadataSnapshot`
(原 L191-219,30 行)持有 MethodMetadataResolver snapshot/restore + MDC snapshot/restore +
直接调 `DefaultMethodMetadataResolver.clearStatic()` —— async 边界 ThreadLocal 透传逻辑
寄生在 cache/writer 模块,语义归属是 chain / MethodMetadataResolver 域。`retrieve()`/`store()`
各自调一次,边界管理散落。

writer 知道太多 MethodMetadataResolver 的 ThreadLocal 内部(`clearStatic` 是实现特定静态方法)。
同型先例:ADR-0025(EarlyExpirationPolicy 从 TtlPolicy 迁出 refresh↔avalanche 跨域寄生方法)。

## 决策

**边界管理归位 owner(MethodMetadataResolver)**:

### D1 接口加 `default runWithSnapshot(Supplier<T>)`

`MethodMetadataResolver` 新增 default 方法,默认 no-op(`return work.get();`)。适用于
非 ThreadLocal 实现(如未来 ScopedValue-based resolver)—— seam 的正确用法
(leverage:一个 hook,N 种实现)。

### D2 DefaultMethodMetadataResolver 覆盖 runWithSnapshot

snapshot 自身 ThreadLocal(`CacheInvocationContext.snapshot(this)`)→ restore → work →
finally `clearStatic()`。**resolver 自管自身的 ThreadLocal 边界**,`clearStatic` 不再泄漏到 writer。

### D3 MDC 一并内聚

MDC(snapshot/restore/clear)与 method-metadata 同为「提交线程 → commonPool 线程」需透传的
调用 context,集中到 runWithSnapshot 一处优于 writer 各自处理。javadoc 显式说明此越界理由。

### D4 writer 收敛

- `retrieve()`:`methodMetadataResolver.runWithSnapshot(() -> get(name, key, ttl))`
- `store()`:`methodMetadataResolver.runWithSnapshot(() -> { put(...); return null; })`
- 删除 `withMethodMetadataSnapshot`(30 行)+ 5 个 import(`CacheInvocationContext` /
  `DefaultMethodMetadataResolver` / `MDC` / `Map` / `Supplier`)

### 路径独裁(拒绝的替代)

- **新建独立 `AsyncContextPropagator` 类** —— **拒绝**:报告倾向归位 owner;resolver 本就是
  ThreadLocal owner + 边界语义载体,加 default 方法即可,无需新 module。
- **MDC 留 writer** —— **拒绝**:writer 再持有 MDC snapshot 样板 = 寄生未清;集中到 runWithSnapshot
  locality 最优。

## 不变量(preserved invariants)

- **byte-equivalent 行为**:`runWithSnapshot` 逻辑与原 `withMethodMetadataSnapshot` 完全一致
  (snapshot/restore/clear 顺序、null 守卫、finally 清理)—— 纯位置迁移,async 语义零变化。
- **public API**:`MethodMetadataResolver` 加 default 方法(向后兼容);`RedisProCacheWriter.retrieve/store`
  签名不变;删除的 `withMethodMetadataSnapshot` 是 private。
- **接口默认 no-op**:非 ThreadLocal resolver 实现零影响。

## 验证

- **JDK 17 release=17 `clean test`**(Lombok 兼容口径):Tests run: 756, Failures: 0, Errors: 0,
  Skipped: 17(baseline 与 ADR-0033/0034 一致)。**完整重编译**(非增量),Lombok 生效,改动零回归。
- **JDK 21 全量测试**:本地 JDK 21 不可用(vfox `v-21.0.2+13` 损坏 + 两个 tarball 下载不完整 +
  Fedora 44 WSL 源仅 `java-25-openjdk`),JDK 25 与项目 Lombok 版本 annotation processing 不兼容
  (项目 Lombok 在 JDK 25 下 `@Builder` 等处理失效)—— 21 正式构建待 CI / 用户环境。
- async 透传语义被 `PathCAopAsyncIT` 覆盖(属 17 skipped Testcontainers IT,无 Docker 环境)。

## 内部红蓝博弈(CR & Fix)

| 反方意见 | 反驳 / 处理 |
|---|---|
| MDC 内聚到 MethodMetadataResolver 违反单一职责? | 同为「async 边界 context 透传」,集中一处 locality 最优;Default 实现作为 ThreadLocal 边界载体,内聚合理;javadoc 显式说明越界理由。 |
| snapshot 在 commonPool 线程读 ThreadLocal(空)? | 行为继承原 `withMethodMetadataSnapshot`(byte-equivalent);async 语义不在本 ADR 范围(本 ADR 是 locality 迁移,非 async 修复)。 |
| JDK25 验证失败? | 根因 JDK25 与项目 Lombok 不兼容(非本改动),退 JDK17 release=17 `clean test` 验证逻辑零回归。 |

## 关联 wiki 路径

- `wiki/adr/0035-async-snapshot-resolver-attribution.md`(本文件)
- `wiki/log.md` round 25 C2 entry
- ADR-0034(round 25 C1)/ ADR-0025(同型跨域寄生修复先例)
