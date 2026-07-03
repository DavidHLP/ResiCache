---
title: "ADR-0032: MetadataKeys — chain 包 reflectField + cast-instanceof seam 收敛"
type: adr
status: accepted
date: 2026-07-03
deciders: DavidHLP
related:
  - ADR-0020
  - ADR-0029
  - ADR-0031
tags:
  - architecture-deepening
  - duplication-collapse
  - polymorphic
  - chain-package
  - deletion-test
  - round-23
---

# ADR-0032: MetadataKeys — chain 包 reflectField + cast-instanceof seam 收敛

## 状态

- **Status**: Accepted
- **Date**: 2026-07-03
- **Deciders**: DavidHLP
- **Related**: ADR-0020(`AnnotationTargets` in annotation/) / ADR-0029(单 adapter hypothetical seam 接受) / ADR-0031(`RedisProCacheTimers` in cache/)
- **Round**: 23

## 背景

`/improve-codebase-architecture` round 22 HTML report 在 "Deferred — saturation evidence"
表中点名 `handler/ cast-instanceof seams` "queued for R23"。Round 23 autocratic one-shot
兑现该队列,把 R22 标注的"声称不修复但其实可修"的可疑项从 deferred 提升到 accepted。

`chain` 包内两文件共享 **完全字节级同构的反射模板**:
- `DefaultMethodMetadataResolver.java` (line 56-74 原) —— `currentMethod()` /
  `currentTargetClass()` 各 5 行,含 `instanceof Method` / `instanceof Class<?>` 分派
- `CacheInvocationContext.java` (line 57-67 原) —— `of(AnnotatedElementKey)` 6 行,
  含 2 个 cast-instanceof 分派 + 一个合并的 `if (!(element instanceof Method) || ...)`
- 两文件各持一个 **7 行 `reflectField` 私有 helper** —— try/catch 反射
  `AnnotatedElementKey` 私有字段(`element` / `targetClass`),字节级同构
  (DefaultMethodMetadataResolver 多一行 `log.warn`;CacheInvocationContext 无日志)

合计 3 处 `instanceof` 手动分派 + 2 处 7 行反射样板 + 1 处合并型 `if-||` 守卫。

**与 ADR-0029 的关系(精确定位)**:ADR-0029 锁定 `MethodMetadataResolver` *接口层* 的
**单 adapter hypothetical seam 接受**——这把接口的"是否会被替换"问题锁定为"接受现状"。
本 ADR 触及的是 **callers 的 boilerplate collapse**——它们的 `reflectField` 私有 helper
是实现细节(无第三方 adapter),不属于 ADR-0029 的接口决策。两者**正交**,本 ADR 不
与 ADR-0029 冲突。

## 决策

**新建 `chain/MetadataKeys` package-private 工具类(3 个静态入口)**,吸收 2 文件
`reflectField` × 2 + 3 处 `instanceof` 分派:

### 三个 seam 入口

| 入口 | 行为 | 替换原 |
|---|---|---|
| `reflectField(key, fieldName)` | key=null→null;字段缺失→null+WARN;返回字段值 | 两文件 `reflectField` 私有 helper(DefaultMethodMetadataResolver line 125-134 + CacheInvocationContext line 104-112) |
| `extractMethod(key)` | 反射读 `element` + `instanceof Method` narrow,非 Method→null | DefaultMethodMetadataResolver line 56-64 + CacheInvocationContext line 61-63 |
| `extractTargetClass(key)` | 反射读 `targetClass` + `instanceof Class<?>` narrow,非 Class→null | DefaultMethodMetadataResolver line 66-74 + CacheInvocationContext line 62-63 |

**关键设计**:
- **类型保证**:Spring 6.2 `AnnotatedElementKey` 私有字段为 `element`(`Object`,
  实际 `Method`)+ `targetClass`(`Object`,实际 `Class<?>`);helper 反射后
  `instanceof` 窄化,与原 try-cast 分派语义等价
