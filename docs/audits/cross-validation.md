# 三方体检报告交叉验证 · 真实 vs 误判裁决

> **审查对象**：`deepseek-unused-modules-report.md` / `minimax-unused-modules-report.md` / `glm-unused-modules-report.md`
> **裁决依据**：全部结论用 `grep` 词边界引用 + Spring 装配链 + 源码精读 **重新独立验证**，不采信任何单方报告文字。
> **裁决日期**：2026-06-21
> **标记约定**：✅ 真实（代码证实）｜❌ 误判（代码证伪）｜⚠️ 部分正确／需限定

---

## 0. 可信度总览

| 报告 | 死代码覆盖 | 独家准确发现 | 误判数 | 行数准确度 | 综合评价 |
|------|-----------|-------------|--------|-----------|----------|
| **minimax** | 最广（方法/字段/配置级） | 最多（LockPoolStats、双重 LockManager、CacheOutput 死字段、死配置属性、package-info 过时、wrapper 仅测试） | **2**（加载链、双重注册） | 中（LockPoolStats 90 实际 110） | **深度第一**，广度最佳，但 Spring 装配机制两处理解错误 |
| **glm**（本机） | 中（类级为主，装配链核实严） | Bloom 三实现误判排除（独家正确）、VersionEnvelope 安全反模式 | **3**（CacheMetricsRecorder 误判有效、LockPoolStats 漏报、双重 LockManager 漏报） | 高（多数准确，CacheEvictedEvent 51≈实际 54） | **装配链最严谨**，但死代码覆盖有遗漏 + 1 处定性错误 |
| **deepseek** | 广（类级） | SPI/wrapper/事件主线正确、CacheMetricsRecorder 正确 | **2**（EvictionStrategy 当死代码删、行数严重失准） | **低**（SpelConditionEvaluator 报 70，实际 **269**） | 大方向对，但把"过度设计"误当"死代码"，行数不可信 |

**一句话**：minimax 找得最全但有 2 处 Spring 机制误判；glm 装配判断最准但漏报 + 1 处定性错；deepseek 大方向对但行数失真且混淆了"死代码"与"可简化"。

---

## 1. 三方共识 — 全部 ✅ 真实（高置信，代码证实）

| # | 项目 | 三方一致结论 | 代码证据 | 裁决 |
|---|------|-------------|----------|------|
| 1 | `evaluator/SpelConditionEvaluator` | 死代码 | `getInstance()` 全仓零调用；`RedisCacheInterceptor` 注释明示 condition/unless 由 Spring 原生处理 | ✅ |
| 2 | `event/CacheEvictedEvent`（+EvictionReason） | 死代码 | 全仓 `publishEvent`/`@EventListener`/`ApplicationEventPublisher` 零匹配 | ✅ |
| 3 | `wrapper/CircuitBreakerCacheWrapper` + `RateLimiterCacheWrapper` | 死代码 | `RedisProCacheManager` 直接 `new RedisProCache(...)`，从不包装；非 `@Component` | ✅ |
| 4 | `spi/LockProvider` + `RedissonLockProvider` | 死代码 + 名实不符 | `RedissonLockProvider` 未 `implements LockProvider`；`getLockManager()` 无调用者；`ServiceLoader` 全仓零用 | ✅ |
| 5 | `spi/BloomFilter` + `BloomFilterProvider` + `RedisBloomFilterProvider` | 死代码（SPI 自闭环） | `ServiceLoader` 零用；services 文件空；`create()` 返回值无消费者 | ✅ |
| 6 | `RedissonLockProvider` 名实不符 | 自称 SPI 实现却未 implements | `public class RedissonLockProvider`（无 implements） | ✅ |
| 7 | SPI 体系整体空壳 | ServiceLoader 从未调用 | `grep ServiceLoader src/main` = 0 行；两 services 文件仅注释 | ✅ |
| 8 | Bloom 三实现有效（勿删） | glm 独家澄清，另两方未误删 | `HierarchicalBloomIFilter` `@Primary` + `@Qualifier("localBloomFilter")`/`("redisBloomFilter")` 与 bean 名精确匹配 | ✅（glm 独家正确） |

---

## 2. minimax 独家发现 — 多数 ✅ 真实（深度优势）

