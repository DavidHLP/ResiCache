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
updated: 2026-07-02
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
- [[0022-chain-single-representation-seam]] —— Chain single-representation seam(消除 ADR-0009 残留的 next 指针 × List 快照双轨,`CacheHandler` 接口删 setNext/getNext + `driveChain` 改 index 推进 + 修复并发隔离漏洞)
- [[0023-executor-graceful-shutdown-seam]] —— Executor graceful-shutdown seam(`ThreadPoolEarlyExpirationExecutor.shutdown()` 两段逐字重复的优雅关闭样板收敛为单一 `shutdownGracefully` 私有 seam,byte-for-byte 行为等价;round 15 扫尽未触及域后唯一经 deletion test 通过的 friction)
- [[0024-early-expiration-pool-config-seam]] —— early-expiration 线程池配置接入 seam(兑现 dead config:`@Component` 无参硬编码 → `@Bean` 从 `EarlyExpirationProperties` 读池参数;+ 6 处 `EarlyExpirationSupport` stale wiki 引用清理;round 16 跨域接缝盲区排查唯一命中)
- [[0025-early-expiration-policy-seam-extraction]] —— early-expiration 决策 policy seam 迁出 TtlPolicy(refresh↔avalanche 跨域寄生方法 + `Clock` 依赖归位;`shouldEarlyExpiration` → refresh 自有 `EarlyExpirationPolicy.shouldRefresh`;`DefaultTtlPolicy` 无状态化;5 机制 policy seam 齐整;round 17 跨域寄生 seam)
- [[0026-round14-contextbuilder-deletion-foreachsafe-and-sealings]] —— Round 14: CacheContextBuilder 删除 + ObserverRegistry.forEachSafe 异常语义统一 + 候选 3/4 封口(D1删Builder/D2 forEachSafe/D3删死常量/D4 SpringAnnotationAdapter封口/D5 typed-key封口)
- [[0027-annotation-parser-put-evict-spring-standard-alignment]] —— @RedisCachePut/@RedisCacheEvict AnnotationParser 对齐(单注解探测修补 + Spring 标准类,纠正 4 轮 ADR 的环境误诊;D1补@RedisCachePut探测/D2 Evict标准类/D3 Put标准类/D4删import/D5修测试ClassCast)
- [[0028-operationfactory-seam-narrowing-and-applytext]] —— OperationFactory seam 收窄(删 supports 死链 + create 5参→3参 + 删 AbstractOperationFactory)+ SpringAnnotationAdapter applyText 收敛(3 build 方法 17 处 if-hasText-set → Consumer method reference;**重开 ADR-0026 D4**;round 20)
- [[0029-single-adapter-hypothetical-seams-acceptance]] —— 单-adapter hypothetical seam 接受策略(MethodMetadataResolver + BloomHashStrategy;可逆性对冲,锁定不删;round 20)
- [[0030-redisprocachewriter-dead-accessors-removal]] —— RedisProCacheWriter.getTtl(String)/getExpiration(String) 2 个死 protected 方法删除(零调用零子类零反射,deletion test 通过;round 21)
- [[0031-redisprocache-timing-helper-seam]] —— RedisProCache 6 处 try-finally + System.nanoTime() + safeRecord timing 样板 → RedisProCacheTimers package-private 工具 seam(registerTimer/registerCounter/safeIncrement/timed/timedGet 5 个静态入口;net -83 body SLOC;byte-equivalent;round 22)
- [[0032-metadata-keys-extract-seam]] —— chain 包 DefaultMethodMetadataResolver + CacheInvocationContext 2 个 reflectField × 7 行字节级同构 + 3 处 cast-instanceof 分派 → MetadataKeys package-private 工具 seam(reflectField/extractMethod/extractTargetClass 3 个静态入口;可观测性提升 CacheInvocationContext 失败从静默升级为 WARN;round 23 兑现 round 22 R23 队列)
- [[0033-cacheoutput-typed-decisions]] —— `CacheOutput` 9 字段共享可变袋(2 字段死 + 5 owner 跨包泄漏 + 1 engine control flow 错位)→ typed per-handler decisions(`TtlDecision`/`NullDecision` records)+ `keyPattern` direct field + `skipRemaining` 升格 context 一级;`getOutput()` 公共 API 消失;CacheOutput.java 97 SLOC 整删;round 24 兑现 round 24 HTML report Top recommendation C3
- [[0034-writer-context-build-single-seam]] —— RedisProCacheWriter context-build 三路分裂(put5参内联 / buildContext / clean 后置 mutate)→ 单一 9参 buildContext seam + resolveOperation helper(round 25 Top rec C1;兑现 ADR-0033 C2 候补 + 清 setKeyPattern 尾巴)
- [[0035-async-snapshot-resolver-attribution]] —— async snapshot/restore 跨域寄生归位 MethodMetadataResolver.runWithSnapshot(writer 删 30 行 withMethodMetadataSnapshot + 5 import;MDC 一并内聚;byte-equivalent;round 25 Worth exploring C2)
- [[0036-prefetch-decision-interceptor-activate-lua-script]] —— Round 26 三连深化:C1 PrefetchDecision 类型化(attributes 3 业务 key 收编,ADR-0033 续篇)+ C2 Interceptor activate 归位(消除跨包寄生,ADR-0035 续篇)+ C3 Lua 外置 EarlyExpirationScripts(守 ADR-0029);C4 HierarchicalBloom 撤销(@Primary 默认部署)
- [[0037-twolistlru-lock-wrapper-dead-code-and-false-seam-removal]] —— `TwoListLRU` 锁 wrapper 死代码 + false seam 删除(`readLockForKey` 零调用 + `writeLockForKey` 误导命名 false seam + `promoteNodeSafe` 零语义包装;byte-equivalent 39 测试绿;附带 eviction.md ADR-0010 stale 清理;round 27)
- [[0038-cachedvalue-wither-handlerpriority-order-noop-observer-dead-code-removal]] —— `CachedValue.withExpired/withAccessUpdate` 零调用 wither + `HandlerPriority.order()` deprecated 零读取死参数 + `NoOpAnnotationChainObserver` YAGNI 单例整删(byte-equivalent;Explore agent 6 候选核实斩 3 误诊;附带 index.md ADR-0033 错位修正;round 28)
- [[0039-cacheresult-dead-fields-and-noop-removal]] —— `CacheResult` 5 字段共享袋 → 2 字段(删 `hit`/`rejectedByBloomFilter`/`exception` 死字段 + 死读法,`failure(e)`→`failure()`)+ `NoOpChainObserver` YAGNI 单例整删(ADR-0038 同构漏网;byte-equivalent;6 测试类绿;round 29 HTML review Top rec C1+C2 合并)
- [[0040-lockcontext-nulldecision-dead-factory-removal]] —— `LockContext.noLock()` + `NullDecision.passthrough()` 零调用 YAGNI 死工厂删除(ADR-0033 typed decision + LockContext 漏网;byte-equivalent;同步 ADR-0033 示例;round 30 Python 全仓扫描定位;同 ADR-0039 续篇)

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
- [[fix-docker-pull-ipv6-timeout]] —— 修复 Docker 拉取镜像 IPv6 timeout(WSL2 + 大陆网络环境)
- [[fix-wsl2-testcontainers-socat-forward]] —— 修复 WSL2 native docker 下 testcontainers 集成测试不通(bridge + socat 中转)

---

最后更新:2026-07-04 · 共 71 篇文档(含 37 ADR)· 维护见 [[log]]
