# ResiCache `src/main/` 三维体检报告

> **审查范围**：`src/main/java/io/github/davidhlp/spring/cache/redis/`（全部生产代码）
> **审查维度**：① 代码调用链 ② 死代码（Dead Code）③ 过度设计（冗余封装 / 无用抽象）
> **审查日期**：2026-06-21
> **审查方法**：静态引用分析（grep 词边界引用计数）+ Spring 装配链核实（`@Import` / `@ComponentScan` / `@Bean` / `@Conditional` / SPI `ServiceLoader`）+ 源码精读定性。CodeGraph 索引未初始化，故以 grep 静态分析替代（对 Java 引用反而更全，可覆盖 import / 字段类型 / 注解字符串等所有形式）。

---

## TL;DR

| 维度 | 发现 | 规模 |
|------|------|------|
| **确证死代码**（零运行时路径） | 9 个类 / 3 个孤立包 | **≈ 830 行** |
| **孤儿 bean / 名实不符** | `RedissonLockProvider`（自称 SPI 实现却未 `implements LockProvider`） | 1 处 |
| **过度设计**（可简化非死） | 双重布隆体系、`BloomFilterConfig` 持有类、双套责任链并存、单实现策略接口 | 4 处 |
| **加载链隐患** | `MetricsAutoConfiguration` / `CachingEnablementValidation` 标 `@AutoConfiguration` 却未注册，靠全包 `@ComponentScan` 副作用加载 | 2 处 |
| **安全反模式** | `VersionEnvelope.payload` 用 `@JsonTypeInfo(Id.CLASS)` 全局类型，与安全白名单目标冲突 | 1 处 |
| **嫌疑排除**（曾怀疑后证实有效，勿删） | Bloom `Local/Redis/Hierarchical` 三实现（`@Primary` 装配正确） | — |

**最高优先级动作**：删除 9 个确证死代码类（≈830 行）+ 修正 `RedissonLockProvider` 名实问题，可在不改变任何运行时行为的前提下完成（均已无运行时路径）。

---

## §1 审查方法

1. **类型引用计数**：对每个顶层 public 类型，统计其作为词边界标识符在 *其它* `.java` 文件中出现的文件数。`refs=0` 是死代码强信号（但对 Spring `@Component`/`@Bean` 类需进一步核实装配链）。
2. **装配链核实**：从唯一自动配置入口 `RedisCacheAutoConfiguration`（在 `AutoConfiguration.imports` 注册）出发，沿 `@Import` / `@ComponentScan` / `@Bean` 追踪每个类的激活路径；核实 `ServiceLoader`、`publishEvent`、`@EventListener` 等动态激活点。
3. **源码精读**：对低引用类读取源码，区分"死代码"（无路径）、"过度设计"（有路径但冗余）、"有效"（有路径且必要）。

---

## §2 维度一：代码调用链

### 2.1 有效主链路（启动 → 运行时）

```
[启动装配]
AutoConfiguration.imports
  └─ RedisCacheAutoConfiguration  (@AutoConfiguration, 唯一入口)
       ├─ @Import: JacksonConfig
       │            RedisConnectionConfiguration
       │            RedisCacheRegistryConfiguration
       │            RedisProxyCachingConfiguration  ── 装配 4×AnnotationHandler → new RedisCacheInterceptor
       │            RedisProCacheConfiguration
       │                └─ @ComponentScan("...redis")  ← 扫描整个根包（粗粒度，见 §5）
       │                    ├─ protection/* 全部 @Component handler（Bloom/SyncLock/EarlyExp/Ttl/NullValue）
       │                    ├─ factory/* 3×OperationFactory
       │                    ├─ DefaultNullValuePolicy / DefaultTtlPolicy
       │                    └─ observability/* （孤儿 bean 潜伏的根因，见 §5）
       └─ (副作用加载) MetricsAutoConfiguration / CachingEnablementValidation  ← 加载链隐患，见 §5

[运行时读/写]
Method 调用
  → RedisCacheInterceptor.invoke
      ├─ handlerChain.handle  (AnnotationHandler 手工 setNext 链: cacheable→evict→caching→put)
      │     └─ 各 handler 调用 OperationFactory.create → 注册到 RedisCacheRegister
      └─ super.invoke (Spring CacheAspectSupport)
           → RedisProCache.get/put
                → RedisProCacheWriter
                     → CacheHandlerChain  (@HandlerPriority 动态排序的责任链)
                          BloomFilterHandler(100) → SyncLockHandler(200)
                          → EarlyExpirationHandler(250) → TtlHandler(300)
                          → NullValueHandler(400) → ActualCacheHandler(500)
                          → Redis
```

