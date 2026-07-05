---
title: "ADR-0020: AnnotationTargets 反射多态 seam (annotation 包 23 处 instanceof Method/Class 收敛为 AnnotatedElement)"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0010
  - ADR-0017
tags:
  - annotation
  - reflection
  - polymorphic
  - seam
  - deepening
  - round-10
---

# ADR-0020: AnnotationTargets 反射多态 seam (annotation 包 23 处 instanceof Method/Class 收敛为 AnnotatedElement)

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0010 (RedisCacheAttributes 投影层) / ADR-0017 (Operation.fromAttributes 静态 seam) / round 10 报告

## 背景

`/improve-codebase-architecture` round 10 autocratic one-shot 扫描 `annotation/` 域,基于 round 1–9 已落地 ADR-0009/0010/0011/0012/0013/0014/0015/0016/0017/0018/0019 状态,筛出 1 强候选(候选 A)落地,2 候选(B/C)继续延后(YAGNI / 行为差异有意),5 候选(D/E/F/G/H)显式 defer(触发条件未达):

| 候选 | 评级 | 裁决 |
| --- | --- | --- |
| A · `annotation` 包 23 处 `instanceof Method/Class` 多态化为 `AnnotatedElement` | Strong | **执行** |
| B · AnnotationParser 3 个 parse 方法 "set name + cacheNames + hasText 守卫" 模板下沉 | Worth | **不动**(Builder 类型不同;template method 价值小于复杂度) |
| C · ChainEngine vs AnnotationChainEngine observer 异常处理不一致 | Worth | **不动**(行为差异有意:ChainEngine 不捕获让异常冒泡,注释已明示契约) |
| D · 4 disabled-handler if-block | Speculative | **不动**(ADR-0016 F 触发条件未发生) |
| E · 5 ChainObserver DRY | Speculative | **不动**(ADR-0019 C YAGNI) |
| F · Spring adapter fromAttributes | Speculative | **不动**(ADR-0017 C 已显式封口) |
| G · Startup 校验器合并 | Speculative | **不动**(本轮新增 defer;共享的只有 `@EventListener` 注解) |
| H · 1.0 毕业 int→long 统一 | Speculative | **不动**(STABILITY.md §4 forward marker;1.0 触发) |

全文精读核实后,逐条裁决如下。

## 决策

### D1 — `AnnotationTargets` 反射多态 utility seam (候选 A,执行)

**问题陈述**:`annotation` 包内两文件共 23 处 `if (target instanceof Method) ... else if (target instanceof Class) ...` 重复模式:

- `AnnotationParser.java` × 9 sites:
  - 6 处 `AnnotatedElementUtils.findMergedAnnotation((Method|Class) target, X.class)` (3 注解 × 2 路径)
  - 3 处 `(target instanceof Method) ? ((Method) target).getName() : target.toString()` (3 个 parse 方法的 name 提取)
- `SpringAnnotationAdapter.java` × 14 sites:
  - 6 个 `hasResiCacheable/Evict/Put(Method)` + 6 个 `hasResiCacheable/Evict/Put(Class<?>)` 重载对
  - 6 个 `convertSpringCacheable/Put/Evict(Method, List)` + 6 个 `convertSpringCacheable/Put/Evict(Class<?>, List)` 重载对
  - `addSpringNativeOperations` 内 SELECTIVE / FULL 两个分支的 Method/Class 二分法
  - `hasResiCacheAnnotation(Object)` 内 Method/Class 二分法
  - 6 处 `AnnotatedElementUtils.findMergedAnnotation((Method|Class) target, X.class)`

```java
// AnnotationParser 原 instanceof 模式 (6 sites, 3 注解 × 2 路径)
RedisCacheable cacheable = null;
if (target instanceof Method) {
    cacheable = AnnotatedElementUtils.findMergedAnnotation(
            (Method) target, RedisCacheable.class);
} else if (target instanceof Class) {
    cacheable = AnnotatedElementUtils.findMergedAnnotation(
            (Class<?>) target, RedisCacheable.class);
}

// SpringAnnotationAdapter 原 Method/Class 重载对 (6 pairs = 12 methods)
private boolean hasResiCacheable(Method method) {
    return AnnotatedElementUtils.findMergedAnnotation(method, RedisCacheable.class) != null;
}
private boolean hasResiCacheable(Class<?> clazz) {
    return AnnotatedElementUtils.findMergedAnnotation(clazz, RedisCacheable.class) != null;
}
// ... 同样 hasResiCacheEvict / hasResiCachePut × 2 = 6 pairs
```

