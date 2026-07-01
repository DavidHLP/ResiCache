---
title: "ADR-0024: early-expiration 线程池配置接入 seam (兑现 dead config + 清理 EarlyExpirationSupport stale 引用)"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0012
  - ADR-0023
tags:
  - protection
  - refresh
  - config
  - deepening
  - interface-implementation-gap
  - round-16
---

# ADR-0024: early-expiration 线程池配置接入 seam (兑现 dead config + 清理 EarlyExpirationSupport stale 引用)

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0012(EarlyExpirationSupport 浅模块删除——本 ADR 不复活门面,仅改装配方式)/ ADR-0023(同款 refresh 域 locality 先例)

## 背景

第 16 轮架构评审基于 round 1–15 已落地 ADR-0009~0023。round 15(ADR-0023)已系统化扫描 `serialization/`、`config/`、`eviction/`、`observability/`、`protection/{refresh,bloom/filter,breakdown}` 等域并宣告「架构经 14 轮 deepening 已趋饱和」。本轮因此**不再单域重复扫描**,转而审视 round 1–15 的**结构性盲区**:各轮均按单一域内聚扫描,**跨 config/refresh 两域的接入关系未被任何一轮核验**。

跨域核验暴露一处真实的 interface/implementation 割裂:

**Interface(配置承诺)** —— `RedisProCacheProperties.EarlyExpirationProperties`(`config/RedisProCacheProperties.java:210-220`)向用户暴露三个可配置字段:

```java
private int poolSize = 2;
private int maxPoolSize = 10;
private int queueCapacity = 100;
```

wiki `mechanisms/early-expiration.md` 据此向用户承诺「池参数由 `resi-cache.early-expiration.{pool-size,max-pool-size,queue-capacity}` 配置」。

**Implementation(硬编码)** —— `ThreadPoolEarlyExpirationExecutor` 标注 `@Component`,Spring 走**无参构造** → `createExecutor()`(`protection/refresh/ThreadPoolEarlyExpirationExecutor.java:96-105`)**硬编码 `2, 10, 100`**:

```java
private static ExecutorService createExecutor() {
    return new ThreadPoolExecutor(2, 10, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(100), new EarlyExpirationThreadFactory(),
        new ThreadPoolExecutor.CallerRunsPolicy());
}
```

**核验事实**(全仓 grep):
- `getPoolSize()` / `getMaxPoolSize()` / `getQueueCapacity()` 在 `main/` **零调用**(唯一命中 `tpe.getPoolSize()` 是 `ThreadPoolExecutor` 自身运行时方法,非配置读取)
- 全 `config/` 包**无任何 `@Bean`** 显式用 properties 构造 executor
- 用户配 `resi-cache.early-expiration.pool-size: 8` **完全不生效**,永远走硬编码 2/10/100

**Friction**:这是 shallow interface 的变体——接口(配置属性类 + wiki 文档)承诺了「池可调」的维度,实现却未兑现。**The interface is the test surface**:任何读 properties 字段或 wiki 文档的维护者/LLM 都会被误导,以为配置生效;实际调试时才发现"配了没用"。这是 round 1–15 单域扫描的系统性盲区(config 域看到 properties 字段合理、refresh 域看到 executor 硬编码合理,**两域各自合理,接缝处没人看**)。

**附带发现**:wiki `mechanisms/early-expiration.md`(4 处)、`modules/observability.md`(1 处)、`concepts/hot-key.md`(1 处)仍引用 `EarlyExpirationSupport.java`——该类在 ADR-0012 已作为浅模块删除。CI `docs-link-check` 未捕获,因为这是**正文文件路径引用**(`src/main/java/.../EarlyExpirationSupport.java`)而非 Obsidian wikilink(`[[...]]`),link-check 只校验后者 + 关键类白名单。属同源(interface 文档撒谎)的 stale facts。

## deletion test

**正向(做深化)**:把 executor 装配从 `@Component` 无参硬编码改为 `@Bean` 读 properties → 配置字段(`poolSize/maxPoolSize/queueCapacity`)从 dead 变 live,interface(属性类 + wiki)与 implementation 一致 → 复杂度(「配置撒谎」这个隐性 bug)被消除,集中到 `@Bean` 单一 seam。

**反向(删配置字段而非兑现)**:删掉三个字段 → complexity 消失(它们本就零读取)。但**移除了 interface 承诺的能力**(用户失去「池可调」),且 wiki 文档继续撒谎。所以三个字段本身**在 interface 上 earning its keep**(它们表达了「这个池子应当可调」的设计意图),问题不在字段、在 impl 没兑现。→ 正确深化是**让 impl 兑现**,而非删字段。

**关键安全属性(零回归)**:`EarlyExpirationProperties` 默认值 `poolSize=2 / maxPoolSize=10 / queueCapacity=100` 与 executor 旧硬编码 `2 / 10 / 100` **逐字一致**。故用户不显式配置时,`@Bean` 用默认值构造的 executor 与原 `@Component` 无参构造**byte-for-byte 行为等价**——零行为回归(同款 ADR-0023 D2 byte-for-byte 等价纪律)。