### 2.2 断裂的调用链（死路径，无入口或无出口）

| 断裂类型 | 位置 | 证据 |
|----------|------|------|
| **无入口** | `evaluator/SpelConditionEvaluator` | `getInstance()` 全仓零调用；`RedisCacheInterceptor` 注释明示 `condition`/`unless` 由 Spring 原生处理 |
| **无出口（无发布者）** | `event/CacheEvictedEvent` | 全仓 `publishEvent` / `@EventListener` / `ApplicationEventPublisher` **零匹配** |
| **无装配点** | `wrapper/CircuitBreakerCacheWrapper`、`RateLimiterCacheWrapper` | `RedisProCacheManager.createRedisCache/getMissingCache` 直接 `new RedisProCache(...)`，从不包装 wrapper；两 wrapper 非 bean |
| **SPI 闭环（无消费者）** | `spi/BloomFilter` + `spi/BloomFilterProvider` + `RedisBloomFilterProvider` | `ServiceLoader` 全仓零使用；`BloomFilterProvider.create()` 返回的 `BloomFilter` 仅在三者内部自闭环 |
| **SPI 断链** | `spi/LockProvider` + `spi/RedissonLockProvider` | `RedissonLockProvider` 未 `implements LockProvider`；`LockProvider` 零实现零消费者；`getLockManager()` 无调用者 |

---

## §3 维度二：死代码清单

### 3.1 P0 — 确证死代码（可直接删除，无运行时影响）

| # | 类 / 文件 | 行数 | 死因（证据） | 删除风险 |
|---|-----------|------|--------------|----------|
| 1 | `evaluator/SpelConditionEvaluator.java` | 269 | 单例 `getInstance()` 全仓零调用。`RedisCacheInterceptor` 类注释明确："condition、unless 等语义由 Spring 原生处理"。整个 `evaluator/` 包仅此一文件。 | 无 |
| 2 | `event/CacheEvictedEvent.java`（含 `EvictionReason` 枚举） | 51 | `extends ApplicationEvent`，但全仓 `publishEvent` / `@EventListener` / `ApplicationEventPublisher` **零匹配**——事件从未发布、无监听者。整个 `event/` 包仅此一文件。 | 无 |
| 3 | `wrapper/CircuitBreakerCacheWrapper.java` | 185 | 断路器装饰器，`extends`/包装 `RedisProCache`，但 `RedisProCacheManager` 直接 `new RedisProCache(...)` 从不包装它；非 `@Component`、无调用者。 | 无 |
| 4 | `wrapper/RateLimiterCacheWrapper.java` | 162 | 限流装饰器，同上。`RateLimiterCacheWrapper:49` 有"防止循环包装"自检，说明设计时预期被装配，但实际零装配点。 | 无 |
| 5 | `spi/LockProvider.java` | 30 | `LockProvider` 接口：**零实现**（`RedissonLockProvider` 未 implements 它）、**零消费者**、`ServiceLoader` 全仓零使用、`META-INF/services/...LockProvider` 为空。 | 无 |
| 6 | `spi/RedissonLockProvider.java` | 34 | `@Component` 孤儿 bean：注入 `DistributedLockManager` 并提供 `getLockManager()` 透传，但**无任何代码注入 `RedissonLockProvider` 或调用 `getLockManager()`**。且 javadoc 自称"分布式锁 SPI 实现"却**未 `implements LockProvider`**——名实不符。 | 无（删除冗余包装，直接用 `DistributedLockManager`） |
| 7 | `spi/BloomFilter.java` | ~19 | `BloomFilter` 接口仅被 `BloomFilterProvider.create()` 作为返回类型、被 `RedisBloomFilterProvider` 内部匿名类实现——三者自闭环，对外零接口。与生效的 `protection/bloom/filter/BloomIFilter` 体系概念重复（见 §4.1）。 | 无 |
| 8 | `spi/BloomFilterProvider.java` | 33 | 仅被 `RedisBloomFilterProvider` implements，无消费者；`META-INF/services/...BloomFilterProvider` 为空。 | 无 |
| 9 | `protection/bloom/RedisBloomFilterProvider.java` | 45 | `implements BloomFilterProvider`，但**非 `@Component`**（连 bean 都不是）；`create()` 返回的 `BloomFilter` 无人使用；`create()` 参数 `expectedInsertions`/`falseProbability` 收而不用（SPI 设计粗糙佐证）。 | 无 |
| | **合计** | **≈ 828** | | |

