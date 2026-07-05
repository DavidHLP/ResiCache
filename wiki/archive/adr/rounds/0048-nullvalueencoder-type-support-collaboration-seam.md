---
title: "ADR-0048: NullValueEncoder ↔ TypeSupport 协作 contract(为 C6 拆 seam 铺路)"
type: adr
status: accepted
date: 2026-07-05
deciders: DavidHLP
related:
  - 0047-round-34-architecture-deepening
  - 0025-defaultttlpolicyshouldearlyexpiration-policy-split
  - 0005-strategy-replacement-spring-component
tags:
  - architecture-deepening
  - round-35
  - seam-extraction
  - single-responsibility
---

# ADR-0048 — NullValueEncoder ↔ TypeSupport 协作 contract

> 本 ADR 界定 `NullValueEncoder` 与 `TypeSupport` 的协作边界,为 ADR-0047 C6
> (`DefaultNullValuePolicy` 5 方法混合拆 seam) 实施铺路。本 ADR 只定 contract,
> 不实施代码;代码变更在下一个提交。

## 上下文(Context)

`DefaultNullValuePolicy` 当前 5 个方法:
- `shouldCacheNull(RedisCacheableOperation)` — 纯决策(读 operation flag)
- `toStoreValue(value, cacheOperation)` — 纯决策(value + cacheNullValues → null / 原值)
- `fromStoreValue(value)` — 恒等
- `isNullValue(value)` — 恒等(`value == null`)
- **`toReturnValue(value, cacheName, key)`** — TypeSupport 桥接(3 条件 dispatch + 2 条 debug log)

**唯一耦合 TypeSupport 的方法**:`toReturnValue`。其余 4 个都是纯函数/恒等变换。

**问题**:
1. **职责混合**:`DefaultNullValuePolicy` 名义是 "null value policy",实质扮演了两个角色 —
   (a) null 值**决策**(是否缓存、如何转换)和 (b) null 值**字节编码**(null → NullValue.INSTANCE 字节)。
   这两个角色在 ADR-0047 中已被识别为"5 方法混合 + 3 件事 + 3 条 debug 日志分支"。
2. **`NullValue.INSTANCE` 所有权不清晰**:`NullValue.INSTANCE` 是 Spring 提供的标记
   单例(final + 私有构造 + `readResolve`)。`TypeSupport.serializeToBytes` 已识别
   `NullValue` 实例并通过 `SecureNullValueDeserializer` 走安全 Java 序列化 — 这是字节**生产**层。
   `DefaultNullValuePolicy.toReturnValue` 决定**何时**把 `null` 映射为 `NullValue.INSTANCE` —
   这是字节**决策**层。两层耦合在一个类里,ownership 边界模糊。
3. **测试范围不分**:`DefaultNullValuePolicyTest` 既要 mock TypeSupport,又要验证
   决策/转换/binding 三件事混合。

## 决策(Decision)

### 1. 抽 seam:`NullValueEncoder`

新增 `io.github.davidhlp.spring.cache.redis.protection.nullvalue.NullValueEncoder`
作为 Spring `@Component`,单一职责 — **null-aware 字节编码器**(决定 *序列化什么*)。

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class NullValueEncoder {
    private final TypeSupport typeSupport;

    @Nullable
    public byte[] encodeForReturn(@Nullable Object value, String cacheName, String key) {
        if (value == null) {
            log.debug("Encoding null value as NullValue.INSTANCE: cacheName={}, key={}",
                    cacheName, key);
            return typeSupport.serializeToBytes(NullValue.INSTANCE);
        }
        return typeSupport.serializeToBytes(value);
    }
}
```

**契约**:
- 输入:`value` (任意类型,可 null) + `cacheName` + `key` (供 debug 日志用)
- 输出:`byte[]`
  - `value == null` ⇒ `NullValue.INSTANCE` 的 Java 序列化字节(`TypeSupport` 内部识别 + `SecureNullValueDeserializer` 走白名单往返)
  - `value != null` ⇒ `TypeSupport.serializeToBytes(value)`(JSON 或 NullValue)
- 依赖:仅 `TypeSupport` 一个 collaborator
- 不持有状态、无 ThreadLocal

### 2. `DefaultNullValuePolicy` 不再直接依赖 TypeSupport

`DefaultNullValuePolicy` 改持 `NullValueEncoder`(替换 `TypeSupport`):

```java
@Component
@RequiredArgsConstructor
public class DefaultNullValuePolicy implements NullValuePolicy {
    private final NullValueEncoder encoder;