| # | minimax 主张 | 代码证据 | 裁决 |
|---|-------------|----------|------|
| M1 | `eviction/LockPoolStats`（110 行）死代码 | 生产零引用，**仅 `LockPoolStatsTest` 构造** | ✅ 真实（**glm/deepseek 漏报**） |
| M2 | 双重 `LockManager` 接口（`spi/` vs `protection/breakdown/`） | 两接口 API 几乎相同；`DistributedLockManager implements` breakdown 版；`spi.LockManager` **零实现** | ✅ 真实（**glm/deepseek 漏报**） |
| M3 | `spi/LockHandle` 死接口 | 仅 `spi.LockManager` 引用 + 自身定义；生产用 `breakdown.LockManager$LockHandle` 嵌套版 | ✅ 真实（**glm P0 清单遗漏**） |
| M4 | `CacheOutput.setLockContext/getLockContext` 死 | grep 零调用（仅定义于 `CacheOutput:91-92`） | ✅ 真实 |
| M5 | `CacheOutput.earlyExpirationDecision` 字段死 | `set/getEarlyExpirationDecision` grep 零调用（仅定义）；`EarlyExpirationDecision` record 本身被 handler 本地用（非死） | ✅ 真实（字段死，record 活） |
| M6 | `CacheOutput.clearSkipRemaining` 死方法 | grep 零调用（`CacheOutput:105`） | ✅ 真实 |
| M7 | `CacheOutput.getFinalResult` 死 getter | `setFinalResult` 被 `ActualCacheHandler:72` 写，但 `getFinalResult` **仅测试读**（`ActualCacheHandlerTest:388`），生产无读 | ✅ 真实（写活读死） |
| M8 | 死配置属性 `BloomFilterProperties`/`EarlyExpirationProperties`/`HandlerConfig` 整 nested 类 | `getBloomFilter()`/`getEarlyExpiration()`/`getHandlerSettings()` grep **零生产读取**；实际配置走 `BloomFilterConfig`(@Value) + `ThreadPoolEarlyExpirationExecutor`(硬编码) | ✅ 真实（**glm/deepseek 漏报**） |
| M9 | `failOnSpelError` 死字段 | 唯一潜在读者 `SpelConditionEvaluator:75` 已死 → 字段死 | ✅ 真实 |
| M10 | `CacheConfig.enableBloomFilter`/`enableEarlyExpiration` 死字段 | grep `isEnableBloomFilter`/`isEnableEarlyExpiration` 仅命中 `CacheOperation`（非 properties）的方法，properties 版字段无人读 | ✅ 真实 |
| M11 | `EarlyExpirationHandler.getDecision`（public static, line209）死 | grep 仅定义，无生产调用（仅潜在测试） | ✅ 真实 |
| M12 | `EarlyExpirationSupport.getThreadPoolStats`（line49）死 | grep 零调用 | ✅ 真实 |
| M13 | `chain/package-info.java` 过时 | 列出链顺序 BloomFilter→SyncLock→Ttl→NullValue→Actual，**缺 EarlyExpirationHandler(250)** | ✅ 真实 |
| M14 | wrapper "仅测试用" | `CircuitBreakerCacheWrapperTest` + `RateLimiterCacheWrapperTest` 存在（生产死、测试覆盖） | ✅ 真实（比 glm/deepseek 的"仅自身文件"更精确） |

> minimax 的方法/字段/配置级深度是三方之最，14 项独家发现 **全部成立**。

---

## 3. 关键误判澄清

### ❌ 误判 1（minimax）：`CachingEnablementValidation` / `MetricsAutoConfiguration` "永远不会被加载"

- **minimax 原话**：「整个类在生产环境永远不会被加载」「完全未装配」。
- **代码事实**：`@AutoConfiguration` 元注解了 `@Configuration(proxyBeanMethods=false)`，而 `@Configuration` 是 `@Component` 的派生 → `RedisProCacheConfiguration` 的 `@ComponentScan("io.github.davidhlp.spring.cache.redis")` **会扫描并拾取**这两个类。
- **裁决**：它们 **会加载**（靠 ComponentScan 副作用）。minimax 误判。**glm/deepseek 的"靠副作用加载、脆弱"判断正确**——风险真实（调整扫描范围即静默失效），但"永不加载"是错的。

### ❌ 误判 2（minimax）：`CacheMetricsRecorder` / `RedisCacheHealthIndicator` 双重注册会报错

- **minimax 原话**：「会有两个同类型 bean，依赖注入可能报错」。
- **代码事实**：`MetricsAutoConfiguration` 的 `@Bean cacheMetricsRecorder(...)` 带 `@ConditionalOnMissingBean`。`CacheMetricsRecorder` 类标 `@Component`（先由扫描注册）→ `@ConditionalOnMissingBean` 检测到已存在 → `@Bean` **不重复创建**。无冲突、不报错。
- **裁决**：误判。**真实问题不是"双重注册冲突"，而是"CacheMetricsRecorder 整套方法无人调用"**（见误判 3 的反面——这是 deepseek/minimax 正确的部分）。

