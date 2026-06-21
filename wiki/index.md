---
title: 内容索引
type: meta
tags:
  - meta
  - 索引
  - 导航
related: [overview, log, README]
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 内容索引

wiki 全部页面,按类别分组。回答问题前先在这里定位。

> 阅读入口:[[overview]]。维护规范:[[README]]。变更历史:[[log]]。

## 🗺️ 视觉地图(画布)

| 画布 | 用途 |
|---|---|
| ![[meta/overview.canvas]] | 架构 / 机制 / 概念 三栏布局总览 |
| ![[meta/mechanisms-canvas.canvas]] | 5 机制在责任链上的交互关系 |
| ![[meta/modules-canvas.canvas]] | 8 模块在数据流上的依赖关系 |

> 画布文件位于 `meta/`,可单独在 Obsidian 中打开拖拽编辑。**未列出画布的入口**——见上表。

## Meta

- [[README]] —— Wiki 维护规范(schema:目录/命名/链接/工作流)
- [[overview]] —— 项目概览与技术栈,阅读入口
- [[log]] —— 操作日志(append-only)
- [[index]] —— 本页
- [[dashboard]] —— Dataview 仪表盘(近期更新 / 分类 / 待完善)
- [[lint-report-2026-06-21]] —— Lint 健康检查报告
- [[mechanisms-moc]] —— 机制拓扑 MOC(责任链档位 + 问题 ↔ 防御组合)
- [[modules-moc]] —— 模块依赖 MOC(三层模型 + 关键调用链)

## 架构(architecture/)

- [[chain-of-responsibility]] —— 责任链脊柱,`HandlerOrder` 顺序真理源
- [[cache-lifecycle]] —— GET/PUT/CLEAN 端到端读写路径
- [[context-data-flow]] —— `CacheInput`/`CacheContext`/`CacheOutput` 数据模型
- [[handler-result-control]] —— CONTINUE/TERMINATE/SKIP_ALL 三态与属性标记
- [[auto-configuration]] —— Spring Boot starter 零配置装配链

## 防护机制(mechanisms/)

- [[bloom-filter]] —— 布隆过滤器(100),防穿透,三实现 + PostProcess
- [[breakdown-lock]] —— 分布式锁(200),防击穿,Redisson 锁内聚
- [[early-expiration]] —— 提前过期(250),热 key 异步刷新 + Lua CAS
- [[ttl-jitter]] —— TTL 抖动(300),高斯随机防雪崩
- [[null-value]] —— 空值缓存(400),`CachedValue` 占位防穿透

## 模块(modules/)

- [[cache-core]] —— `RedisProCache`/`Manager`/`Writer`/`Interceptor`/`CachedValue`
- [[annotations]] —— 4 注解 + `AnnotationHandler` 解析链 + 操作源
- [[operations]] —— `Operation` + `RedisCacheRegister` + `OperationFactory`
- [[configuration]] —— `resi-cache.*` 全配置树与三层优先级
- [[serialization]] —— 安全序列化:白名单 + NullValue 受限往返
- [[observability]] —— `RedisCacheHealthIndicator` + actuator 指标
- [[eviction]] —— `TwoListLRU` 双链表近似 LRU
- [[holder-and-config]] —— 元数据缓存 + config 装配辅助类

## 概念(concepts/)

- [[cache-penetration]] —— 穿透:布隆 + 空值双防线
- [[cache-breakdown]] —— 击穿:分布式锁串行化回源
- [[cache-avalanche]] —— 雪崩:TTL 抖动 + 提前过期
- [[hot-key]] —— 热点 key:提前过期异步刷新

## 操作指南(how-to/)

- [[add-protection-handler]] —— 4 步新增防护 handler(含示例)
- [[configure-behavior]] —— 三层配置实操与典型套餐

---

最后更新:2026-06-21 · 共 28 页 · 维护见 [[log]]
