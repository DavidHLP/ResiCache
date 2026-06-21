# ResiCache 代码体检报告

> 审查范围：`src/main/java/io/github/davidhlp/spring/cache/redis/`
> 审查维度：代码调用链、死代码（Dead Code）、过度设计（Over-Engineering）

---

## 总览

| 维度 | 问题数 | 预估脏代码行数 |
|------|--------|--------------|
| 🧟 死代码 | 10 个 | ~900 行 |
| 🏗️ 过度设计 | 12 处 | ~2,200 行 |
| 🔗 调用链问题 | 4 处 | 设计缺陷 |

---

## 🔗 一、代码调用链分析

### 主调用路径（健康）

```
RedisCacheAutoConfiguration (单入口)
  └─ @Import → RedisCacheRegistryConfiguration
             → RedisConnectionConfiguration
             → RedisProCacheConfiguration
             → RedisProxyCachingConfiguration
             → JacksonConfig

RedisCacheInterceptor.invoke()
  └─ CacheHandlerChain.handle()
       ├─ BloomFilterHandler     (order=100, 可关闭)
       ├─ SyncLockHandler        (order=200, 可关闭)
       ├─ EarlyExpirationHandler (order=250, 可关闭)
       ├─ TtlHandler             (order=300, 可关闭)
       ├─ NullValueHandler       (order=400, 可关闭)
       └─ ActualCacheHandler     (order=500, 终结点)
```

### 问题 1.1：缺少 `@EnableCaching` 的校验路径断裂

`CachingEnablementValidation.java` 声明了 `@AutoConfiguration`，意图是检测用户是否忘记加 `@EnableCaching`，但**未注册到 `AutoConfiguration.imports`**，永久不生效。

### 问题 1.2：Metrics 配置路径断裂

`MetricsAutoConfiguration.java` 同样未注册到 `AutoConfiguration.imports`。其中的 `CacheMetricsRecorder` 虽有 `@Component` 但**从未被任何生产代码注入**——`recordHit()`、`recordMiss()` 等方法从未被调用。该 111 行的类是纯摆设。

### 问题 1.3：SPI 加载路径完全断裂

`ServiceLoader` 在 `src/main/` 中**一次都没有被调用**。两个 SPI services 文件全是空壳（只有注释，无实现类名）。`spi/` 包的 6 个文件形成了一个自指图，但没有任何消费点。

### 问题 1.4：`CacheMetricsRecorder` 组件扫描与配置冲突

`CacheMetricsRecorder` 同时有 `@Component`（组件扫描创建）和 `MetricsAutoConfiguration` 的 `@Bean`（配置类创建，但该配置类未注册）。双重注册路径导致不确定行为，且构造函数参数 `Optional<MeterRegistry>` 在组件扫描路径下可能引发启动失败。

---

## 🧟 二、死代码清单

| # | 文件 | 行数 | 死亡原因 |
|---|------|------|----------|
| 1 | `wrapper/CircuitBreakerCacheWrapper.java` | 185 | 完整断路器状态机（CLOSED/OPEN/HALF_OPEN + 滑动窗口），从未被实例化或引用 |
| 2 | `wrapper/RateLimiterCacheWrapper.java` | 162 | 完整令牌桶限流器，自实现而非用 Guava/Bucket4j，从未被引用 |
| 3 | `event/CacheEvictedEvent.java` | 30 | 定义了 `ApplicationEvent` 子类和 `EvictionReason` 枚举，无人 publish，无人 listen |
| 4 | `evaluator/SpelConditionEvaluator.java` | 70 | 完整的 SpEL 解析器 + 单例模式，`getInstance()` 无人调用 |
| 5 | `eviction/LockPoolStats.java` | 110 | Record 含 10 字段 + 5 计算方法 + 自定义 `toString`，无人构造 |
| 6 | `config/CachingEnablementValidation.java` | 45 | `@AutoConfiguration` 未注册到 `AutoConfiguration.imports`，死配置 |
| 7 | `config/MetricsAutoConfiguration.java` | 51 | `@AutoConfiguration` 未注册到 `AutoConfiguration.imports`，死配置 |
| 8 | `spi/RedissonLockProvider.java` | 32 | 有 `@Component` 但无人注入，且它**没有实现** `LockProvider` 接口（仅暴露 `getLockManager()`） |
| 9 | `protection/bloom/RedisBloomFilterProvider.java` | 60 | 实现了 `BloomFilterProvider` 但 SPI 注册文件为空，`ServiceLoader` 未调用 |
| 10 | `spi/` 整个包（6 文件） | 145 | 自指图，无消费端，无 ServiceLoader 调用 |

