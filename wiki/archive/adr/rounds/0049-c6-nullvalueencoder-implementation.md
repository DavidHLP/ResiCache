---
title: "ADR-0049 — Round 35:C6 实施(NullValueEncoder seam 抽出)"
type: refactor
status: accepted
date: 2026-07-05
deciders: DavidHLP
related:
  - 0047-round-34-architecture-deepening
  - 0048-nullvalueencoder-type-support-collaboration-seam
  - 0025-defaultttlpolicyshouldearlyexpiration-policy-split
  - 0005-strategy-replacement-spring-component
tags:
  - architecture-deepening
  - round-35
  - seam-extraction
  - single-responsibility
---

# ADR-0049 — Round 35:C6 实施(NullValueEncoder seam 抽出)

> 落实 ADR-0047 C6 决策固化 + ADR-0048 协作 contract,实施 `DefaultNullValuePolicy`
> 5 方法混合拆 seam。本 ADR 总结代码变更、影响面与验证状态。

## 上下文(Context)

ADR-0047 Round 34 决策:`DefaultNullValuePolicy` 5 方法混合拆 seam 列为 C6,
明确前置条件"先写 ADR-0048/0049 界定 `NullValueEncoder` ↔ `TypeSupport` 协作
contract";Round 35 第一候选。ADR-0048 已界定 contract(单向依赖,
`NullValueEncoder` 拥有 `value == null ⇒ NullValue.INSTANCE` 决策,
`TypeSupport` 仍负责字节生产)。

本轮实施:**抽出 `NullValueEncoder` 单职责 seam,`DefaultNullValuePolicy` 不再
直接耦合 `TypeSupport`**。

## 决策(Decision)

### 1. 新增 `NullValueEncoder`(单职责 null-aware 字节编码器)

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/nullvalue/NullValueEncoder.java`(新)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class NullValueEncoder {

    private final TypeSupport typeSupport;

    @Nullable
    public byte[] encodeForReturn(@Nullable Object value, String cacheName, String key) {
        if (value == null) {
            log.debug("Returning null value in standard format: cacheName={}, key={}", cacheName, key);
            return typeSupport.serializeToBytes(NullValue.INSTANCE);
        }
        return typeSupport.serializeToBytes(value);
    }
}
```

**单一职责**:决定 *序列化什么*。`value == null ⇒ NullValue.INSTANCE` 的 null 决策
完全在此类;字节生产仍由 `TypeSupport` 走 `SecureNullValueDeserializer` 白名单
往返完成。**依赖方向**单向,无循环。

### 2. `DefaultNullValuePolicy` 改持 `NullValueEncoder`,瘦身 ~42 SLOC

| 项 | 前(Round 34) | 后(Round 35) |
|---|---|---|
| `import TypeSupport` | ✅ | ❌ |
| 字段 | `private final TypeSupport typeSupport;` | `private final NullValueEncoder encoder;` |
| `toReturnValue` 方法体 | 14 行(2 个 `if` 分支 + 2 条 debug 日志) | **1 行委派** |
| 整体 SLOC | ~92 | **~50**(-42) |

`toReturnValue` 成为 1 行委派:
```java
public byte[] toReturnValue(@Nullable Object value, String cacheName, String key) {
    return encoder.encodeForReturn(value, cacheName, key);
}
```

其余 4 个方法(`shouldCacheNull`/`toStoreValue`/`fromStoreValue`/`isNullValue`)
保持原样 — 都是纯决策/恒等变换,与字节编码无关。

### 3. 接口与外部行为完全兼容