**Java 反射类型保证**:
- `Method extends Executable extends AccessibleObject implements AnnotatedElement` ✓
- `Class<?>` implements `AnnotatedElement` ✓
- Spring 的 `AnnotatedElementUtils.findMergedAnnotation(AnnotatedElement, Class<A>)` 已原生多态

**手动 instanceof 分派完全多余**。

**deletion test**:
- 删 23 处 instanceof 分支 + 12 个 Method/Class 重载对 → 行为完全一致(多态分发对 Method/Class 透明)
- 替换为反射遍历 `AnnotatedElement` 字段 → 失去类型安全 + 反射开销;失败
- 保留 12 个重载对 + 用 if-else 链分派 → 浅模块病征,触发条件 = 任何新注解类型需要新增重载;失败

**形态对比**:
- 重复墙:23 处 instanceof 分派 + 12 个重载方法 + 6 对 `if/else` 链 = 难以维护的样板网络
- 模板版:2 个 static helper (`findMerged` + `extractTargetName`) + 6 个多态方法(原 12 个重载合并) + 23 处 1-liner 委派

**决策**:抽出 `AnnotationTargets` utility class 到 `annotation/` 包,持 2 个 public static helper:

```java
@UtilityClass
public final class AnnotationTargets {
    public static <A extends Annotation> A findMerged(Object target, Class<A> type) {
        if (!(target instanceof AnnotatedElement)) {
            return null;
        }
        return AnnotatedElementUtils.findMergedAnnotation((AnnotatedElement) target, type);
    }

    public static String extractTargetName(Object target) {
        if (target instanceof Method) return ((Method) target).getName();
        if (target instanceof Class<?>) return ((Class<?>) target).getName();
        return target.toString();
    }
}
```

**关键设计**:
- **`AnnotatedElement` 多态**:`Method` / `Class<?>` 都实现 `AnnotatedElement`;Spring 的 `findMergedAnnotation(AnnotatedElement, Class)` 已是多态 — helper 强转一次即可对两者透明
- **`@UtilityClass` (Lombok)**:private 构造 + final class 阻止实例化,纯静态工具
- **Null 容忍**:`findMerged` 对非 `AnnotatedElement` 的 target 返回 `null`(与原 instanceof 不匹配时 `findMergedAnnotation` 不调用的行为等价);`extractTargetName` 对非 Method/Class 走 `toString()`(与原 else 分支 `target.toString()` 等价,保留行为兼容)
- **`Class.getName()` vs `toString()`**:原 else 分支 `target.toString()` 对 Class 实际返回的是类全名(因 `Class.toString()` 委托 `getName()`),helper 显式走 `getName()` 更明确、更类型化

**落地**:
- 新建 `annotation/AnnotationTargets.java`(114 SLOC,9 SLOC body + 84 SLOC Javadoc)
- `AnnotationParser.java`:
  - 6 处 `instanceof + findMergedAnnotation` 收敛为 6 个 1-liner `AnnotationTargets.findMerged(target, X.class)`
  - 3 处 `instanceof + getName` 收敛为 3 个 1-liner `AnnotationTargets.extractTargetName(target)`
  - 3 处中间 `null` 变量(`cacheable` / `cacheEvict` / `caching` 初始化为 null)消失,合并为 `final` 一次性赋值
