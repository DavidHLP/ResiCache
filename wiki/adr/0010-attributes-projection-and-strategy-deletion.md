# ADR-0010: Attributes 投影层 + TwoListEvictionStrategy 删除 — 注解 × 工厂 × 操作的字段映射去重与浅模块收敛

- **Status**: Accepted
- **Date**: 2026-06-30
- **Deciders**: DavidHLP
- **Related**: ADR-0004 / ADR-0005 / ADR-0009 /
  `wiki/architecture/chain-of-responsibility.md` /
  `wiki/modules/handlers.md` /
  `/tmp/architecture-review-1782816491.html`
- **Supersedes**: (局部覆盖) `@RedisCacheable.expectedInsertions / falseProbability` 与
  `@RedisCachePut / @RedisCacheEvict.syncTimeout` 的默认字面值

---

## 背景

`/tmp/architecture-review-1782816491.html`（v2 修订版）针对 `chain · handler · factory ·
annotation · serialization · eviction · chain/model` 七个领域做了"删除测试 + 接口/实现
大小比 + 字段语义对比"筛子，挑出 3 个 deepening 候选：

| 候选 | 评级 | 描述 |
| --- | --- | --- |
| **A · RedisCacheAttributes 投影层** | Strong | 3 注解 × 3 factory = 9 处漂移复制；引入纯 POJO 收敛 |
| **B · TwoListEvictionStrategy pass-through 删除** | Worth exploring | 105 SLOC 仅做 1:1 委托给 `TwoListLRU`，`EvictionStats.getStats()` 9 行聚合可下沉 |
| **C · Spring `@Cacheable` 适配器** | Worth exploring | handler 内联 47 行 if-Builder 模板；做 A 之后天然被吸收 |

本 ADR 一并落地 A + B + C，并显式封口 ADR-0005 / ADR-0009 / ADR-0002 三条已建 seam。

---

## 决策

### Decision 1 — 引入 `RedisCacheAttributes` 投影层（Candidate A）

在三个注解 (`@RedisCacheable / @RedisCachePut / @RedisCacheEvict`) 与三个 factory
(`CacheableOperationFactory / CachePutOperationFactory / EvictOperationFactory`) 之间
引入：

- `factory/RedisCacheAttributes.java` — Lombok `@Value @Builder` POJO，19 字段（包括
  Evict 独有的 `allEntries / beforeInvocation`）。
- `factory/RedisCacheAttributesProjector.java` — 三个 `from(annotation)` 方法，共享 `from`
  入口；做"注解 → attributes"的纯映射。`cacheNames / value` 合并规则下沉到
  `resolveCacheNames(String[], String[])` 静态方法。

三个具体 factory 改为消费 attributes：

```java
@Override public O create(Method m, A annotation, Object target, Object[] args, String key) {
    return materialize(m, key, projector.from(annotation));   // template 形式
}
// 子类只剩一个 materialize(...) 做 builder 字段填充，不再接触注解。
```

`@interface` 默认值层面修复 3 处已知 drift（详见 Decision 2）——projection 层不再做
sentinel 归一化。

`AbstractOperationFactory` 原承载的 `resolveCacheNames` helper 同步下沉到投影器。

### Decision 2 — 修复 3 处默认值漂移（drift fix in @interface）

| 字段 | 旧值 | 新值 | 用户语义变化 |
| --- | --- | --- | --- |
| `@RedisCacheable.expectedInsertions()` | `10000` | **`100000`** | Bloom 容量扩大到与 Put/Evict 对齐 |
| `@RedisCacheable.falseProbability()` | `0.03` | **`0.01`** | Bloom 误判率收紧到与 Put/Evict 对齐 |
| `@RedisCachePut.syncTimeout()` | `-1`（无限等待） | **`10`** | 防御性收紧——最长等 10 秒 |
| `@RedisCacheEvict.syncTimeout()` | `-1`（无限等待） | **`10`** | 同上 |

显式覆盖（如 `@RedisCachePut(syncTimeout = 60)`）不被修改。

### Decision 3 — 删除 `TwoListEvictionStrategy`（Candidate B）

直接绑 `TwoListLRU`，封装 9 行聚合到 `EvictionStats.of(TwoListLRU, int, int)`：

```java
public record EvictionStats(int totalEntries, int activeEntries, ...) {
    public static EvictionStats of(TwoListLRU<?, ?> lru, int maxA, int maxI) {
        return new EvictionStats(lru.size(), lru.getActiveSize(), ...);
    }
}
```

`RedisCacheRegister` 持有 `TwoListLRU` + 两个 int 容量字段，提供 `snapshotStats()` 公开
方法（替换原 `operationStrategy.getStats()`）。