### ❌ 误判 3（deepseek）：`EvictionStrategy` + `Factory` + `TwoListEvictionStrategy` 当"死代码"删除

- **deepseek 原话**：列入"立即可删除的 11 文件 742 行"。
- **代码事实**：`RedisCacheRegister:26` 运行时调用 `EvictionStrategyFactory.createTwoList(...)`，字段 `operationStrategy: EvictionStrategy<...>` 在用。**这是活的运行时路径，不是死代码。**
- **裁决**：deepseek 把"过度设计（可简化）"误当"死代码（可删除）"。正确表述（minimax/glm）：`EvictionStrategyFactory` 虽 `@Deprecated`（项目自标），但在用，应**简化**（让 `RedisCacheRegister` 直接持有 `TwoListEvictionStrategy`/`TwoListLRU`），而非直接删。**直接删会破坏 `RedisCacheRegister`。**

### ❌ 误判 4（deepseek）：行数严重失准

- SpelConditionEvaluator：deepseek 报 **70 行**，实际 `wc -l` = **269 行**（差 4 倍，疑似只读了方法体未读全文）。
- CacheEvictedEvent：deepseek 30 / minimax 65 / glm 51，实际 **54**（三方都不准，glm 最近）。
- LockPoolStats：deepseek 110（对）/ minimax 90（错），实际 **110**。
- **裁决**：deepseek 的行数不可信；以 `wc -l` 实测为准。

### ⚠️ 误判 5（glm，即本机上一轮报告）：`CacheMetricsRecorder` 标"有效"

- **glm 原话**：附录标记 `CacheMetricsRecorder ← @Bean 产出（有效）`。
- **代码事实**：`RedisProCache` 直接用 `MeterRegistry` 注册 `Timer`/`Counter`（`RedisProCache:63-75` registerTimer/registerCounter，`122-140` safeIncrement(hitCounter/missCounter)）；`CacheMetricsRecorder.recordHit/recordMiss/...` **全仓零生产调用**（只在自己内部 `getHitCounter` 自引用）。
- **裁决**：**glm 误判**。CacheMetricsRecorder 是"僵尸 bean"——装配了但所有 public 方法死；与 RedisProCache 内置指标构成**双轨制**，业务走 RedisProCache 内置那套。**deepseek/minimax 正确。**

### ⚠️ glm 漏报（本机上一轮报告未覆盖）

- `LockPoolStats`（110 行，仅测试）— minimax/deepseek 均报，glm 漏。
- 双重 `LockManager` 接口 + `spi/LockHandle` — minimax/deepseek 均报，glm 仅在引用表见同名但未正文指出、未入 P0 清单。
- `CacheOutput` 死字段（lockContext/earlyExpirationDecision/clearSkipRemaining/getFinalResult）— minimax 独家，glm 漏。
- 死配置属性（BloomFilterProperties/EarlyExpirationProperties/HandlerConfig/failOnSpelError/enableBloomFilter/enableEarlyExpiration）— minimax 独家，glm 漏。

---

## 4. glm 独家正确发现（另两方未提或未深入）

| # | glm 主张 | 代码证据 | 裁决 |
|---|---------|----------|------|
| G1 | Bloom 三实现装配正确（排除误删） | `@Primary` + `@Qualifier` bean 名精确匹配 | ✅ 独家正确（另两方未误删但也未显式澄清） |
| G2 | `VersionEnvelope.payload` 用 `@JsonTypeInfo(Id.CLASS)` 安全反模式 | `VersionEnvelope.java:30` 确有该注解，与 SecureJackson 白名单目标冲突 | ✅ 真实（minimax 提到 version 字段 YAGNI，但未提 Id.CLASS 安全风险） |
| G3 | 全包 `@ComponentScan` 是孤儿 bean 潜伏根因 | 机制性根因分析 | ✅ 真实 |

---

## 5. 权威合并清单（三方去重去误后，经代码复核）

### P0 — 确证死代码（直接删，零行为变化）