> 删除上述 9 类可一次性移除约 **830 行**生产代码及 3 个孤立包（`evaluator/`、`event/`、`wrapper/`），并使 `spi/` 包瘦身至仅剩生效的 `BloomFilter`(已删)/`LockManager`/`LockHandle`/`BloomFilterProvider`(已删)。

### 3.2 P1 — 孤儿 bean / 名实不符

- **`spi/RedissonLockProvider`**：见上表 #6。它存在于容器（因 `@Component` + 根包扫描），但无消费者；且其 `getLockManager()` 只是把已注入的 `DistributedLockManager` 原样返回——是纯粹的冗余间接层。建议直接删除（调用方本就直接注入 `LockManager`/`DistributedLockManager`）。

### 3.3 嫌疑排除（曾怀疑、核实后证实有效，**勿删**）

> 诚实记录：初查时 `Local/Redis/Hierarchical` 三 `BloomIFilter` 实现的引用计数偏低且 `HierarchicalBloomIFilter` 使用 `@Qualifier`，曾疑为"装配歧义缺陷"。精读类声明后**推翻该怀疑**：

| 类 | 证据 | 结论 |
|----|------|------|
| `protection/bloom/filter/LocalBloomIFilter` | `@Component("localBloomFilter")` | **有效**——作为 Hierarchical 的本地层零件 |
| `protection/bloom/filter/RedisBloomIFilter` | `@Component("redisBloomFilter")` | **有效**——作为 Hierarchical 的远程层零件 |
| `protection/bloom/filter/HierarchicalBloomIFilter` | `@Primary @Component("hierarchicalBloomFilter")`，构造 `@Qualifier("localBloomFilter")` + `@Qualifier("redisBloomFilter")`（**与 bean 名精确匹配**） | **有效**——`@Primary` 使 `BloomSupport` 的单一 `BloomIFilter` 注入无歧义，双层布隆（本地 + Redis）正常工作 |

装配链：`BloomSupport` →（`@Primary`）`HierarchicalBloomIFilter` → `Local` + `Redis`。**无歧义、非缺陷**。

---

## §4 维度三：过度设计与多余模块

### 4.1 双重布隆体系（概念重复，P1）

存在两套功能重叠的布隆抽象：

| 体系 | 接口 | 实现 | 状态 |
|------|------|------|------|
| **A. `BloomIFilter` 体系**（`protection/bloom/filter/`） | `BloomIFilter` | `Local` / `Redis` / `Hierarchical`（`@Primary`） | **生效**，被 `BloomSupport`→`BloomFilterHandler` 使用 |
| **B. `BloomFilter` SPI 体系**（`spi/` + `RedisBloomFilterProvider`） | `BloomFilter` + `BloomFilterProvider` | `RedisBloomFilterProvider` | **死代码**（§3.1 #7/#8/#9） |

命名仅差一字母（`BloomFilter` vs `BloomIFilter`），易混淆。**建议**：删除体系 B（已在 §3.1 列为死代码），消除概念重复。

### 4.2 `BloomFilterConfig` 持有类（P2，轻度）

`protection/bloom/BloomFilterConfig.java`（29 行）是独立 `@Component`，仅用 `@Value` 持有 4 个 `resi-cache.bloom.*` 属性（`keyPrefix/bitSize/hashFunctions/hashCacheSize`），供 `Local/Redis IFilter` 读取。

- **问题**：与 `RedisProCacheProperties`（`@ConfigurationProperties(prefix="resi-cache")`）的集中式配置理念冲突——同一 `resi-cache.bloom.*` 前缀的属性被两种机制（`@Value` 散落 vs `@ConfigurationProperties` 集中）读取。
- **建议**：将这 4 个属性并入 `RedisProCacheProperties` 的 nested `Bloom` 类，删除 `BloomFilterConfig`，减少一个 bean。

### 4.3 单实现策略接口（P3，可保留）

| 接口 | 唯一实现 | 唯一消费者 | 评价 |
|------|----------|------------|------|
| `NullValuePolicy` | `DefaultNullValuePolicy` | `NullValueHandler` | 1 实现 + 1 消费者 |
| `TtlPolicy` | `DefaultTtlPolicy` | `TtlHandler` | 1 实现 + 1 消费者 |

属"单实现接口"轻度过设计，但保留了用户可替换策略的扩展点，**可辩护保留**。若追求极简，可内联为 handler 内私有逻辑；若保留，建议在 javadoc 明示扩展意图。

### 4.4 双套责任链模式并存（P3，认知负担，非死）

项目存在两套职责不同的责任链，但模式重复增加认知负担：