`validateConsistency()` 一并删除（原实现只有 INFO 日志，无验证）。`TwoListEvictionStrategy`
整体删除（约 -115 SLOC），`TwoListEvictionStrategyTest` 一并删除。

### Decision 4 — `SpringCacheableAdapterFactory`（Candidate C）

在工厂包新增 4 号 factory：

```java
@Component
class SpringCacheableAdapterFactory
    extends AbstractOperationFactory<Cacheable, RedisCacheableOperation> { ... }
```

- 自己的 `toAttributes(Cacheable)` 把 Spring 注解 9 字段映射为 `RedisCacheAttributes`。
- 自己的 `materialize(m, key, attrs)` 把 attributes 填进 RedisCacheableOperation.Builder；
  只对 `hasText(...)` 的字段赋值（Spring `CacheableOperation.Builder` 对空串敏感，
  `setKeyGenerator` 还会 assert notNull）。
- 不走 `RedisCacheAttributesProjector.from(...)`——Spring 注解本身不持有 3 处 drift 字段。

`CacheableAnnotationHandler.doHandle()` 两个分支现在对称：

```java
if (cacheable != null) { /* @RedisCacheable 路径 */ }
if (springCacheable != null) { /* Spring @Cacheable 路径 — 同样的 registerOne */ }
```

47 行内联 `registerSpringCacheableOperation(...)` 删除，handler 全部走
`AbstractAnnotationHandler.registerOne(...)` 模板。

---

## 后果

**增益**：

- `Cacheable ≡ Put` 18/18 builder 字段逐字复制收敛——三个 factory 各只剩 1 个
  `materialize(m, key, attrs)` 共约 ~20 SLOC 字段填充；总 builder 复制 ≥68 SLOC 消失
  （`42` Cacheable≡Put 硬证据 + `26` Evict 共享列 × 2）。
- 3 处 drift 单一收敛点：`RedisCacheAttributesProjector` 静态工具 + `@interface` 默认值；
  新增字段只动 3 处（POJO + 投影器 + 1 个 Builder），不再 9 处。
- `Spring @Cacheable` 9 字段 Build-if-Text 路径下放到 factory（可单元覆盖每条字段路径），
  handler 不再持有样板。
- `TwoListEvictionStrategy` 删除：算法读懂门槛从 669 行降到 564 行 + 30 行 `EvictionStats.of`。

**代价**：

- `@RedisCacheable.expectedInsertions` 默认从 10000 → 100000：少量用户可能恰好精准依赖
  `10000` 这个数值——属用户契约变更，需 release note。
- `@RedisCachePut / @RedisCacheEvict.syncTimeout` 默认从 `-1` → `10`：依赖"无限等待"
  的延迟敏感场景若忘记显式赋值，将得到 10 秒保护——属<strong>防御性收紧</strong>，需
  release note。
- 注解字段签名未变（兼容性保持）。

**重构范围之外**（保持）：

- ADR-0005 kernel hedge（chain handler 与 spring cache 内核解耦）
- ADR-0009 ChainEngine 抽出（chain 推进 + 观测 seam）
- ADR-0002 holder 删除（`DefaultMethodMetadataResolver` 已替代 static ThreadLocal）

---

## 实施

5 文件改动 + 2 文件新建 + 2 文件删除 + 1 测试新增 + 1 ADR。

### 新建

- `factory/RedisCacheAttributes.java`
- `factory/RedisCacheAttributesProjector.java`
- `factory/SpringCacheableAdapterFactory.java`
- `test/.../factory/RedisCacheAttributesProjectorTest.java`

### 修改

- `annotation/RedisCacheable.java` — default `expectedInsertions=100_000`，`falseProbability=0.01`
- `annotation/RedisCachePut.java` — default `syncTimeout=10`
- `annotation/RedisCacheEvict.java` — default `syncTimeout=10`
- `factory/AbstractOperationFactory.java` — 删除 `resolveCacheNames`
- `factory/CacheableOperationFactory.java` — 转为 attributes 消费者
- `factory/CachePutOperationFactory.java` — 转为 attributes 消费者
- `factory/EvictOperationFactory.java` — 转为 attributes 消费者
- `handler/CacheableAnnotationHandler.java` — 双路径对称化（移除内联 47 行）
- `operation/RedisCacheRegister.java` — 直接绑 TwoListLRU，新增 `snapshotStats()`
- `eviction/EvictionStats.java` — 新增 `of(TwoListLRU, int, int)`

### 删除

- `eviction/TwoListEvictionStrategy.java`（-105 SLOC）
- `test/.../eviction/TwoListEvictionStrategyTest.java`

### 验证

- `mvnw checkstyle:check` — PASS
- `mvnw verify` — 689 tests, 0 failures; coverage checks met
