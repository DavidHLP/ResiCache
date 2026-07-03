---
title: "ADR-0028: OperationFactory seam 收窄(删 supports 死链 + create 窄化)+ SpringAnnotationAdapter applyText 收敛"
type: adr
status: accepted
date: 2026-07-03
deciders: DavidHLP
related:
  - ADR-0010
  - ADR-0017
  - ADR-0019
  - ADR-0021
tags:
  - architecture-deepening
  - dead-code-removal
  - interface-implementation-gap
  - round-20
---

# ADR-0028: OperationFactory seam 收窄 + SpringAnnotationAdapter applyText 收敛

## 状态

- **Status**: Accepted
- **Date**: 2026-07-03
- **Deciders**: DavidHLP
- **Related**: ADR-0010(strategy dispatch 删除,留 supports 残骸)/ ADR-0017(fromAttributes seam)/ ADR-0019/0021(同类样板收敛)

## 背景

架构深化复审(`/tmp/architecture-review-resicache-20260703-111350.html` 候选 1+2)发现两处 interface-implementation gap:

1. **OperationFactory dispatch seam 残骸**:`OperationFactory.supports(Annotation)` 在 `src/main/` **零调用**(ADR-0010 删除 strategy dispatch 后的死残骸);`create` 五参中 `target`/`args` implementation **从未使用**。3 个 concrete factory + SpringCacheableAdapterFactory 各 ~50 行类壳,核心 `create` 是 2 步 1-liner。仅测试断言 `supports`(测一个从未被调用的方法)。Deletion test:删 interface 方法 + 未用参数,复杂度直接消失。

2. **SpringAnnotationAdapter build 样板**:3 个 `buildXxxOperation` 方法各 ~20 行 `if (hasText) set` 样板高度重复(共 17 处),与 ADR-0017/0019/0021 同构但未收敛。

## 决策

### D1 — OperationFactory seam 收窄(候选 1)

- 删除 `OperationFactory.supports(Annotation)` interface 方法(main 零调用的死代码)
- 删除 `AbstractOperationFactory` 抽象基类(仅承载死的 `supports` + `annotationClass`)
- `create` 签名 `(Method, A, Object target, Object[] args, String key)` → `(Method, A, String key)`(implementation 从未使用 target/args,interface 对齐 implementation 真实面)
- 4 个 concrete factory:`extends AbstractOperationFactory` → `implements OperationFactory`;删除 `annotationClass()`;3 个 ResiCache factory 内联 1-liner `materialize`(直接 `fromAttributes`),移除未使用的 `@Slf4j`
- 删除 6 个测试 `supports` 断言块;同步窄化 main(1 处)+ test(~45 处)`create` 调用

### D2 — SpringAnnotationAdapter applyText 收敛(候选 2)

- 新增 `private static applyText(String, Consumer<String>)` helper
- 3 个 build 方法的 17 个 `if (hasText) set` 样板替换为 `applyText(value, builder::setter)`
- 抹平 Builder setter 风格异构:Spring 标准 `setX` 与 Lombok `x` 均兼容 `Consumer<String>`(Lombok 返 Builder 被丢弃),消除"Builder 异构不可收敛"的预设障碍

### D3 — 歧路否决

- ❌ 删除整个 OperationFactory 接口层 → 破坏泛型 `registerOne`/`registerAll` 的 type-erased 桥,4 个 handler 失去共享模板。interface 仍是 real seam(被 4 个 adapter 实现 + 被泛型 registerOne 跨 type 消费)。
- ❌ 保留 supports 作为"未来 dispatch 预留" → YAGNI,死代码持续误导读者;未来真需要 dispatch 再加,删除是可逆性更优方向。

## 验证

- `mvnw test-compile -B -q` —— BUILD SUCCESS(checkstyle + main/test 编译通过)
- `mvnw test -Dtest='CacheableOperationFactoryTest,CachePutOperationFactoryTest,EvictOperationFactoryTest,AbstractAnnotationHandlerTest,CacheableAnnotationHandlerTest,EvictAnnotationHandlerTest,CachingAnnotationHandlerTest,SpringAnnotationAdapterTest,OperationFromAttributesTest'` —— **56 tests, 0 failures, 0 errors**(行为不变)

## 后续

- 候选 3(MethodMetadataResolver hypothetical seam)+ 候选 4(BloomHashStrategy)见 ADR-0029
- 候选 5(SyncSupport.LockStack)internal seam,deletion test 中性,接受现状不记 ADR

详见 round 20 `log.md`。