## 决策

### D1 — `createExecutor` 参数化(执行)

```java
private static ExecutorService createExecutor(int corePoolSize, int maxPoolSize, int queueCapacity) {
    return new ThreadPoolExecutor(
            corePoolSize, maxPoolSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            new EarlyExpirationThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy());
}
```

### D2 — 新增配置化公开构造(`@Bean` 主路径,执行)

```java
public ThreadPoolEarlyExpirationExecutor(int corePoolSize, int maxPoolSize, int queueCapacity, MeterRegistry meterRegistry) {
    this(createExecutor(corePoolSize, maxPoolSize, queueCapacity),
         new ConcurrentHashMap<>(), meterRegistry, 30_000L);
}
```

委派既有包级 4 参构造(`ExecutorService, inFlight, MeterRegistry, long cleanupIntervalMs`),复用 `cleanupScheduler` 创建 / `RefreshRetryPolicy` / `RefreshTaskMetrics` 全套既有初始化。

### D3 — 无参构造保留为测试/默认 fallback(执行,行为等价)

```java
public ThreadPoolEarlyExpirationExecutor() {
    this(2, 10, 100, null);
}
```

委派 D2,硬编码 `2/10/100/null`——与旧无参构造 `this(createExecutor(), new ConcurrentHashMap<>(), null, 30_000L)` **byte-for-byte 等价**(createExecutor() 旧硬编码即 2/10/100)。保留无参构造供测试 `EarlyExpirationHandlerTest` 等不关心池参数的构造场景直接 `new`(零修改)。

### D4 — 去 `@Component`,改 `@Bean` 装配(执行)

executor 类移除 `@Component`。装配职责上移到 `RedisProCacheConfiguration`(已有 `@Bean @ConditionalOnMissingBean` 既有模式:`redisProCacheWriter` / `cacheManager` / `cacheStatisticsCollector` / `systemClock`):

```java
@Bean
@ConditionalOnMissingBean
public ThreadPoolEarlyExpirationExecutor earlyExpirationExecutor(
        RedisProCacheProperties properties,
        ObjectProvider<MeterRegistry> meterRegistryProvider) {
    RedisProCacheProperties.EarlyExpirationProperties ee = properties.getEarlyExpiration();
    return new ThreadPoolEarlyExpirationExecutor(
            ee.getPoolSize(), ee.getMaxPoolSize(), ee.getQueueCapacity(),
            meterRegistryProvider.getIfAvailable());
}
```

`@ConditionalOnMissingBean` 保留项目既有策略替换纪律(CLAUDE.md「策略替换」:`@Bean` + `@ConditionalOnMissingBean` 顶替默认)——用户仍可自定义 `ThreadPoolEarlyExpirationExecutor` bean 顶替默认实现。

### D5 — wiki stale 引用清理(执行)

- `mechanisms/early-expiration.md`:4 处 `EarlyExpirationSupport` → 改述为 `ThreadPoolEarlyExpirationExecutor`(`@Bean` 装配,从 properties 读池参数);`source-files` 删除不存在的 `EarlyExpirationSupport.java` 行
- `modules/observability.md`:1 处 `EarlyExpirationSupport` 观测点 → 改述为 executor `getStats()` / `getActiveCount()`
- `concepts/hot-key.md`:1 处 `EarlyExpirationSupport.getRefreshingKeyCount()` → `ThreadPoolEarlyExpirationExecutor.getActiveCount()`

### D6 — 不复活 `EarlyExpirationSupport` 门面(撤销,撞 ADR-0012)

歧路:借兑现配置之机,顺手把"刷新业务逻辑闭包"从 `EarlyExpirationHandler.scheduleAsyncRefresh` 下沉到一个新的 `EarlyExpirationRefresher` 领域门面。

**否决理由**:`EarlyExpirationSupport` 已在 ADR-0012 经 deletion test 判定为**纯转发浅模块**(5 方法全一行委派、`getThreadPoolStats`/`getRefreshingKeyCount` 无 main 消费者 = dead API)并删除。新增 `EarlyExpirationRefresher` 即便承载业务逻辑而非纯转发,在本场景(单一调用点 `EarlyExpirationHandler`、业务闭包仅 ~35 行)下**总复杂度不减、只搬家**,违反 ADR-0012 纪律与 deletion test。本 ADR 严格限定范围在**配置接入 + 装配方式**,不触碰 handler ↔ executor 的调用关系(handler 仍直持 executor,业务闭包仍由 handler 组装)。

## 后果

**增益(locality + leverage + 接口诚实)**:

1. **配置兑现**:`resi-cache.early-expiration.pool-size/max-pool-size/queue-capacity` 真正生效;读 properties 字段或 wiki 文档的维护者/LLM 不再被误导
2. **interface 与 implementation 一致**:属性类字段从 dead 变 live,「池可调」的 interface 承诺被实现兑现——deep module 的小接口背后真有池调度行为
3. **装配 seam 单一**:池参数从硬编码散点(`createExecutor`)收敛到 `@Bean` ↔ `EarlyExpirationProperties` 单一接缝,调池策略只改 properties 默认值一处
4. **wiki 诚实化**:6 处 `EarlyExpirationSupport` stale 引用清理,与 ADR-0012 删除事实对齐(补 CI `docs-link-check` 对正文文件路径引用的覆盖盲区)

**代价**:
- executor 装配契约从「`@Component` 自动扫描」改为「`@Bean` 显式装配」——属**项目既有标准模式**(`RedisProCacheConfiguration` 全部核心组件均 @Bean 装配),且 `@ConditionalOnMissingBean` 保留可顶替性
- 新增一个公开构造(D2)——但它是 `@Bean` 主路径的必要入口,非冗余抽象

**不变**:
- 包级 4 参构造(`ExecutorService, inFlight, MeterRegistry, long`)签名零变化 → 3 处测试调用零修改
- `submit` / `cancel` / `getStats` / `getActiveCount` / `initCleanupScheduler` / `shutdown`(ADR-0023 `shutdownGracefully`)/ `EarlyExpirationThreadFactory` / `RefreshRetryPolicy` / `RefreshTaskMetrics` 全部不动
- `ActualCacheHandler` / `EarlyExpirationHandler` 构造注入 `ThreadPoolEarlyExpirationExecutor` 零变化(Spring 自动解析到 @Bean)
- 默认行为 byte-for-byte 等价(properties 默认 2/10/100 = 旧硬编码)

## 规模与性质诚实声明

本 ADR **规模小于** cross-module seam 类 ADR(0009/0013/0022),与 ADR-0023 同属 refresh 域 locality 级 deepening,但**性质更强**:ADR-0023 是 2 处类内重复样板的 locality 收敛(行为本就正确,仅消除维护漂移);本 ADR 修复一处**真实的 dead config + 文档撒谎**(interface 承诺 ≠ impl 兑现),不仅有 locality 价值,更有**正确性 + AI-navigability** 价值(用户配置不生效是真实 bug,文档误导是真实 onboarding 摩擦)。

发现路径:round 16 不再单域扫描(round 15 已宣告饱和),转而审视**跨域接缝**——`config` 域(properties 字段)与 `refresh` 域(executor 硬编码)的接入关系,这是 round 1–15 各轮单域内聚扫描的系统性盲区。同源排查可推广至其他「properties 暴露字段 ↔ 实际消费者」的跨域接缝(本轮仅 early-expiration 命中,其余 properties 字段经核验均有 main 消费者)。

## 实施

### 修改(2 main + 0 test 源码 + 3 wiki + 1 新 ADR + log/index)

**main**:
- `protection/refresh/ThreadPoolEarlyExpirationExecutor.java` —— 去 `@Component`;`createExecutor()` → `createExecutor(int,int,int)`;新增 `public ThreadPoolEarlyExpirationExecutor(int,int,int,MeterRegistry)` 配置化构造;无参构造委派 `this(2,10,100,null)`;包级 4 参构造 + `shutdownGracefully`(ADR-0023)不动
- `config/RedisProCacheConfiguration.java` —— 新增 `@Bean @ConditionalOnMissingBean earlyExpirationExecutor(properties, meterRegistryProvider)`

**test 源码**:零修改(3 处 `new ThreadPoolEarlyExpirationExecutor(executorService, inFlight, null, cleanupIntervalMs)` 包级构造调用 + `EarlyExpirationHandlerTest` 无参构造均保留)。

**wiki**:
- `mechanisms/early-expiration.md` —— 4 处 EarlyExpirationSupport 改述 + source-files 去 stale 行 + 补配置接入说明
- `modules/observability.md` —— 1 处观测点改述
- `concepts/hot-key.md` —— 1 处方法引用改述
- `log.md` —— round 16 条目
- `index.md` —— ADR-0024 索引行

### 验证

- `mvnw checkstyle:check` —— 0 violations
- `mvnw test -Dtest='ThreadPoolEarlyExpirationExecutorTest'` —— 既有 3 嵌套测试类(含 ShutdownTests)全过
- `mvnw test` —— 全量回归(预期 3 个 pre-existing Testcontainers IT 失败,与 ADR-0019~0023 四次独立验证一致)

## 参考

- ADR-0012:EarlyExpirationSupport 浅模块删除(本 ADR D6 不复活门面的纪律源头)
- ADR-0023:executor shutdown seam(同款 refresh 域 locality + byte-for-byte 等价先例)
- ADR-0014:小规模 locality 收敛先例
- Ousterhout《A Philosophy of Software Design》—— "the interface is the test surface" / deep module
- `/improve-codebase-architecture` skill —— deletion test / interface-implementation gap 词汇