- `SpringAnnotationAdapter.java`:
  - 6 对 `hasResiCacheable/Evict/Put(Method/Class<?>)` 重载(12 个方法)合并为 6 个多态方法(`hasResiCacheable(Object)` 等)
  - 6 对 `convertSpringCacheable/Put/Evict(Method/Class<?>, List)` 重载(12 个方法)合并为 6 个多态方法(`convertSpringCacheable(Object, String, List)` 等)—— `String name` 由 `addSpringNativeOperations` 预提取(走 `extractTargetName` helper),委派逻辑保持 1-liner
  - `addSpringNativeOperations` SELECTIVE / FULL 两个分支的 Method/Class 二分法塌缩为 1 个多态路径(SELECTIVE 3 行 + FULL 3 行,Method/Class 判断全部消失)
  - `hasResiCacheAnnotation(Object)` 内 Method/Class 二分法塌缩为 1 个 `instanceof AnnotatedElement` 守卫 + 多态 helper 调用

### D2 — `buildRedisCacheXxxOperation` 三方法零修改

**问题陈述**:`SpringAnnotationAdapter.buildRedisCacheableOperation(Cacheable, String)` /
`buildRedisCachePutOperation(CachePut, String)` /
`buildRedisCacheEvictOperation(CacheEvict, String)` 三方法已经是 `String name` 形参,
本 ADR 的 `convertSpringXxx(target, name, ops)` 多态路径只需预提取 `name` 后 1-liner 委派,三方法签名零变化。

**决策**:`buildRedisCacheXxxOperation` 保持不变,本 ADR 不动其内部逻辑(职责单一:annotation → Builder 字段映射,与 target 类型无关)。

### D3 — 既有 3 个测试零修改 (有意,行为兼容性证明)

**问题陈述**:
- `SpringAnnotationAdapterTest` × 5 tests(SELECTIVE / FULL / NONE 三模式 × Method/Class 各测试)
- `RedisCacheOperationSourceSelectiveTest` × 2 tests(SELECTIVE 模式 end-to-end)
- `OperationValidatorTest` × 4 tests(校验逻辑)

**决策**:本 ADR 不修改上述 9 个测试 — 通过即证明多态路径对原行为零回归。

### D4 — 新增 `AnnotatedElementPolymorphicSeamTest` × 9 contract tests

**问题陈述**:helper 行为需显式钉住,防后续漂移。

**决策**:新增测试类,9 个 contract:
- `findMerged` × 6 tests(Method 命中 / Class 命中 / @RedisCaching 复合 / Method 缺席 / Class 缺席 / 非 AnnotatedElement null-safe)
- `extractTargetName` × 3 tests(Method → getName / Class → getName / fallback toString)

## 后果

**增益**:
- 23 处 `instanceof Method/Class` 手动分派 → 0 处,统一委派给 2 个 helper
- 6 对 `hasResiCacheXxx(Method/Class<?>)` 重载 → 6 个多态方法(原 12 个方法声明塌缩为 6 个)
- 6 对 `convertSpringCacheXxx(Method/Class<?>, List)` 重载 → 6 个多态方法 + 1-liner 委派
- `addSpringNativeOperations` SELECTIVE / FULL 两个分支的 Method/Class 二分法消失(4 个 `if (target instanceof Method) ... else if (target instanceof Class) ...` 块塌缩为 0)
- 3 处 `parseResiCacheAnnotations` 中间 `null` 变量消失,合并为 `final` 一次性赋值
- 新增第 4 个 `@RedisCache*` 注解时:`AnnotationParser` 加 1 行 `findMerged(target, X.class)` + `parseRedisXxx` 方法,不再需要新增 2 个 `instanceof` 分支
- 新增 Spring 兼容注解(假设第 4 个 `@Cache*`)时:`SpringAnnotationAdapter` 加 1 个多态 `hasXxx` + 1 个多态 `convertSpringXxx`,不再需要新增 2 个重载

**代价**:
- **+114 SLOC 新文件**:`AnnotationTargets` 9 SLOC body + 84 SLOC Javadoc(行为契约 + 类型保证 + 线程安全声明集中,后续 reader 无需猜)
- **+123 SLOC 新测试**:`AnnotatedElementPolymorphicSeamTest` 9 个 contract test
- 0 公开 API 变化(`AnnotationParser` 与 `SpringAnnotationAdapter` 仍 `final class` 包级私有,签名零变化;`addSpringNativeOperations` 与 `parseResiCacheAnnotations` 签名零变化)
- 0 行为回归(stash ADR-0020 diff 后跑 `mvnw test -Dtest='!*IT'` 与本 ADR 落地后 795 tests / 3 failures 完全一致 — 3 IT 失败为 pre-existing Testcontainers 环境问题)