| 文件 | 行数 | 来源 | 复核 |
|------|------|------|------|
| `evaluator/SpelConditionEvaluator.java` | 269 | 三方共识 | ✅ |
| `event/CacheEvictedEvent.java` | 54 | 三方共识 | ✅ |
| `wrapper/CircuitBreakerCacheWrapper.java` (+Test) | 185 | 三方共识 | ✅（同步删测试） |
| `wrapper/RateLimiterCacheWrapper.java` (+Test) | 162 | 三方共识 | ✅（同步删测试） |
| `eviction/LockPoolStats.java` (+Test) | 110 | minimax/deepseek | ✅（glm 漏，已补） |
| `spi/LockProvider.java` | 30 | 三方共识 | ✅ |
| `spi/RedissonLockProvider.java` | 34 | 三方共识 | ✅ |
| `spi/BloomFilter.java` | ~19 | 三方共识 | ✅ |
| `spi/BloomFilterProvider.java` | 33 | 三方共识 | ✅ |
| `spi/LockManager.java` | 27 | minimax/deepseek | ✅（glm 漏，已补） |
| `spi/LockHandle.java` | 15 | minimax/deepseek | ✅（glm 漏，已补） |
| `protection/bloom/RedisBloomFilterProvider.java` | 45 | 三方共识 | ✅ |
| `observability/CacheMetricsRecorder.java` | ~80 | deepseek/minimax | ✅（glm 误判，已纠正） |
| **合计** | **≈ 1060** | | |

> 比 glm 原报告（≈830 行）多出约 230 行：补入了 `LockPoolStats`(110)、`spi/LockManager`+`LockHandle`(42)、`CacheMetricsRecorder`(~80)。删除 `CacheMetricsRecorder` 前需确认 `MetricsAutoConfiguration` 的 `@Bean` 方法一并清理（否则引用悬空）。

### P0+ — 死方法/死字段（minimax 独家，均已复核 ✅）

- `CacheOutput`：`lockContext` 字段 + getter/setter、`earlyExpirationDecision` 字段 + getter/setter、`clearSkipRemaining()`、`getFinalResult()`（保留 `setFinalResult`）
- `EarlyExpirationHandler.getDecision`（public static, line 209）
- `EarlyExpirationSupport.getThreadPoolStats`（line 49）
- `RedisProCache.getHitCount/getMissCount/getHitRate`（line 265/269/281，仅自引用；评估是否作 actuator 保留）

### P0+ — 死配置属性（minimax 独家，均已复核 ✅）

- `RedisProCacheProperties.failOnSpelError`（字段）
- `RedisProCacheProperties.BloomFilterProperties`（整 nested 类）
- `RedisProCacheProperties.EarlyExpirationProperties`（整 nested 类）
- `RedisProCacheProperties.HandlerConfig`（整 nested 类）+ `handlerSettings` 字段
- `RedisProCacheProperties.CacheConfig.enableBloomFilter` / `enableEarlyExpiration`（字段）
- 注：`getNativeAnnotationMode`/`getDisabledHandlers` **在用**（RedisProxyCachingConfiguration:47 / CacheHandlerChainFactory:60），勿删。

### P1 — 简化（非死代码，需重构 + 测试）

- `EvictionStrategyFactory`（`@Deprecated`）+ `EvictionStrategy` 接口 + `TwoListEvictionStrategy` 包装层 → 让 `RedisCacheRegister` 直接持 `TwoListLRU`（**简化非删除**，deepseek 的"直接删"是误判）
- 加载链：`MetricsAutoConfiguration`/`CachingEnablementValidation` 显式注册（glm/deepseek 主张；minimax 的"永不加载"误判）
- `VersionEnvelope` 的 `@JsonTypeInfo(Id.CLASS)` 安全审查（glm 独家）
- `BloomFilterConfig` 4 个 `@Value` 并入 `RedisProCacheProperties`
- `chain/package-info.java` 补 `EarlyExpirationHandler`（minimax）

---

## 6. 结论

**三方无一方完全正确**，但互补性极强：

- **采纳 minimax 的死代码清单为骨架**（最全，14 项独家发现全真），**剔除其 2 处 Spring 装配误判**（加载链、双重注册）。
- **采纳 glm 的装配链判断**（加载副作用、Bloom 装配、VersionEnvelope 安全），**修正其 CacheMetricsRecorder 定性 + 补齐 4 处漏报**。
- **采纳 deepseek 的主线方向**（SPI/wrapper/事件），**摒弃其"EvictionStrategy 当死代码删"误判 + 不采信其行数**。

最终权威死代码规模：**≈ 1060 行 + ~15 个死方法/字段 + 1 个死配置类群**，高于任一单方报告。删除前建议 `./mvnw clean verify -B` 建立基线，删后重跑确认绿，并同步删除对应的 `*Test.java`（wrapper/LockPoolStats 等仅测试覆盖项）。
