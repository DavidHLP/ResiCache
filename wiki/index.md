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
updated: 2026-07-01
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
- [[log]] —— 操作日志(append-only,「日期 + 主题」摘要风格)
- [[log/archive-2026-q2]] —— 2026 Q2 归档(autonomous-loop v1/v2 round 1–42)
- [[milestone-2026-q3]] —— **Active 里程碑**:项目优化(2026 Q3)
- [[index]] —— 本页
- [[dashboard]] —— Dataview 仪表盘(近期更新 / 分类 / 待完善)
- [[lint-report-2026-06-21]] —— Lint 健康检查报告
- [[mechanisms-moc]] —— 机制拓扑 MOC(责任链档位 + 问题 ↔ 防御组合)
- [[modules-moc]] —— 模块依赖 MOC(三层模型 + 关键调用链)

## 架构决策记录(adr/)

- [[0001-positioning]] —— 定位为「Spring Cache 防护增强注解生态」(Q1,已被 0006 取代)
- [[0002-keep-interceptor]] —— 保留 interceptor+Advisor、弃装饰器(Q3)
- [[0003-serialization-envelope]] —— 序列化信封 + 迁移路径,不放松白名单(Q4)
- [[0004-protection-preset]] —— protection.preset 批量启用,而非默认全开(Q6)
- [[0005-kernel-extraction-hedge]] —— 内核无关化仅作长寿对冲,不近期执行
- [[0006-redisson-companion-positioning]] —— 定位为「ResiCache for Redisson」(取代 0001 定位叙事)
- [[0007-fire-single-buildline-abandonment]] —— WS-1.1 双分支策略废弃,统一单构建 Boot 4.0
- [[0008-observation-spans-attribution]] —— Observation spans 归属(v0.1.0 + 不归 ADR-0005 范畴,Proposed 待 review)
- [[0009-chain-engine-extraction]] —— Chain Engine 抽出(责任链推进+观测收口单一 seam;Proposed,未实现)
- [[0010-attributes-projection-and-strategy-deletion]] —— Attributes 投影层 + TwoListEvictionStrategy 删除(注解×工厂字段映射去重)
- [[0011-bloom-key-drift-fix-and-cachekeys-seam]] —— Bloom 键漂移修复 + CacheKeys 键派生 seam(sync+bloom 静默 null)
- [[0012-interceptor-consolidation-and-shallow-module-removal]] —— Path C interceptor 残骸收敛(Helper 死代码 + ResiCacheMethodInterceptor pass-through)+ EarlyExpirationSupport 浅模块删除(Spring 注解映射不合并裁决)
- [[0013-annotation-chain-engine-extraction]] —— AnnotationChainEngine + AnnotationChainObserver 抽出(平行 ADR-0009 seam,注解解析链单一化)
- [[0014-constructor-telescoping-collapse]] —— `RedisProCache` + `RedisProCacheManager` 构造重载墙收敛(单一 seam)
- [[0015-annotation-handler-registerall-deepening]] —— `AbstractAnnotationHandler.registerAll` 批量注册模板下沉(5 处 for-loop 收敛为单行委派)
- [[0016-observer-registry-seam-and-manager-instantiate-seam]] —— `ObserverRegistry<O>` 跨 engine observer 列表去重 seam + `RedisProCacheManager` instantiate seam 收敛
- [[0017-operation-fromattributes-seam]] —— `XxxOperation.fromAttributes(method, key, attributes)` 静态 seam + `RedisCacheAttributes` 移到 `operation/` 包(factory materialize 1-liner 委派)
- [[0018-semantic-counter-template-method]] —— `AbstractCacheHandler.semanticCounter()` 模板方法(5 个 protection handler 的 `onAttachMetrics` 样板收敛为基类 record 字段 + null-safe `safeIncrementSemantic()`,counter 名字仍 unique)
- [[0019-projector-fieldsource-seam-and-type-drift-deferral]] —— `RedisCacheAttributesProjector.FieldSource` 私有 record + 单一 `project()` seam(3 个 `from(annotation)` 26-line 重复墙收敛为 1-liner 委派)+ `@RedisCacheable.expectedInsertions` int/long type-drift 显式 defer(1.0 毕业统一)
- [[0020-annotation-targets-annotatedelement-seam]] —— `AnnotationTargets` 反射多态 utility seam(annotation 包 23 处 `instanceof Method/Class` 收敛为 `AnnotatedElement` 多态路径,6 对 Method/Class 重载合并为 6 个多态方法)
- [[0021-redis-cache-attributes-applyto-seam-and-protection-toggle]] —— `RedisCacheAttributes.applyTo(B)` seam(3 fromAttributes 22-line 重复墙收敛为 1 行委派)+ `ProtectionToggle` Function 化(CacheHandlerChainFactory 4 disabled-handler if-block 收敛为 list iteration)

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
- [[holder-and-config]] —— 方法元数据解析(`MethodMetadataResolver`) + config 装配辅助类

## 概念(concepts/)

- [[cache-penetration]] —— 穿透:布隆 + 空值双防线
- [[cache-breakdown]] —— 击穿:分布式锁串行化回源
- [[cache-avalanche]] —— 雪崩:TTL 抖动 + 提前过期
- [[hot-key]] —— 热点 key:提前过期异步刷新

## 操作指南(how-to/)

- [[add-protection-handler]] —— 4 步新增防护 handler(含示例)
- [[configure-behavior]] —— 三层配置实操与典型套餐

---

最后更新:2026-07-01 · 共 40 页 · 维护见 [[log]]
