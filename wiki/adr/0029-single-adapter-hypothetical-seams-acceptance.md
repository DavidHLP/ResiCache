---
title: "ADR-0029: 单-adapter hypothetical seam 接受策略(MethodMetadataResolver + BloomHashStrategy)"
type: adr
status: accepted
date: 2026-07-03
deciders: DavidHLP
related:
  - ADR-0028
tags:
  - architecture-deepening
  - hypothetical-seam
  - reversibility-hedge
  - round-20
---

# ADR-0029: 单-adapter hypothetical seam 接受策略

## 状态

- **Status**: Accepted
- **Date**: 2026-07-03
- **Deciders**: DavidHLP
- **Related**: ADR-0028(同轮复审的改码候选)

## 背景

架构深化复审(候选 3+4)识别两个单-adapter hypothetical seam(满足 "One adapter = hypothetical seam. Two adapters = real seam." 原则):

1. **`MethodMetadataResolver`**(`chain/`):5 方法 interface,唯一 adapter `DefaultMethodMetadataResolver`(基于 ThreadLocal)。javadoc 明示为 Java 21 `ScopedValue` 迁移留 seam("Path C WS-1.3")。当前 nothing varies across the seam。

2. **`BloomHashStrategy`**(`protection/bloom/`):1 方法 interface,唯一 adapter `MessageDigestBloomHashStrategy`(MD5+SHA-256 双哈希)。测试三处均 `new MessageDigestBloomHashStrategy()` 注入真实实现(非 fake),证明 nothing varies。

复审最初建议删除两个 seam。可逆性分析推翻此建议。

## 决策

**接受两个 hypothetical seam 现状,不删除。** 理由:

- **可逆性不对称**:删 interface 不可逆(未来重新引入成本高);保留可逆(确认不需要再删,成本低)。在不确定时,保留是更便宜的错误方向。
- **存在成本极低**:`MethodMetadataResolver` 71 行(主要为 javadoc),`BloomHashStrategy` 10 行。
- **真实演进对冲**:
  - `ScopedValue` 是 Java 并发模型的真实演进方向(已多轮 preview,GA 在 horizon),ThreadLocal→ScopedValue 迁移是非平凡改动,保留 seam 让未来迁移点单一化(不污染 3 处注入点)。
  - 布隆 hash 算法是经典可替换点(Murmur / 双哈希 / 加盐),保留 seam 支持未来算法切换或测试注入确定性 fake。

**本 ADR 锁定决策**:未来架构复审**不得**再以"单 adapter"为由建议删除这两个 seam,除非满足以下任一条件:

- (a) `ScopedValue` 迁移被明确放弃(项目决定长期保留 ThreadLocal);或
- (b) hash 算法被确定不可替换(例如性能约束锁定 MD5+SHA-256)。

## 后续

- 若 `ScopedValue` 在 Java LTS 周期内仍未执行迁移,重新评估 `MethodMetadataResolver` seam。
- `BloomHashStrategy` 若长期单实现,可考虑改为 `sealed interface` 显式标记"有意单实现"。

详见 round 20 `log.md`。
