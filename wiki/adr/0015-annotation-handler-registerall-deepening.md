---
title: "ADR-0015: AnnotationHandler 批量注册样板下沉到基类 (5 处 for-loop 收敛为单行委派)"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0009
  - ADR-0013
  - ADR-0014
  - /tmp/architecture-review-1782872112.html
tags:
  - handler
  - annotation
  - registerAll
  - deepening
  - round-6
---

# ADR-0015: AnnotationHandler 批量注册样板下沉到基类

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0009 / ADR-0013 / ADR-0014 /
  `/tmp/architecture-review-1782872112.html` (round 5,候选 B 落地)

## 背景

`/improve-codebase-architecture` round 5 报告筛出候选 B(已显式延后到 round 6):
4 个 `AnnotationHandler` 子类 (`Cacheable` / `CachePut` / `Evict` / `Caching`)
的 for-loop + `registerOne` + null-check 样板重复,5 处 for-loop 收敛到基类
`AbstractAnnotationHandler.registerAll` 模板。

## 决策

### D1 — 基类暴露 `registerAll` 模板方法(执行)

**问题陈述**:4 个具体 handler 中,`CachePut` / `Evict` / `Caching` 3 个类的
`doHandle` 方法包含 5 处几乎逐字重复的样板:

```java
// 1. CachePutAnnotationHandler.doHandle (1 处)
RedisCachePut[] puts = method.getAnnotationsByType(RedisCachePut.class);
List<CacheOperation> operations = new ArrayList<>();
for (RedisCachePut put : puts) {
    RedisCachePutOperation operation = registerOne(
            method, target, args, put, put.key(),
            cachePutOperationFactory, redisCacheRegister::registerCachePutOperation, "cache put");
    if (operation != null) operations.add(operation);
}
return operations;

// 2. EvictAnnotationHandler.doHandle (1 处) — 与上同构,仅类型 / factory / register-ref 不同

// 3. CachingAnnotationHandler.doHandle (3 处) — 三种 RedisCacheable/Evict/Put 各一处
```

**形态对比**:
- 重载墙:每个 for-loop 11 行 × 5 处 = 55 SLOC,其中 ~40 SLOC 是同构样板
- 模板版:`AbstractAnnotationHandler.registerAll` 1 处实现 = 18 SLOC body
- **interface ≈ implementation**(浅模块病征:5 个 for-loop 接口对应 5 套样板)

**deletion test**:删 5 处 for-loop → 委派到 `registerAll` → 行为完全一致(空 list 行为变化见 D4)。
删 `registerAll` → 5 处 for-loop 重新出现 → 失败。`registerAll` 在**用 1 个 body 抵消 5 个 body**。

**决策**:`AbstractAnnotationHandler.registerAll(Method, Object, Object[], A[], Function<A, String>, OperationFactory<A, O>, RegisterAction<O>, String) → List<CacheOperation>`。

**关键设计**:
- **Function-based key 提取器**:`Function<A, String> keyExtractor` 而非硬编码 `key()` 调用,
  让 `registerAll` 对所有"有 key 字段"的注解统一工作。当前 3 个 ResiCache 注解
  (`@RedisCacheable` / `@RedisCachePut` / `@RedisCacheEvict`) 都提供 `.key()`,可
  直接 `RedisCacheable::key` 方法引用;未来如新增无 `.key()` 的注解类型,子类
  可提供自定义 extractor 而无需改基类
- **返回类型显式 `List<CacheOperation>`**(非 `List<O>`):原因见 D2
- **空数组 / null 数组 → `Collections.emptyList()`**:快速路径,免一次 ArrayList 分配
- **per-element 异常隔离**:由内部 `registerOne` 的 try/catch 保证,
  5 处 for-loop → 5 处 registerAll 调用,**单注解失败语义零变化**

**落地**:
- `AbstractAnnotationHandler.java`:新增 `registerAll` 方法(18 SLOC body + 35 SLOC Javadoc)
- 4 个 import 新增(`ArrayList` / `Collections` / `Function` / `List` 已有)
- 0 个 public API 删除,0 个 protected API 删除(`registerOne` / `generateKey` / `RegisterAction` 全部保留)

### D2 — 返回类型 `List<CacheOperation>` 而非 `List<O>`(执行)

**问题陈述**:`registerAll` 第一版尝试 `List<O> registerAll(...)`,在子类中
`return registerAll(...)` 触发 Java target-type 推断,把 `O` 拉到
`CacheOperation`(doHandle 契约),与 factory 的具体 `O`(`RedisCachePutOperation`)
冲突 → 编译失败。

**决策**:显式返回 `List<CacheOperation>`。两个理由:
1. 推断不被 `doHandle` 契约绑定,`O` 完全由 factory + method-ref 决定
2. `O extends CacheOperation` 保证每个 operation 安全上转,调用方拿到统一抽象层

**落地**:`registerAll` 内部 `ArrayList<CacheOperation>`,`O operation` add 进去(隐式上转)。

### D3 — `CacheableAnnotationHandler` 不参与本轮收敛(有意)

**问题陈述**:`CacheableAnnotationHandler` 的 `doHandle` 没有 for-loop,而是两条
`if-return` 路径(`@RedisCacheable` 与 Spring `@Cacheable` 二选一),每条路径
1 次 `registerOne` 调用,无迭代样板。

**未执行原因**:
- 形态不同(0 for-loop vs 5 for-loop),不属于本轮目标(批量迭代模板下沉)
- 用 `registerAll` 包 1 元素数组是负优化(单次 registerAll 调用 = 1 次 registerOne
  + 1 次 ArrayList 分配 + 1 次循环判定),现有 `registerOne` 已是局部最优
