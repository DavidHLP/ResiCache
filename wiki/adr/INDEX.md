---
title: ADR 索引
type: meta
tags:
  - meta
  - adr
  - 索引
related: [index, rounds/CHRONICLE]
status: stable
created: 2026-07-05
updated: 2026-07-05
---

# ADR 索引(二分类)

> ADR 分两类:**A 类**定位型架构决策(本目录,0001-0008)/ **B 类**深化型 round 决策(下沉 `rounds/`,0009-0051)。
> B 类按 round 编年查阅 [[rounds/CHRONICLE]];过程实施日志由 git history 保留,不再赘述于卡片。

## A 类:定位型架构决策(0001-0008)

真正的架构决策,高长期价值。保留完整 Context / Decision / Consequences。

- [[0001-positioning]] — 定位「Spring Cache 防护增强注解生态」(已被 0006 取代)
- [[0002-keep-interceptor]] — 保留 interceptor+Advisor,弃装饰器
- [[0003-serialization-envelope]] — 序列化信封 + 迁移路径,不放松白名单
- [[0004-protection-preset]] — `protection.preset` 批量启用,而非默认全开
- [[0005-kernel-extraction-hedge]] — 内核无关化仅作长寿对冲,不近期执行
- [[0006-redisson-companion-positioning]] — 定位「ResiCache for Redisson」(取代 0001)
- [[0007-fire-single-buildline-abandonment]] — 统一单构建 Boot 4.0
- [[0008-observation-spans-attribution]] — Observation spans 归属

## B 类:深化型 round 决策(0009-0051)

refactor round 的决策卡片,下沉 rounds/(43 篇)。每张卡片只留**决策与后果**,实施 delta 见 [[rounds/CHRONICLE]]。

按阶段速查:

| 阶段 | ADR 区间 | 主题 | round |
|---|---|---|---|
| 1 | 0009-0013 | Chain Engine 抽出 + seam 化 | — |
| 2 | 0014-0017 | 构造 / 注册 / 工厂 seam 收敛 | — |
| 3 | 0018-0020 | 样板收敛 + 反射多态 seam | 9 |
| 4 | 0021-0025 | applyTo + Chain 单一表示 + 配置接入 | — |
| 5 | 0026-0029 | Round 14 封口 + Annotation 对齐 + Factory 收窄 | 14 / 20 |
| 6 | 0030-0033 | Writer / Metadata / Typed decisions | 21-24 |
| 7 | 0034-0036 | Writer context 归位 + Prefetch / Lua | 25-26 |
| 8 | 0037-0041 | 死代码扫尾系列 | 27-31 |
| 9 | 0042-0051 | 深度收尾(lock / observer / snapshot / nullvalue) | 32-37 |