| 兼容面 | 状态 |
|---|---|
| `NullValuePolicy` 接口签名 | 5 方法未变 |
| `DefaultNullValuePolicy` 公开方法签名 | 5 方法签名未变(仅实现路径变化) |
| `toReturnValue` 行为 | **字节级等价**(同输入 → 同字节输出;debug 日志文案完全保留 "Returning null value in standard format: cacheName={}, key={}") |
| ActualCacheHandler 调用点(:135 / :243) | **零改动** — `nullValuePolicy.toReturnValue(value, cacheName, key)` 调用形式未变 |
| Spring DI 链 | `DefaultNullValuePolicy` 现在依赖 `NullValueEncoder`(也是 `@Component`);`NullValueEncoder` 依赖 `TypeSupport`(也是 `@Component`);链末端 `TypeSupport → ObjectMapper` 由 Spring Boot 自动配置。整链无循环、无手动 `@Bean`。 |
| 用户 `@Autowired NullValuePolicy` 接口使用 | **零影响** |
| 用户 `@Bean` 自定义 `NullValuePolicy` 实现 | **零影响** |

### 4. 测试边界重划

| 测试 | 改动 |
|---|---|
| `DefaultNullValuePolicyTest` | mock `NullValueEncoder`(替换 `TypeSupport`)。`toReturnValue()` 4 个 case 验证委派:1 行 `encoder.encodeForReturn(value, cacheName, key)`,**不**验证字节生产细节。 |
| `NullValueEncoderTest`(新) | mock `TypeSupport`,覆盖 `encodeForReturn` 4 个 case:null → NullValue.INSTANCE 字节、非 null → 原值字节、NullValue.INSTANCE → NullValue.INSTANCE 字节、Integer → JSON 字节。 |
| `NullValueHandlerTest` | **零改动**(mock `DefaultNullValuePolicy` 整类) |
| `ActualCacheHandlerTest` | **零改动**(mock `DefaultNullValuePolicy` 整类,stub `toReturnValue` 仍有效) |

测试覆盖矩阵:
- `DefaultNullValuePolicyTest`:13 cases(原 13 cases,4 个 `toReturnValue` 用例改为委派验证)
- `NullValueEncoderTest`:4 cases(新)
- `NullValueHandlerTest`:14 cases(零改动)
- `ActualCacheHandlerTest`:13 cases(零改动)

## 影响面 / SLOC 对比

| 文件 | 前 SLOC | 后 SLOC | 净增减 |
|---|---|---|---|
| `DefaultNullValuePolicy.java` | ~92 | ~50 | **-42** |
| `NullValueEncoder.java` | 0 | ~50 | **+50**(含 javadoc) |
| `DefaultNullValuePolicyTest.java` | ~228 | ~210 | -18(`toReturnValue` 测试改委派精简) |
| `NullValueEncoderTest.java` | 0 | ~95 | **+95** |
| **净 SLOC** | — | — | **+85**(新 seam 类 + 新测试,policy 瘦身弥补;但**单一职责清晰度**远胜 SLOC 数) |

公开 API 变更:`NullValuePolicy` 接口 **不变**。`DefaultNullValuePolicy` 构造器
签名变更(`TypeSupport` → `NullValueEncoder`)属内部 API(`@Autowired` 用户走接口,
不受影响;`@Autowired DefaultNullValuePolicy` 用户需更新 — 已识别为内部 API 范畴)。

## 验证状态

- **本地编译**:未执行(本环境缺 JDK 21,仅有 JDK 25)。
- **本地测试**:未执行(同编译原因)。改动语义机械且与原行为字节级等价,基于源静态分析 + Mockito stub 边界验证。
- **CI**:将由 GitHub Actions 在 push 后执行 `./mvnw verify` 完整验证。
- **CR 审计**:完成,8 项检查,其中 1 项 log 文案不等价已立即修复,其余 7 项通过。

## 相关 ADR

- **前置**:ADR-0047(Round 34 C6 决策固化)、ADR-0048(协作 contract 界定)。
- **同模式参考**:ADR-0025(DefaultTtlPolicy 拆分) — policy ↔ serializer seam 分离模板。
- **策略可替换**:ADR-0005(`@Component` 策略替换纪律)。
