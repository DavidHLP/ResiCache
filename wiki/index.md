---
title: 内容索引
type: meta
tags:
  - meta
  - 索引
  - 导航
related: [overview, README]
status: stable
created: 2026-06-21
updated: 2026-07-09
---

# 内容索引

wiki 全部页面,按类别分组。回答问题前先在这里定位。

> 阅读入口:[[overview]]。维护规范:[[README]]。

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

---

最后更新:2026-07-09