### 死代码确认证据

| 检查项 | 命令 | 结果 |
|--------|------|------|
| `ServiceLoader` 调用 | `grep -r "ServiceLoader" src/main/java` | **0 行** |
| `CircuitBreakerCacheWrapper` 被引用 | `grep` 搜索 | 仅自身文件 |
| `RateLimiterCacheWrapper` 被引用 | `grep` 搜索 | 仅自身文件 |
| `CacheEvictedEvent` 被引用 | `grep` 搜索 | 仅自身文件 |
| `SpelConditionEvaluator` 被引用 | `grep` 搜索 | 仅自身文件 |
| `LockPoolStats` 被引用 | `grep` 搜索 | 仅自身文件 |
| SPI services 文件内容 | 直接读取 | 均为空（仅注释） |
| `AutoConfiguration.imports` 内容 | 直接读取 | 仅 `RedisCacheAutoConfiguration` 一条 |

---

## 🏗️ 三、过度设计清单

### 3.1 单实现接口（Interface/Impl 分裂）

每个接口有且仅有一个实现类，且无可预见的替代实现需求。

| 接口 | 唯一实现 | 方法数 | 建议 |
|------|---------|--------|------|
| `TtlPolicy` | `DefaultTtlPolicy` | 3 | 内联为静态方法或与 `TtlHandler` 合并 |
| `NullValuePolicy` | `DefaultNullValuePolicy` | 5 | 内联为具体类，移除接口 |
| `BloomHashStrategy` | `MessageDigestBloomHashStrategy` | 1 | 合并为一个具体类 |
| `EarlyExpirationExecutor` | `ThreadPoolEarlyExpirationExecutor` | 5 | 合并且移除接口 |

上述 4 对接口共 ~700 行代码。

### 3.2 纯委托门面（Facade 无增值逻辑）

| 文件 | 行数 | 委托目标 | 问题 |
|------|------|----------|------|
| `EarlyExpirationSupport.java` | 69 | `EarlyExpirationExecutor` | 5 个 public 方法全部直接委托，零增强逻辑。可被注入直接替换 |
| `ChainDecision.java` | ~40 | 三个枚举值 | 完全内嵌于 `HandlerResult` 即可，独立成文件过重 |

### 3.3 工厂过度封装

| 文件 | 行数 | 问题描述 |
|------|------|----------|
| `OperationFactory` 接口 | 36 | 定义 `create()` + `supports()` 泛型接口 |
| `CacheableOperationFactory` | ~58 | 仅调用 `RedisCacheableOperation.builder().field1().field2().build()` |
| `CachePutOperationFactory` | ~58 | 同上模式 |
| `EvictOperationFactory` | ~58 | 同上模式 |
| **小计** | **210** | **4 个文件可完全内联进对应的 *AnnotationHandler** |
| `CacheHandlerChainFactory` | 117 | 用双检锁单例 + `volatile` + `synchronized` 实现一次性排序/过滤逻辑，应改为 `@Bean` 方法（~15 行） |
| `EvictionStrategyFactory` | ~50 | **项目自己标记了 `@Deprecated`**（`EvictionStrategyFactory.java:13`），`StrategyType` 枚举只有一个值 `TWO_LIST` |

### 3.4 三层平行继承体系