    public boolean shouldCacheNull(...) { ... }   // 纯决策
    public Object toStoreValue(...) { ... }        // 纯转换
    public Object fromStoreValue(...) { ... }      // 恒等
    public boolean isNullValue(...) { ... }        // 恒等
    public byte[] toReturnValue(@Nullable Object value, String cacheName, String key) {
        return encoder.encodeForReturn(value, cacheName, key);   // 委派
    }
}
```

**契约**:
- `NullValuePolicy` 接口保持 5 方法不变 — 二进制/源码兼容
- `DefaultNullValuePolicy` 不再 `import TypeSupport`,全部类型支持职责经 `NullValueEncoder` 转交
- `toReturnValue` 成为 1 行委派 — 删除原 3 条件 dispatch(原代码 `if null → serialize NullValue; if non-null → serialize value` 双 if 是冗余,合并后等价)

### 3. `TypeSupport` 契约保持不变

`TypeSupport.serializeToBytes` 已识别 `NullValue` 实例并走 `SecureNullValueDeserializer`
—— 这是字节**生产**层的契约,**不应被 NullValueEncoder 越权修改或依赖反转**。

依赖方向:**`NullValueEncoder` → `TypeSupport`**(单向,无循环)

`TypeSupport` 不调用 `NullValueEncoder`,不感知上层 null 决策。两条独立流水线各司其职。

### 4. 测试边界

| 测试类 | 改动 |
|---|---|
| `DefaultNullValuePolicyTest` | mock `NullValueEncoder`(替换 `TypeSupport`)。`toReturnValue()` 的 4 个 case 验证委派:1 行调用 `encoder.encodeForReturn(value, cacheName, key)`,不验证字节生产细节。 |
| `NullValueEncoderTest`(新增) | mock `TypeSupport`,覆盖 2 个 case:`null` → `serializeToBytes(NullValue.INSTANCE)`;非 null → `serializeToBytes(value)`。 |

## 影响面 / 测试影响

| 项 | 改动 |
|---|---|
| 源文件 | 3(1 新 `NullValueEncoder` + 1 改 `DefaultNullValuePolicy` + 1 改 `ActualCacheHandler` import) |
| 测试 | 2(1 改 `DefaultNullValuePolicyTest` + 1 新 `NullValueEncoderTest`) |
| 公开 API | `NullValuePolicy` 接口不变;`DefaultNullValuePolicy` 构造器签名变更(`TypeSupport` → `NullValueEncoder`),**破坏性**但属内部 API |
| 净 SLOC | `DefaultNullValuePolicy`:~92 SLOC → ~50 SLOC(瘦身 ~42 SLOC,全部是删除条件分支与 debug log)。`NullValueEncoder`:~25 SLOC 新增。净效果:绝对量微增,但单一职责清晰。 |

## 验证状态

本 ADR 为 C6 实施**前置 contract**,不涉及代码变更;实施提交在下一次 commit。

## 相关 ADR

- **前置**:ADR-0047 C6 决策固化("先写 ADR-0048/0049 界定 `NullValueEncoder` ↔ `TypeSupport` 协作 contract,再实施")。
- **后续**:ADR-0049 C6 实施总结。
- **同模式参考**:ADR-0025(DefaultTtlPolicy.shouldEarlyExpiration 拆分) — 同为 policy ↔ serializer seam 分离模板。
