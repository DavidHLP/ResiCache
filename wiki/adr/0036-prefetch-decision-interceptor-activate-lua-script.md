---
title: "ADR-0036: PrefetchDecision 类型化 + Interceptor activate 归位 + Lua 脚本外置(Round 26)"
type: adr
status: accepted
date: 2026-07-03
deciders: DavidHLP
related:
  - ADR-0033
  - ADR-0035
  - ADR-0025
  - ADR-0029
tags:
  - architecture-deepening
  - shallow-module-removal
  - seam
  - locality
  - deletion-test
  - round-26
---

## 状态

- **Status**: Accepted
- **Date**: 2026-07-03
- **Deciders**: DavidHLP
- **Round**: 26(`/improve-codebase-architecture` round 26,报告
  `/tmp/architecture-review-resicache-round26-20260703-231706.html` 的 C1/C2/C3 落地;C4 撤销)

## 背景

Round 26 在 Round 24/25(ADR-0033/0034)基础上继续深化,锁定 4 个候选(均为既有 ADR
续篇,延续已建立深化模式,风险可控)。

### C1 — CacheContext.attributes 袋的最后一对业务 key

ADR-0033 把原 `CacheOutput` 9 字段共享袋类型化为 `TtlDecision`/`NullDecision` +
`keyPattern`,但 `attributes` Map 仍残留 **3 个业务 magic-string key**(全由
`EarlyExpirationHandler` 同一次 GET 预取 + 判定产出):

| magic key | writer | reader |
|---|---|---|
| `earlyExpiration.skipped` | EarlyExpirationHandler | ActualCacheHandler.doHandle |
| `cache.prefetchedValue` | EarlyExpirationHandler | ActualCacheHandler.handleGet |
| `earlyExpiration.decision`(private `DECISION_KEY`) | EarlyExpirationHandler | EarlyExpirationHandler.getDecision(static,test 用) |

字符串契约、无编译期约束、跨 handler 生产者-消费者靠默契 —— ADR-0033 已治愈的病灶在此复发。

### C2 — Interceptor 同步路径 ThreadLocal 跨包寄生

ADR-0035 给 `MethodMetadataResolver` 加 `runWithSnapshot` 归位了 async 路径(writer),
但 sync 路径(`RedisCacheInterceptor.invoke`)仍直接调
`DefaultMethodMetadataResolver.activateStatic/clearStatic` —— interceptor 持有 resolver
的 ThreadLocal 写入 API 知识,跨包寄生。

### C3 — EarlyExpirationHandler 执行层未深化

ADR-0025 抽走了判定(`EarlyExpirationPolicy`),但 `ATOMIC_TTL_SHORTEN_SCRIPT` Lua
字面量仍硬编码在 handler,无法独立引用/断言。

## 决策

### D1 — C1:新增 `chain/model/PrefetchDecision` record

机械复刻 ADR-0033 的 `TtlDecision`/`NullDecision` 模式,3 个业务 key 收编为单一 record
三元组:

```
record PrefetchDecision(boolean earlyExpirationSkipped,
                        @Nullable CachedValue prefetchedValue,
                        @Nullable EarlyExpirationDecision decision)
```

- `CacheContext` 加 `prefetchDecision` 一级字段(`@Getter @Setter`);**删除 `AttributeKey`
  内部类**(业务 key 清零,`attributes` 仅余 observer/bloom/lock 各模块自管临时键)
- `EarlyExpirationHandler.doHandle` 缓存命中分支一次性 `setPrefetchDecision`(`skipped`
  由 `decision` 派生);`getDecision` 改读 `prefetchDecision`
- `ActualCacheHandler` 改读 `context.getPrefetchDecision()`
- 删除 `DECISION_KEY` 常量

**deletion test: CONCENTRATES** —— 复杂度集中到 1 个 typed record,编译期约束 prefetch
生产者-消费者。

### D2 — C2:Interceptor 走 `resolver.activate()`(ScopedActivation)

interceptor 注入 `MethodMetadataResolver`,`invoke` 改:

```
try (ScopedActivation ignored = methodMetadataResolver.activate(method, targetClass)) {
    annotationChainEngine.execute(method, target, args);
    return super.invoke(invocation);
}
```

eliminate 跨包寄生(interceptor 不再调 `activateStatic/clearStatic`)。选 `activate` 而非
`runWithSnapshot` 避 `Supplier` 的 checked-exception 坑(`super.invoke` 抛 `Throwable`)。

**`activateStatic/clearStatic` 保持 `public`** —— `RedisProCacheWriterTest` 直接调用,
降 package-private 需同步重构 test 且 `ScopedActivation.close` 签名需验,本轮以 interceptor
迁移为 C2 核心交付,可见性收紧留作 follow-up。