```
Annotation:   @RedisCacheable    @RedisCachePut    @RedisCacheEvict    @RedisCaching
                    ↓                  ↓                  ↓                  ↓
Handler:    CacheableAnnotation → CachePutAnnotation → EvictAnnotation → CachingAnnotation
Factory:    CacheableOpFactory → CachePutOpFactory  → EvictOpFactory   (无 Caching 工厂)
Operation:  RedisCacheableOp    RedisCachePutOp     RedisCacheEvictOp
```

共 **12 个文件、~900 行代码**处理同一个问题的三个维度。`CachingAnnotationHandler.java` 的 82 行中核心逻辑仅是按顺序调用三个工厂。移除工厂层可直接省去 4 个文件。

### 3.5 模型层过度设计

`CacheInput` + `CacheOutput` + `CacheContext` 三个对象合计 **~517 行**：

- `CacheContext` 是"上帝对象"，同时持有 Input 和 Output，对两者字段都提供委托访问
- `CacheInput` 有自己的 Builder（`CacheInput.Builder`），`CacheContext` 又有 `CacheContextBuilder`——两套 Builder 做同一件事
- `CacheOutput` 含 28 个 getter/setter，许多字段仅被一个 handler set、另一个 handler get，跨文件隐式耦合
- `attributes` 字段（`ConcurrentHashMap`）作为万能兜底，进一步质疑类型化字段的必要性——如果有 map 兜底，为何还需要 Input/Output 拆分？

`CacheResult`（98 行）+ `HandlerResult`（56 行）：
- `HandlerResult` 本质是 `CacheResult` + `ChainDecision` 的配对
- 可将 `ChainDecision` 直接作为 `CacheResult` 的字段，消除一个类

### 3.6 锁管理接口的平行重复

存在两个 `LockManager` 接口，API 几乎相同：

| 位置 | 文件 | 行数 | 状态 |
|------|------|------|------|
| `spi/LockManager` | `spi/LockManager.java` | 27 | **死**——无人实现，无人引用 |
| `breakdown/LockManager` | `protection/breakdown/LockManager.java` | 36 | **活**——被 `DistributedLockManager` 实现 |

此外还有 `spi/LockProvider`（接口，未在任何 wiring 点使用）和 `spi/LockHandle`（record）。三个 SPI 锁相关文件完全可以删除。

### 3.7 已废弃但未清理

`EvictionStrategyFactory.java:13`：
```java
@Deprecated
```
注解明确说明"since 0.0.2 — This factory is a static utility, not a Spring bean. StrategyType enum has only ONE entry (TWO_LIST); no SPI abstraction needed."

但以下 3 个文件仍保留：

| 文件 | 行数 | 说明 |
|------|------|------|
| `EvictionStrategy` | ~80 | 接口，8 个方法完全镜像 `TwoListLRU` |
| `TwoListEvictionStrategy` | ~120 | 包装类，逐行委托给 `TwoListLRU` |
| `EvictionStrategyFactory` | ~50 | 已标记 `@Deprecated` |

合计 ~250 行。调用方可直接使用 `TwoListLRU`。

---

## 📊 四、改进优先级矩阵

| 优先级 | 操作 | 预估删除行 | 涉及文件数 |
|--------|------|-----------|-----------|
| 🔴 P0 | 移除 `spi/` 整个包（断裂的 SPI 系统） | ~145 | 6 |
| 🔴 P0 | 移除 `wrapper/` 整个包（断路器+限流器） | ~347 | 2 |
| 🔴 P0 | 移除 `EvictionStrategy` + `Factory` + `TwoListEvictionStrategy`（项目自认的过度设计） | ~250 | 3 |
| 🟠 P1 | 合并 `EarlyExpirationSupport` 到 executor（纯委托门面） | -1 文件 | 1 |
| 🟠 P1 | 移除 4 个单实现接口（TtlPolicy, NullValuePolicy, BloomHashStrategy, EarlyExpirationExecutor） | -4 文件 | 8→4 |
| 🟠 P1 | 移除 `OperationFactory` 体系（Handler 直接构造 Operation） | -4 文件 | 4 |
| 🟠 P1 | 合并 `CacheInput/CacheOutput/CacheContext` 为单一对象 | -2 文件 | 5→3 |
| 🟡 P2 | 合并 `CacheResult` + `HandlerResult` | -1 文件 | 2→1 |
| 🟡 P2 | 删除 `CacheEvictedEvent`、`LockPoolStats`、`SpelConditionEvaluator` | 3 文件 | 3 |
| 🟡 P2 | 合并两个 `LockManager` 接口 | -1 文件 | 2→1 |
| 🔵 P3 | 注册或删除 `MetricsAutoConfiguration`、`CachingEnablementValidation` | 2 文件 | 2 |