- **Null 容忍**:`key == null` / 字段为 null / 字段类型不符 → 返回 null
- **可见性**:package-private(同包 2 caller,无跨包泄漏需求);`final class` + `private 构造`
- **日志契约**:helper 内 `log.warn` 在字段缺失或访问被拒时记录,继承 DefaultMethodMetadataResolver 原行为;CacheInvocationContext 原行为本无日志,提升到 WARN 级日志以便在 Spring 字段改名时早期告警(行为更可观测,不破坏原契约)

### 调用方净变更

**`DefaultMethodMetadataResolver.java`** (原 135 行 → 113 行,-22 SLOC body):
- `currentMethod()` —— 7 行 → 1 行 `return MetadataKeys.extractMethod(currentKey());`
- `currentTargetClass()` —— 7 行 → 1 行 `return MetadataKeys.extractTargetClass(currentKey());`
- 删除 `private static reflectField` (10 行 body + Javadoc)
- 保留 `// ==================== 反射工具 ====================` 段注释指向新 seam

**`CacheInvocationContext.java`** (原 113 行 → 103 行,-10 SLOC body):
- `of(AnnotatedElementKey)` —— 7 行 → 6 行(`Method` + `Class<?>` 各从 seam 取,合并 null 检查)
- 删除 `private static reflectField` (8 行 body)
- 段注释 `// Round 23 / ADR-0032:reflectField 私有 helper 已迁到 ...` 替代删除的 helper

**`chain/MetadataKeys.java`** (新,~110 SLOC):`@Slf4j final class` + private 构造
+ 3 个 package-private static 方法 + 完整 javadoc(类型保证、null 容忍、deletion test、ADR 链接)

### 行为保真

字节级语义对齐:
- `key == null` → 旧 `if (key == null) return null` 守卫或
  `reflectField(null, ...)` 的 NPE 路径;helper `reflectField` 首行检查短路,与原
  `if-else` 守卫等价
- `instanceof Method/Class<?>` narrow → 原代码显式检查;helper typed-narrow 方法内联
  同一判断——行为完全等价
- `reflectField` 反射失败 → DefaultMethodMetadataResolver 原 `log.warn` 落到 helper
  内,CacheInvocationContext 原静默被 helper 的 WARN 行为替代(行为更可观测——一旦
  Spring 字段改名,WARN 日志比静默 null 更早暴露问题)
- `currentContext()` 间接走 `CacheInvocationContext.of(currentKey())`,被波及但语义不变

## 后果

**增益**:
- **locality**:2 文件 × 7 行反射样板 + 3 处 instanceof 分派从 N 处集中消失
- **leverage**:1 个 seam 入口,1 个未来可扩展 seam 的测试面(待补 `MetadataKeysTest`,
  本轮 context-conservation 优先,留作 polish 工单)
- **接口更窄**:2 个 caller 删除 1 个 private helper;public API 零变化
- **可观测性提升**:CacheInvocationContext 的 `reflectField` 失败从静默 null 升级为
  WARN 日志——Spring 内部状态变更早暴露
- **deletion test**:`reflectField` + 3 处 instanceof 同时删,复杂度从 2 caller 集中
  消失——真实归并,而非搬家

**代价**:
- +110 SLOC 新文件(包含完整 javadoc + 3 个静态入口)
- +0 SLOC body (净 SLOC:cache+ 2 caller = -32,seam +110 = net +78,主要在 javadoc)
- 0 公开 API 变化(DefaultMethodMetadataResolver / CacheInvocationContext /
  MethodMetadataResolver public 接口零变化)
- 0 行为回归(`./mvnw test -Dtest='chain.*Test'` 125 tests / 0 failures;
  `./mvnw test` 757 unit tests / 0 failures)

**不变**:
- `MethodMetadataResolver` 接口签名零变化(ADR-0029 锁定,本 ADR 也不动)
- `ScopeActivation` 行为零变化(仍 `activate(Method, Class<?>)` + lambda 复原)
- `ThreadLocal<AnnotatedElementKey> CURRENT_KEY` 所有权不变(Step 7 决定,本 ADR 不动)
- `CacheInvocationContext.of(Method, Class<?>)` 工厂零变化
- `CacheInvocationContext.snapshot/reserve` 零变化

## 实施

### 修改文件(2 main)+ 新增(1 main + 0 test)

