---
title: "ADR-0034: RedisProCacheWriter context-build 三路分裂 → 单一 buildContext seam"
type: adr
status: accepted
date: 2026-07-03
deciders: DavidHLP
related:
  - ADR-0033
  - ADR-0026
tags:
  - architecture-deepening
  - shallow-module-removal
  - seam
  - locality
  - deletion-test
  - round-25
---

# ADR-0034: RedisProCacheWriter context-build 三路分裂 → 单一 buildContext seam

## 状态

- **Status**: Accepted
- **Date**: 2026-07-03
- **Deciders**: DavidHLP
- **Related**: ADR-0033(CacheOutput typed decisions,把 keyPattern 升格为 CacheContext direct field)/ ADR-0026(曾删 CacheContextBuilder)
- **Round**: 25(`/improve-codebase-architecture` round 25, Top recommendation C1 落地)

## 背景

Top recommendation C1 锁定 `RedisProCacheWriter` 的 CacheContext 构造三路分裂为最深层 shallow module:

| 构造点 | cacheOperation 来源 | keyPattern | 状态 |
|---|---|---|---|
| `executeChain` → `buildContext`(GET/PUT/PUT_IF_ABSENT/REMOVE) | 查 register | 无 | live |
| `put(5参)` 重载 | 直接传入 | 无 | live —— **内联 build,绕过 buildContext**,8 个 CacheInput.builder() 调用与 buildContext 字节级重复 |
| `clean` → `buildContext` + 后置 mutate | 查 register | 后置 `setKeyPattern` | live —— ADR-0033 兑现一半的尾巴(keyPattern 已升格 direct field,但 buildContext 签名未跟上 → build 后 mutate) |

作者自注(RedisProCacheWriter L350)承认三路分裂:「clean(需 setKeyPattern)与带 operation 的 put
重载因上下文构建方式不同,不经此入口。」

## 决策

**单一 9参 `buildContext` seam + `resolveOperation` helper**,3 路收敛为 1:

### D1 `buildContext` 签名扩展(7参 → 9参)

新增 `cacheOperation`(已解析,可为 null)+ `keyPattern`(CLEAN 传,其余 null)两个槽位。
内部:`CacheContext.of(CacheInput.builder()…)` 单点 + `if (keyPattern != null) context.setKeyPattern(keyPattern)`。

### D2 register 查询剥离为 `resolveOperation(cacheName)` helper

原 buildContext 内的 `methodMetadataResolver.currentKey()` + `redisCacheRegister.getCacheableOperation()`
+ 元数据缺失 log,抽成独立 `@Nullable resolveOperation`。buildContext 不再查 register,只负责构造。

### D3 3 个调用点收敛

- **`executeChain`**(GET/PUT/PUT_IF_ABSENT/REMOVE):`buildContext(…, resolveOperation(name), null)`
- **`put(5参)`**:`buildContext(PUT, …, operation, null)` —— operation 直传,跳过 register 查询
- **`clean`**:`buildContext(CLEAN, …, resolveOperation(name), keyPattern)` —— keyPattern 前置,后置 mutate 消失

### 路径独裁(本 ADR 拒绝的替代方案)

- **替代 A**:新建独立 `CacheContextBuilder` 类 —— **拒绝**:ADR-0026 刚删过同名类,重建有反复风险;
  buildContext 本就是该职责的 writer 内载体,扩签名即可,无需新增 module。
- **替代 B**:把 register 查询留在 buildContext 内(用 `Supplier<RedisCacheableOperation>`)—— **拒绝**:
  concern 未分离,put5参仍需绕过。剥离 resolveOperation 让 buildContext 纯粹(只构造)+ 调用方各自解析。

## 不变量(preserved invariants)

- **public API 零变化**:`put(5参)/get/put/putIfAbsent/remove/clean/clear/retrieve/store` 签名全不变;
  `buildContext`/`resolveOperation` 均 private。
- **clean 语义**:keyPattern 同时作 redisKey 传 buildContext + 作 keyPattern 设置 —— 保持原行为,
  仅从「build 后 mutate」改为「build 时直传」。
- **operation 解析语义**:elementKey 查 register + 缺失 log.debug —— 零逻辑变化,纯位置迁移到 resolveOperation。
- **CacheInput / CacheContext 模型**:零变化(ADR-0033 typed decisions + keyPattern direct field 不动)。

## 验证

- **JDK 17 release=17 编译**:`./mvnw test-compile -Dmaven.compiler.release=17` EXIT=0
  —— 改动语法/类型/签名正确,不依赖 Java 21 专有 API。
- **JDK 21 全量测试**:本地环境 JDK 21 不可用(vfox `v-21.0.2+13` 损坏 `libjli.so` + 两个 OpenJDK 21
  tarball 下载不完整 + Fedora 44 WSL 源仅 `java-25-openjdk`),改用 `java-25-openjdk-devel` 以
  `--release 21` 编译口径跑全量 `./mvnw test`(JDK 25 javac 支持 target 21)。baseline 见 ADR-0033
  (756 tests, 0 failures, 17 skipped = Testcontainers Redis 环境无 Docker)。
- **checkstyle + main + test 编译**:JDK17 口径 BUILD SUCCESS。

## 内部红蓝博弈(CR & Fix)

| 反方意见 | 反驳 / 处理 |
|---|---|
| 9 参 buildContext 签名太长? | 9 参 = CacheInput 8 字段诚实映射 + 1 keyPattern;private 不污染 API;net 替代 3×8=24 行 builder 重复。 |
| keyPattern 用 null 哨兵反模式? | CacheInput 无 keyPattern 字段(仅 CLEAN 用),null 表「非 CLEAN 无 pattern」是自然语义;局部 `if != null` 防御,非 leak。 |
| resolveOperation 多一次方法调用开销? | JIT 内联,private helper,可忽略;concern 分离收益远大于。 |
| 删过 CacheContextBuilder(ADR-0026)又扩 buildContext,反复? | 非反复。ADR-0026 删的是「input 不可变 + mutable builder 分离」的中间层;本 ADR 扩的是 writer 内 private 构造 helper,在 ADR-0033 typed decisions + keyPattern direct field 之后的新形态。 |

## 关联 wiki 路径

- `wiki/adr/0034-writer-context-build-single-seam.md`(本文件)
- `wiki/log.md` round 25 entry
- ADR-0033(keyPattern direct field 前置条件)/ ADR-0026(拒绝重建独立 CacheContextBuilder 类)