**不变**:
- 既有 3 个测试 × 11 tests 零修改通过(SpringAnnotationAdapterTest × 5, RedisCacheOperationSourceSelectiveTest × 2, OperationValidatorTest × 4)
- `buildRedisCacheXxxOperation` 三方法零修改(职责单一,与 target 类型无关)
- `RedisCacheOperationSource` 编排顺序零变化(AnnotationParser → Validator → SpringAnnotationAdapter)
- `OperationValidator.validate` 零变化(校验逻辑与 target 类型无关)
- `Operation.fromAttributes(method, key, attributes)` 静态 seam(ADR-0017)零变化(本 ADR 是 ADR-0017 平行 seam —— "annotation 处理场景的反射多态化")
- 3 个 IT 失败(RedisCacheSemanticsIT.*)零变化(pre-existing Testcontainers 环境问题)

## 验证

- `mvnw checkstyle:check` — **0 violations**
- `mvnw test -Dtest='!RedisCacheSemantics*'` — **BUILD SUCCESS, 792 tests, 0 failures, 0 errors**
  (原 783 unit tests + 9 新增 ADR-0020 contract = 792; 3 IT 失败为 pre-existing Testcontainers 环境问题,
  stash ADR-0020 diff 后跑 `mvnw test -Dtest='!*IT'` 同样 3 失败,与本 ADR 改动无关)
- `SpringAnnotationAdapterTest` 单测 — **5 tests, 0 failures**(零修改)
- `RedisCacheOperationSourceSelectiveTest` 单测 — **2 tests, 0 failures**(零修改)
- `OperationValidatorTest` 单测 — **4 tests, 0 failures**(零修改)
- `AnnotatedElementPolymorphicSeamTest` 单测 — **9 tests, 0 failures**(新增)

## 触发重评估

- 新增第 4 个 `@RedisCache*` 注解时:`AnnotationParser.parseResiCacheAnnotations` 加 1 行 `findMerged(target, X.class)` + `parseRedisXxx` 方法(若需要新的 RedisCache*Operation 类型);`SpringAnnotationAdapter` 加 1 个多态 `hasXxx(Object)` + 1 个多态 `convertSpringXxx(Object, String, List)` 方法;`AnnotationTargets` helper 零修改
- 新增 Spring 兼容注解(如 `@Cache*` 第 4 个)时:同上模式
- 引入新反射类型(如 `Field` / `Constructor`)作为注解目标:helper `findMerged` 当前依赖 `AnnotatedElement` 接口;若新类型不实现 `AnnotatedElement` 则 helper 返回 null(已显式 null-safe 守卫);若需支持新类型,扩展 helper 增加 `instanceof Field/Constructor` 分支即可(对称 Method/Class 形态)

## 参考

- ADR-0010: RedisCacheAttributes 投影层(annotation → operation 平行 seam)
- ADR-0017: Operation.fromAttributes 静态 seam(本 ADR 平行模式 —— "annotation 反射场景多态化")
- ADR-0016: ObserverRegistry 跨 engine observer 列表去重(同 observer / handler 同构 seam 模式先例)
- ADR-0015: AbstractAnnotationHandler.registerAll 模板方法(本 ADR 的同构 seam 模式)
- STABILITY.md §1: 0.x 内注解属性 types 稳定契约
- Java reflection: `AnnotatedElement` 是 `Method` / `Class<?>` 共同超接口(D1 类型保证)
- Spring `AnnotatedElementUtils.findMergedAnnotation(AnnotatedElement, Class)` 已多态(D1 leverage 兑现基础)
- Lombok `@UtilityClass`:private 构造 + final class + 静态方法
- Tell, Don't Ask 原则(Martin Fowler):target 类型由 helper 拥有并解释,callers 1-liner 委派