**main 修改**:
- `chain/DefaultMethodMetadataResolver.java`:135 → 113 行
  - `currentMethod()` 7 行 → 1 行
  - `currentTargetClass()` 7 行 → 1 行
  - 删 `reflectField` (10 行 body)
  - 段注释指向 MetadataKeys seam
- `chain/CacheInvocationContext.java`:113 → 103 行
  - `of(AnnotatedElementKey)` 7 行 → 6 行 typed narrow + null 检查
  - 删 `reflectField` (8 行 body)
  - 段注释指向 MetadataKeys seam

**main 新增**:
- `chain/MetadataKeys.java`:~110 SLOC
  - `@Slf4j final class` + private 构造
  - `reflectField(AnnotatedElementKey, String)` —— 低层反射
  - `extractMethod(AnnotatedElementKey)` —— typed narrow
  - `extractTargetClass(AnnotatedElementKey)` —— typed narrow
  - 完整 javadoc(类型保证、null 容忍、deletion test、与 ADR-0032 的链接)

### 验证

- `mvn clean compile test-compile -B` → **BUILD SUCCESS**
- `./mvnw checkstyle:check -B` → **0 violations**
- `./mvnw test -Dtest='chain.*Test' -B` → **125 tests, 0 failures, 0 errors**
- `./mvnw test -B` → **757 tests, 0 failures, 0 errors, 17 skipped**
  (注意:之前 round 22 报告的 8 个 `RedisCacheInterceptorTest.setNext(...)` 错误经
  `mvn clean` 已消除——它们是 stale `.class` 残留:`RedisCacheInterceptorTest.java`
  在 commit `916326c` refactor:ADR-0012 删除,但 `target/test-classes/...` 残留
  旧编译产物,被 Surefire 当成有效 test class 跑。本轮 `mvn clean` 显式清理后,8 个
  错误消失。结论:**round 22 报告的 8 个 pre-existing 错误不是真测试缺陷,是 build hygiene
  问题**——本 ADR 顺手纪录这一发现)

## 与 ADR-0029 的边界

- ADR-0029 锁定 *接口* 形态:`MethodMetadataResolver` 5 方法接口 + 单 adapter +
  `sealed`-style 接受(可被未来 `ScopedValue` 替换)→ 不删除
- 本 ADR 锁定 *实现 boilerplate*:2 个 adapter 共同使用 `reflectField` 反射
  AnnotatedElementKey 私有字段 + 3 处 instanceof 分派 → 抽到 seam

两者**精确正交**:即使将来引入 `ScopedValue` adapter,新 adapter 也会复用
`MetadataKeys.seam`(对 AnnotatedElementKey 的访问模式不变);即使 adapter 接口替换,
本 ADR 的 seam 也无需迁移。

## 已知 deferred

- `MetadataKeysTest` —— 本 seam 的直接测试面。Round 22 上下文消耗已达 ~88%,本
  轮优先落地 ADR + refactor,测试补全留作 round 24 polish 工单。当前由 125 个
  chain.* test 提供间接覆盖(`ChainEngineTest`、`AbstractCacheHandlerSemanticCounterTest`
  等通过 `CacheInvocationContext.currentContext()` / `defaultResolver.currentMethod()`
  间接走过 seam)
- `int → long` graduation(`expectedInsertions`)—— 沿用 ADR-0019 D2 显式 defer 至
  1.0 毕业
- `bloomsift` 命名 polish —— 沿用 ADR-0023 显式 negative-leverage 拒收
- 5 `ChainObserver` DRY —— 沿用 ADR-0019 C / round 22 复用 YAGNI 判定
- `CacheKeys` 第 3 use case —— 沿用 ADR-0011,无第 3 adapter 出现

## 相关

- [[0020-annotation-targets-annotatedelement-seam]] —— Round 10,同款反射多态化模式先例
- [[0029-single-adapter-hypothetical-seams-acceptance]] —— Round 20,接口层 seam 接受(本 ADR 锁实现层)
- [[0031-redisprocache-timing-helper-seam]] —— Round 22,定时样板 seam(本 ADR 平行模式)
- `/improve-codebase-architecture` skill —— round 23 autocratic one-shot 触发