| 链 | 包 | 排序机制 | 职责 |
|----|----|----------|------|
| `AnnotationHandler` 链 | `handler/` | **手工** `setNext`（硬编码于 `RedisCacheInterceptor` 构造函数 `cacheable→evict→caching→put`） | 注解 → 注册 `Operation` |
| `CacheHandler` 链 | `chain/` | **动态** `@HandlerPriority(HandlerOrder.X)`（gap=100，`HandlerOrder` 枚举单一真相源） | 缓存读写保护（Bloom/SyncLock/.../Actual） |

两链都有效。建议在 `CLAUDE.md` / 包 `package-info` 中明确二者职责边界，避免维护者混淆。非删除项。

---

## §5 加载链与架构隐患

### 5.1 `MetricsAutoConfiguration` / `CachingEnablementValidation` 加载链脆弱（P1）

两者均标注 `@AutoConfiguration`，但：
- **不在** `META-INF/spring/...AutoConfiguration.imports`（该文件仅注册 `RedisCacheAutoConfiguration`）；
- **不在** `RedisCacheAutoConfiguration` 的 `@Import` 列表（仅 `JacksonConfig` / `RedisConnectionConfiguration` / `RedisCacheRegistryConfiguration` / `RedisProxyCachingConfiguration` / `RedisProCacheConfiguration`）。

它们的实际激活**完全依赖** `RedisProCacheConfiguration` 的 `@ComponentScan(basePackages = "io.github.davidhlp.spring.cache.redis")`（全包扫描）副作用——因为 `@AutoConfiguration` 元注解了 `@Configuration`，被组件扫描当成普通 `@Configuration` 拾取。

**风险**：这是反模式。`@AutoConfiguration` 类应通过 imports 文件或 `@Import` 显式加载，而非依赖组件扫描。一旦有人调整 `@ComponentScan` 范围或移除该注解，这两个配置类（含 `CacheMetricsRecorder`、`RedisCacheHealthIndicator`、`@EnableCaching` 校验等关键功能）会**静默失效**，无编译错误、无启动报错。

**建议**（二选一）：
- **(A)** 将两者加入 `AutoConfiguration.imports`，回归标准自动配置；
- **(B)** 去掉 `@AutoConfiguration`，改为在 `RedisCacheAutoConfiguration` 的 `@Import` 中显式引入。

### 5.2 全包 `@ComponentScan` 是孤儿 bean 潜伏的根因（P2）

`RedisProCacheConfiguration.@ComponentScan("io.github.davidhlp.spring.cache.redis")` 扫描整个根包，使任何 `@Component`（包括无消费者的 `RedissonLockProvider`）都被装载为 bean。这正是 §3 死代码能长期潜伏的机制性原因——粗粒度扫描掩盖了"装配了却没人用"的问题。

**建议**：删除死代码后，评估能否收窄 `@ComponentScan` 或改用显式 `@Import` 列表，使装配关系显式化。

### 5.3 `VersionEnvelope` 安全反模式（P1，安全）

