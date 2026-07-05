---
title: "ADR-0044: AnnotationChainObserver 死 observer 通道删除"
type: adr
status: accepted
date: 2026-07-04
deciders: DavidHLP
related:
  - ADR-0013
  - ADR-0016
  - ADR-0026
  - ADR-0038
  - ADR-0039
tags:
  - yagni
  - observer-channel
  - deletion-test
  - handler-domain
---

# ADR-0044: AnnotationChainObserver 死 observer 通道删除(round 33)

## 状态

Accepted — 2026-07-04。

## 背景

`/improve-codebase-architecture` round 33 评审识别 5 个候选。**C1(Strong)**:`AnnotationChainObserver`
全仓零生产实现,Engine 维护 observer 通道是为一个不存在的 seam。

通读 `handler/AnnotationChainObserver.java`(68 SLOC)+ `handler/AnnotationChainEngine.java`(164 SLOC)
+ `chain/ObserverRegistry.java`(146 SLOC,泛型 utility)+ `RedisCacheInterceptor`(注入 consumer)
+ `AnnotationChainEngineTest.java`(7 处 mock observer)后裁决。

## 决策

### 落地

- **删除** `handler/AnnotationChainObserver.java` 整文件(68 SLOC)
- **删除** `AnnotationChainEngine.observers` 字段(委派到 `ObserverRegistry<AnnotationChainObserver>`)
- **删除** `addObserver(AnnotationChainObserver)` / `observers()` 方法
- **删除** `execute(...)` 内的 `observers.forEachSafe(o -> o.onChainStart(...))` 调用
- **删除** `execute(...)` 内的 try/finally 守护的 `onChainEnd` 调用
- **删除** `AnnotationChainEngineTest` 的 `ObserverTests` 嵌套类(4 个测试)
- **删除** `AnnotationChainEngineTest` 的 `addObserver_null_throws` / `observers_returnsImmutableSnapshot`
  API surface 测试(2 个)

### 收益

| 维度 | 削减量 |
|---|---|
| AnnotationChainEngine SLOC | 164 → ~120(净 -44 SLOC) |
| 全文件删除 | -68 SLOC(`AnnotationChainObserver.java` 整文件) |
| 测试删除 | -6 个测试方法 |
| Engine 单一职责 | ✓ 只剩"链推进 + handler 求值 + 结果收集"三件关注点 |
| 模板方法样板 | 取消 try/finally + forEachSafe 包装,execute 退化为单层 for 循环 |

### 删除测试

- 删 `AnnotationChainObserver.java` → Engine 失去 observer 抽象,无法在不重写 Engine 的情况下
  注入新观测关注点。但本项目 cache 写入链观测已通过 `chain.ChainObserver` 路径收口,
  annotation chain 不需要独立 observer 抽象(决策语义 filter vs decision 不共享)
- 删 Engine observer 字段 + 4 处调用 → Engine 退化为单职责推进循环,execute 主体 25 SLOC 收敛

### 不变量保留

- `ChainObserver`(cache 写入链)观测通道完整保留
- `ObserverRegistry` 泛型 utility 不动(ADR-0016/0026 收口 seam)
- `AnnotationHandler` / `AbstractAnnotationHandler` 零修改
- `RedisCacheInterceptor.annotationChainEngine` 字段无变化

## 相关

- [[0013-annotation-chain-engine-extraction]] — 平行 seam 抽出
- [[0016-observer-registry-seam-and-manager-instantiate-seam]] — ObserverRegistry 泛型 utility
- [[0026-round14-contextbuilder-deletion-foreachsafe-and-sealings]] — forEachSafe 异常隔离
- [[0038-cachedvalue-wither-handlerpriority-order-noop-observer-dead-code-removal]] — 同源 YAGNI 死 observer 清理
- [[0039-cacheresult-dead-fields-and-noop-removal]] — 同源死 NoOp 清理