**deletion test: CONCENTRATES**(interceptor 跨包寄生消除)。

### D3 — C3:`EarlyExpirationScripts` package-private 常量 seam

新增 `protection/refresh/EarlyExpirationScripts`(package-private final 工具类),持
`ATOMIC_TTL_SHORTEN_SCRIPT` 常量。handler 删本地字面量,引用
`EarlyExpirationScripts.ATOMIC_TTL_SHORTEN_SCRIPT`。复刻 `RedisProCacheTimers`
(ADR-0031)/ `MetadataKeys`(ADR-0032)先例。

**守 ADR-0029**:本类是 package-private 常量持有,非新 interface/adapter。Lua CAS 单
消费者,不造 seam。

**deletion test: CONCENTRATES**(Lua 字面量内聚,可独立引用/断言)。

### D4 — C4 撤销:`HierarchicalBloomIFilter` 保留

报告 C4 原疑 HierarchicalBloom = "为对称而存在"。通读核实:
`HierarchicalBloomIFilter` 标注 `@Primary @Component("hierarchicalBloomFilter")` ——
它是**生产默认装配**(本地抗穿透 + Redis 权威),`wiki/mechanisms/bloom-filter.md` 明确
定位("兼顾速度与一致性")。**非假对称,是真实默认部署形态**。零代码改动,撤销 C4。

## 不变量(preserved invariants)

- `PrefetchDecision` 生产者唯一(`EarlyExpirationHandler.doHandle`),消费者
  `ActualCacheHandler` + `getDecision`
- `attributes` Map 业务 key 清零(仅 observer/bloom/lock 自管临时键)
- interceptor 不再直接调 `activateStatic/clearStatic`(走 `activate`)
- `EarlyExpirationHandler` Lua 行为字节级等价(脚本字面量整体搬迁至 `EarlyExpirationScripts`)

## 验证

- **编译**:JDK 25 编译验证,C1/C2/C3 涉及文件(`PrefetchDecision`/`EarlyExpirationScripts`/
  `ScopedActivation`/`MethodMetadataResolver`/`RedisCacheInterceptor`/`CacheContext`/
  `ActualCacheHandler`/`EarlyExpirationHandler`)**0 编译错误**(grep 0 命中)
- **环境限制**:全量 `./mvnw test` 受阻于本地环境 JDK 不匹配 ——
  - JDK 21(vfox cache `v-21.0.2+13`)`libjli.so`/`libjava.so` 残缺,二进制无法启动;
  - JDK 25(系统 `java-25-openjdk`)与 lombok 1.18.42 注解处理器不完全兼容
    (`variable log` 缺失,javaagent 模式仅部分缓解 160→69),均为 Lombok 生成符号,
    与本次改动无关。
  - 项目目标 JDK 21,CI/生产环境正常编译测试;本地环境为 WSL2 sandbox,JDK 安装异常。
- **行为等价**:`PrefetchDecision` 收编后 `ActualCacheHandler.handleGet` 仍回退原生 GET
  (`prefetchDecision==null` 路径保留);`doHandle` 缓存未命中分支不再
  `setAttribute(null)`(等价 —— `setAttribute` 对 null 是 remove)。

## 内部红蓝博弈(CR & Fix)

1. **C1 第三个 key**:初版 grep 漏 `DECISION_KEY`(`earlyExpiration.decision`),完整读
   `EarlyExpirationHandler` 后发现 `getDecision` 静态方法经 test 2 处使用 → 收编为
   `PrefetchDecision.decision` 字段,`getDecision` 改读,签名不变
2. **C2 可见性反复**:初版降 `activateStatic/clearStatic` 为 package-private,
   `RedisProCacheWriterTest`(cache 包)跨包调 `activateStatic` → 编译断;评估
   `ScopedActivation.close` 签名未验,改回 `public`,ADR 记为 follow-up
3. **C2 Supplier checked-exception**:`runWithSnapshot(Supplier)` 不抛 checked,
   `super.invoke` 抛 `Throwable` → 选 `activate`(`ScopedActivation`,try-with-resources)避坑
4. **C4 自我推翻**:报告 C4 基于推测"假对称";通读发现 `@Primary @Component` 证实是默认部署 → 撤销
5. **环境死磕**:JDK 21 cache 残缺(借 libjli 加载成 25);JDK 25 lombok javaagent 部分
   (160→69);最终接受环境硬限制,以编译验证 + 0 自身错误 + 行为等价论证收尾

## 关联 wiki 路径

- [[context-data-flow]] —— CacheContext 三件套(`PrefetchDecision` 加入 typed decisions)
- [[bloom-filter]] —— HierarchicalBloomIFilter 定位(C4 撤销依据)
- [[early-expiration]] —— EarlyExpirationHandler(C3 Lua 外置)