`config/VersionEnvelope.java:30` 的 `payload` 字段使用：

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
```

`Id.CLASS` 允许反序列化时由 `@class` 指定**任意全限定类名**实例化——这与项目自身的安全目标直接冲突：`SecureJackson2JsonRedisSerializer` 正是为防御此类多态反序列化攻击而设计（白名单校验 `@class`，见最近 commit `fix: secure NullValue deserialization and harden cache config`）。

`VersionEnvelope` 在用（非死代码，被 `SecureJackson2JsonRedisSerializer` 读写），但其 `Id.CLASS` 全局类型信息削弱了序列化器的安全保证。**建议**：审查该字段是否真的需要多态类型；若需要，改用受限的 `Id.NAME` + 显式 `@JsonSubTypes` 白名单。

---

## §6 行动清单（按优先级）

### P0 — 删除确证死代码（零行为变化，≈830 行）

```text
delete: evaluator/SpelConditionEvaluator.java          (269 行) → 删除整个 evaluator/ 包
delete: event/CacheEvictedEvent.java                    ( 51 行) → 删除整个 event/ 包
delete: wrapper/CircuitBreakerCacheWrapper.java         (185 行)
delete: wrapper/RateLimiterCacheWrapper.java            (162 行) → 删除整个 wrapper/ 包
delete: spi/LockProvider.java                            ( 30 行)
delete: spi/RedissonLockProvider.java                    ( 34 行)
delete: spi/BloomFilter.java                             (~19 行)
delete: spi/BloomFilterProvider.java                     ( 33 行)
delete: protection/bloom/RedisBloomFilterProvider.java   ( 45 行)
```

删除后同步清理：`META-INF/services/io.github.davidhlp.spring.cache.redis.spi.{BloomFilterProvider,LockProvider}`（已为空，仅注释占位）、`RedisProCacheProperties` 中对应的 javadoc 示例、任何测试中对上述类的引用。

### P1 — 加载链与安全修正（行为可能变化，需测试）

1. 修正 `MetricsAutoConfiguration` / `CachingEnablementValidation` 的加载方式（§5.1 方案 A 或 B）。
2. 审查 `VersionEnvelope` 的 `@JsonTypeInfo(Id.CLASS)`（§5.3）。

### P2 — 简化过度设计（重构，需测试）

3. 将 `BloomFilterConfig` 的 4 个 `@Value` 属性并入 `RedisProCacheProperties`，删除该持有类（§4.2）。
4. 评估收窄 `@ComponentScan` 或改显式 `@Import`（§5.2）。

### P3 — 文档与可选内联

5. 在 `package-info` / `CLAUDE.md` 中说明两套责任链（`handler/` vs `chain/`）的职责边界（§4.4）。
6. 决定是否内联单实现策略接口 `NullValuePolicy` / `TtlPolicy`，或保留并补扩展意图文档（§4.3）。

---

## §7 附录：顶层类型引用计数全表

> `refs` = 该类型作为词边界标识符在 *其它* `.java` 文件中出现的文件数。`refs=0` 是死代码强信号（需结合 §3 装配链核实定论）。

```
refs  类型                                位置
  0   CacheEvictedEvent                   event/                      ← 死（§3.1 #2）
  0   CachingEnablementValidation         config/                     ← 加载链隐患（§5.1）
  0   CircuitBreakerCacheWrapper          wrapper/                    ← 死（§3.1 #3）
  0   DefaultNullValuePolicy              protection/nullvalue/       ← 策略默认实现（有效，注入 NullValueHandler）
  0   DefaultTtlPolicy                    protection/avalanche/       ← 策略默认实现（有效，注入 TtlHandler）
  0   HierarchicalBloomIFilter            protection/bloom/filter/    ← @Primary 双层布隆（有效，§3.3）
  0   LocalBloomIFilter                   protection/bloom/filter/    ← Hierarchical 零件（有效，§3.3）
  0   LockProvider                        spi/                        ← 死（§3.1 #5）
  0   MessageDigestBloomHashStrategy      protection/bloom/           ← BloomHashStrategy 唯一实现，被 Local/Redis IFilter 注入（有效）
  0   MetricsAutoConfiguration            config/                     ← 加载链隐患（§5.1）
  0   RateLimiterCacheWrapper             wrapper/                    ← 死（§3.1 #4）
  0   RedisBloomFilterProvider            protection/bloom/           ← 死（§3.1 #9）
  0   RedisBloomIFilter                   protection/bloom/filter/    ← Hierarchical 零件（有效，§3.3）
  0   RedissonLockProvider                spi/                        ← 死/孤儿（§3.1 #6）
  0   SpelConditionEvaluator              evaluator/                  ← 死（§3.1 #1）
  0   ThreadPoolEarlyExpirationExecutor   protection/refresh/         ← EarlyExpirationExecutor 唯一实现，被 EarlyExpirationSupport 注入（有效）
  1   BloomFilterProvider                 spi/                        ← 死（§3.1 #8）
  1   CacheErrorHandler / CacheMetricsRecorder / RedisCacheHealthIndicator   ← @Bean 产出（有效）
  1   DistributedLockManager / EvictionStrategyFactory / JacksonConfig       ← 有效
  1   RedisCacheAutoConfiguration / RedisCacheOperationSource                ← 入口（有效）
  1   TwoListEvictionStrategy / TwoListLRU / VersionEnvelope                 ← 有效（VersionEnvelope 见 §5.3）
  2~17 ...其余类型 refs≥2，均有明确装配链，有效...
```

> **注**：`refs=0` 中 `DefaultNullValuePolicy`、`DefaultTtlPolicy`、`Hierarchical/Local/Redis BloomIFilter`、`MessageDigestBloomHashStrategy`、`ThreadPoolEarlyExpirationExecutor` 经核实均为**有效的策略/零件实现**（通过 `@Component` + 类型注入激活），非死代码——`refs=0` 是因为它们作为"被注入的具体实现"，引用方向是接口而非类名。真正可删除的 `refs=0` 死代码见 §3.1。

---

*报告生成于静态分析，所有"死代码"结论均有 grep 零匹配 + 装配链核实双重证据支撑。删除 P0 清单前建议跑一次 `./mvnw test` 建立基线，删除后再跑确认绿。*
