---
title: 内容索引
type: meta
tags:
  - meta
  - 索引
  - 导航
related: [overview, log, README, archive/adr/INDEX]
status: stable
created: 2026-06-21
updated: 2026-07-05
---

# 内容索引

wiki 全部页面,按类别分组。回答问题前先在这里定位。

> 阅读入口:[[overview]]。维护规范:[[README]]。变更历史:[[log]]。

## 🗺️ 视觉地图(画布)

| 画布 | 用途 |
|---|---|
| ![[meta/overview.canvas]] | 架构 / 机制 / 概念 三栏总览 |
| ![[meta/mechanisms-canvas.canvas]] | 5 机制在责任链上的交互 |
| ![[meta/modules-canvas.canvas]] | 8 模块在数据流上的依赖 |

## Meta

- [[README]] — Wiki 维护规范(schema / 三大操作)
- [[overview]] — 项目概览,阅读入口
- [[index]] — 本页
- [[for-onboarding]] — 角色 MOC:新人入门
- [[for-implementer]] — 角色 MOC:扩展开发
- [[mechanisms-moc]] — 机制拓扑 MOC
- [[modules-moc]] — 模块依赖 MOC
- [[log]] — 操作日志 · [[archive-2026-q2]] · [[archive-2026-q3]]
- [[milestone-2026-q3]] — Active 里程碑

## 架构决策(ADR)— 已归档

52 篇 ADR(A 类 0001-0008 定位型 + B 类 0009-0052 深化型)已整体归档至 `wiki/archive/adr/`,**不再纳入常规阅读路径**——历史决策过度约束了后续架构探索的发散思维。归档≠废弃:需查证某条历史决策时再下钻 [[archive/adr/INDEX]] / [[archive/adr/rounds/CHRONICLE]]。

## 架构(architecture/)

- [[chain-of-responsibility]] — 责任链脊柱,HandlerOrder 顺序真理源
- [[cache-lifecycle]] — GET/PUT/CLEAN 端到端读写路径
- [[context-data-flow]] — CacheInput/Context/Output 数据模型
- [[handler-result-control]] — CONTINUE/TERMINATE/SKIP_ALL 三态
- [[auto-configuration]] — Spring Boot starter 零配置装配

## 防护机制(mechanisms/)

- [[bloom-filter]] — 布隆(100),防穿透
- [[breakdown-lock]] — 分布式锁(200),防击穿
- [[early-expiration]] — 提前过期(250),热 key 刷新
- [[ttl-jitter]] — TTL 抖动(300),防雪崩
- [[null-value]] — 空值缓存(400),防穿透

## 模块(modules/)

- [[cache-core]] · [[annotations]] · [[operations]] · [[configuration]] · [[serialization]] · [[observability]] · [[eviction]] · [[holder-and-config]]

## 概念(concepts/)

- [[cache-penetration]] · [[cache-breakdown]] · [[cache-avalanche]] · [[hot-key]]

## 操作指南(how-to/)

- [[add-protection-handler]] — 4 步新增防护 handler
- [[configure-behavior]] — 三层配置实操

环境故障笔记(开发环境,非领域):[[env-notes/fix-docker-pull-ipv6-timeout]] · [[env-notes/fix-wsl2-testcontainers-socat-forward]]

---

最后更新:2026-07-05 · 维护见 [[log]]