**总计可精简：约 20–25 个文件、2,500–3,000 行代码**，约占 `src/main/` 总代码量的 25–30%。

---

## ✅ 五、整体评价

### 健康的部分

**核心 Chain of Responsibility 架构是健康的**——6 个 protection handler（BloomFilter → SyncLock → EarlyExpiration → Ttl → NullValue → ActualCache）职责清晰、顺序合理、可独立开关。`@HandlerPriority(HandlerOrder.X)` 的枚举标记方式值得肯定。

`AbstractCacheHandler` 的 Template Method 模式使用得当，`shouldHandle()`/`doHandle()` 分离合适。`RedisProCache`、`RedisProCacheWriter`、`RedisCacheInterceptor` 的 Spring Cache 集成层设计合理。

**Spring Boot AutoConfiguration 的入口设计良好**——`RedisCacheAutoConfiguration` 作为唯一入口，通过 `@Import` 聚合子配置。

### 需要改进的部分

外围包装层和抽象层显著过度：

1. **SPI 系统从未接通**：定义了完整的插件机制（`BloomFilterProvider`、`LockProvider`、`ServiceLoader` 服务文件），但从未调用 `ServiceLoader`、从未在服务文件中注册实现类。6 个文件 145 行纯属脚手架。
2. **Wrapper 模块从未集成**：`CircuitBreakerCacheWrapper`（185 行）和 `RateLimiterCacheWrapper`（162 行）是完整的功能实现，但从未出现在任何 `@Bean` 定义或配置属性中。疑似半成品 feature 或遗留代码。
3. **接口泛滥**：4 对单实现接口（8 个文件），3 层平行继承体系（12 个文件），2 个平行 `LockManager`（2 个文件）。接口本应为扩展性服务，此处却成为文件数量的乘数。
4. **模型层过度拆分**：`CacheInput`/`CacheOutput`/`CacheContext` 三者 517 行的设计，本质是 1 个 Context 对象即可承载的数据流转。
5. **项目自认的过度设计**：`EvictionStrategyFactory` 的 `@Deprecated` 注解说明团队自己意识到了 `EvictionStrategy` + Factory 的问题，但未实际清理。

### 最推荐的一刀切操作

```
删除 spi/    (6 文件, 145 行)
删除 wrapper/ (2 文件, 347 行)
删除 EvictionStrategy + Factory + TwoListEvictionStrategy (3 文件, 250 行)
```

立即消除 **11 个文件、~742 行**从未投入使用（或已废弃）的代码。这不需要重构，只需要删除——收益最高，风险最低。

---

## 附录：验证方法

本报告所有结论通过以下方式交叉验证：

| 验证方式 | 工具 |
|----------|------|
| 符号交叉引用 | `grep -r "ClassName" src/main/java/` 跨文件匹配 |
| SPI 注册检查 | 直接读取 `META-INF/services/` 下文件 |
| AutoConfiguration 注册检查 | 直接读取 `spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| ServiceLoader 调用检查 | `grep -r "ServiceLoader"` |
| `@Deprecated` 注解检查 | `grep -r "@Deprecated"` |
| 配置类状态检查 | `@AutoConfiguration` + `@Import` + `AutoConfiguration.imports` 三重核对 |