- 两条 if-return 路径结构清晰(命中第一个 return;否则继续),无重复样板可收敛

**封口**:**显式记录不参与本轮**。`CacheableAnnotationHandler.doHandle` 维持
原样,使用 `registerOne` 两次。

### D4 — 空数组行为:从 `new ArrayList<>()` 改为 `Collections.emptyList()`(执行,有意)

**问题陈述**:原 `doHandle` 在 `annotationsByType` 返回空数组时,行为是
`return new ArrayList<>()`(可变空 list)。新版 `registerAll` 对空数组返回
`Collections.emptyList()`(不可变空 list)。

**决策**:`Collections.emptyList()`。两个理由:
1. `doHandle` 契约从未承诺"返回可变 list",且 `AnnotationChainEngine.execute`
   是唯一调用方,只做 `collected.addAll(ops)`,对空 source 是 no-op,不受影响
2. `Collections.emptyList()` 是 Java 标准惯用法,零分配,语义清晰("没有 result")

**风险与缓解**:
- 风险:若未来某调用方对返回 list 做 `result.add(...)` 原地修改,会抛
  `UnsupportedOperationException`
- 缓解:Javadoc 显式说明"never null, may be empty",并把"不要原地修改"作为
  调用方隐含契约(与 Java `List.of()` / `Collections.unmodifiableList()` 等
  工厂方法返回的不可变 list 一致)
- 测试已覆盖空数组 / null 数组两条路径,锁定行为

## 后果

### D1 + D2 + D3 + D4 增益

- **3 个生产类 doHandle SLOC 显著下降**:
  - `CachePutAnnotationHandler.doHandle`:14 → 3 (-78%)
  - `EvictAnnotationHandler.doHandle`:13 → 3 (-77%)
  - `CachingAnnotationHandler.doHandle`:38 → 17 (-55%,三段 for-loop → 三行 addAll)
- **基类净增 SLOC 18**(registerAll body),加 35 SLOC Javadoc
- **全仓 SLOC 净变化**:`-38 (5 for-loop body) + 18 (registerAll body) = -20 SLOC`
- **测试 +0 → +8 净增**:`AbstractAnnotationHandlerTest` 8 个 contract 测试(空 / null /
  单元素 / 多元素 / 部分失败 / 全失败 / keyExtractor / KeyGenerator fallback)
- **零调用方修改**:1 个调用方(`AnnotationChainEngine.execute`)走 `collected.addAll(ops)`,
  对空/可变 list 行为完全一致
- **下游契约稳定**:`doHandle(Method, Object, Object[]) → List<CacheOperation>` 签名零变化
- **可读性提升**:`CacheableAnnotationHandler` 单独保留(2 个 if-return 路径清晰);
  其他 3 个 handler 退化到"取数组 + 委派 registerAll" 二步走,意图一目了然

### D1 + D2 + D3 + D4 代价

- **基类 API 表面增加 1 个 protected method**:`registerAll` 泛型方法 8 参,
  4 个 type parameters 限 2 个 type witness
- **空数组返回不可变 list**(D4 行为收窄,见上)
- **无公开破坏性变更**

## 实施

### 修改(5 文件,行数变化)

- `handler/AbstractAnnotationHandler.java`:新增 `registerAll` 模板(+18 body / +35 Javadoc)
- `handler/CachePutAnnotationHandler.java`:1 for-loop → 1 registerAll 委派(-11 / +3)
- `handler/EvictAnnotationHandler.java`:1 for-loop → 1 registerAll 委派(-11 / +3)
- `handler/CachingAnnotationHandler.java`:3 for-loop → 3 registerAll 委派(-33 / +6)
- `test/handler/AbstractAnnotationHandlerTest.java`:新增 8 个 contract 测试(+198 SLOC)
- `wiki/adr/0015-...md`:本 ADR(新增)
- `wiki/log.md`:本轮条目(追加)
- `wiki/modules/annotations.md`:代码示例小幅更新(后续)

### 验证

- `mvnw compile` —— **PASS**
- `mvnw test-compile` —— **PASS**
- `mvnw test`(全量 unit + integration with Testcontainers)——
  **BUILD SUCCESS,735 tests,0 failures,0 errors**(原 727 + 8 新 contract)
- `mvnw checkstyle:check` —— **0 violations**
- JaCoCo 覆盖率:`AbstractAnnotationHandler` 新增 `registerAll` 100% 行覆盖(8 个 contract 测试)

## 平行问题映射(本 ADR 不处理)

| 问题 | 状态 | 计划 |
| --- | --- | --- |
| Factory `materialize` builder 填充同构 | round 5 候选 C | YAGNI,下次新增字段触发重审 |
| `CacheableAnnotationHandler` 双 if-return 路径是否可统一 | 候选 D3 | 不动 — 形态与 for-loop 完全不同 |
| `RedisCacheRegister` registerXxxOperation 6 个薄包装 | 候选(C) | round 5 候选 C 同源,YAGNI |
| 5 observer / 5 handler 命名 counter 重复 | 不属本轮 | 各自命名独立,无明显可收敛重复 |
| `AnnotationChainEngine` execute 的 `safeArgs` 防御性兜底 | 不属本轮 | 文档已说明,本轮不动 |

## 参考

- `/tmp/architecture-review-1782872112.html`(round 5,候选 B)
- ADR-0009:ChainEngine 抽出(thin facade 模式先例)
- ADR-0013:AnnotationChainEngine 抽出(平行 seam 模式先例)
- ADR-0014:constructor telescoping collapse(单 seam 收敛模式先例)
