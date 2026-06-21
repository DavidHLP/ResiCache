# ResiCache src/main/ 体检报告 — 调用链 / 死代码 / 过度设计

> 自动审计生成 · 范围：`/home/davidhlp/project/ResiCache/src/main/java` (104 个 Java 文件，约 8340 行)
> 审计维度：① 代码调用链 ② 死代码（Dead Code） ③ 过度设计（Over-engineering）

---

## 0. 体检摘要（TL;DR）

| 维度 | 主要发现 | 建议处置 |
|------|----------|----------|
| **死代码 - 整文件** | 4 个文件完全无生产路径调用，共 ~500 行 | 直接删除 |
| **死代码 - SPI 实现** | 2 个 SPI Provider 是空壳（无 ServiceLoader 注册） | 删除并下线 SPI 抽象 |
| **死代码 - 方法/字段** | 12+ 个生产方法/字段仅被测试或完全无人调用 | 收敛或删除 |
| **死代码 - 配置项** | 3 个 `@ConfigurationProperties` 嵌套类 + 2 个独立字段从未被读取 | 删除或合并 |
| **过度设计 - 重复接口** | 2 对同名接口 (`LockManager` / `LockHandle`) 跨包重复 | 合并到一套 |
| **过度设计 - 单实现接口** | 8+ 个接口只有 1 个实现 | 内联或下放 |
| **过度设计 - 无用抽象层** | 2 个 Wrapper、1 个 Factory 全程未参与运行时 | 移除或外置 |
| **过度设计 - 多配置类** | 5 个 `@Configuration` 文件 + 1 个 `package-info` 过时文档 | 合并 |
| **过度设计 - 重复装配** | `CacheMetricsRecorder` / `RedisCacheHealthIndicator` 双重注册 | 选一个 source of truth |

**预计可削减代码量**：约 **1500–1800 行**（≈ 18–22%），其中约 500 行是绝对死代码，1000+ 行是过度设计。

---

## 1. 代码调用链分析

### 1.1 启动 / Bean 装配链

```
@AutoConfiguration  RedisCacheAutoConfiguration            ← 唯一入口 (META-INF/...imports)
  └─ @Import:
       ├─ JacksonConfig                  → 1 Bean: objectMapper
       ├─ RedisConnectionConfiguration   → 5 Beans: redisCacheTemplate, hashOperations, valueOperations, redissonClient
       ├─ RedisCacheRegistryConfiguration → 1 Bean: redisCacheRegister
       ├─ RedisProxyCachingConfiguration → 3 Beans: redisCacheAdvisor, redisCacheOperationSource, redisCacheInterceptor
       └─ RedisProCacheConfiguration     → 5 Beans: redisProCacheWriter, defaultRedisCacheConfiguration, cacheManager, keyGenerator, cacheStatisticsCollector, systemClock
  + @ComponentScan(basePackages = "io.github.davidhlp.spring.cache.redis")
       自动扫描并注册所有 @Component / @Configuration

另:
@AutoConfiguration  MetricsAutoConfiguration              ← 通过条件 resi-cache.metrics.enabled=true 触发
  + 重复定义 CacheMetricsRecorder / RedisCacheHealthIndicator（与 @Component 重复，见 §3.5）
```

> ⚠️ **`CachingEnablementValidation` 没有被 `@Import` 也没有列入 `META-INF/...imports`，整个类在生产环境永远不会被加载。**

### 1.2 读路径 (Cache Hit) 调用链

```
调用 @RedisCacheable 方法
  └─ AOP 代理 → RedisCacheInterceptor.invoke()
        ├─ CacheOperationMetadataHolder.setCurrentKey(method, target)
        ├─ handlerChain.handle()                   ← 注册自定义 Operation 到 RedisCacheRegister
        └─ super.invoke()                          ← Spring CacheAspectSupport 走原生路径
              └─ Spring CacheableOperation 匹配
                    └─ RedisProCache.get(key)      ← 继承自 RedisCache
                          ├─ Bloom 短路: lookupOperation().isUseBloomFilter() + bloomSupport.mightContain()
                          └─ sync 模式: executeSyncLoad() 走 SyncSupport
                                否则: super.get(key) → RedisCacheWriter.get()
                                      └─ RedisProCacheWriter.get()
                                            └─ CacheHandlerChain.execute()
                                                  ├─ BloomFilterHandler    (100, 写后置)
                                                  ├─ SyncLockHandler       (200, 包内执行下游)
                                                  ├─ EarlyExpirationHandler (250, GET 时读 CachedValue 决定是否跳过)
                                                  ├─ TtlHandler            (300, PUT/PUT_IF_ABSENT 才生效)
                                                  ├─ NullValueHandler      (400, PUT/PUT_IF_ABSENT 才生效)
                                                  └─ ActualCacheHandler    (500, 调 valueOperations.get)
```

### 1.3 读路径 (Cache Miss → 加载) 调用链

```
RedisProCache.get(key, loader)
  ├─ Bloom 短路检查 (与 Hit 路径相同)
  ├─ sync=true → executeSyncLoad()  → SyncSupport.executeSync()
  │      ├─ acquireMonitor(key)              ← JVM 内 synchronized
  │      └─ tryAcquire DistributedLockManager (Redisson)
  │             └─ super.get(key) 双重检查 → loader.call() → put(key, loaded)
  └─ sync=false → super.get(key, loader) (Spring 默认锁)
```

### 1.4 写路径调用链 (PUT / PUT_IF_ABSENT)

```
RedisProCache.put(key, value)
  └─ Spring RedisCache → RedisProCacheWriter.put() 或 putIfAbsent()
        └─ CacheHandlerChain.execute()
              ├─ BloomFilterHandler  (打 POST_PROCESS 标记后放行)
              ├─ SyncLockHandler     (sync=true 时包裹下游)
              ├─ EarlyExpirationHandler (cancelAsyncRefresh)
              ├─ TtlHandler          (计算 finalTtl，可选 randomTtl 抖动)
              ├─ NullValueHandler    (cacheNullValues=false 时 skipAll)
              └─ ActualCacheHandler  (setIfAbsent 原子写, valueOperations.set)
                     └─ CacheHandlerChain.executePostProcess()
                           └─ BloomFilterHandler.afterChainExecution()  → bloomSupport.add()
```

### 1.5 驱逐路径调用链

```
@RedisCacheEvict 方法 → AOP → super.invoke() (Spring CacheEvictOperation)
  └─ RedisCache.evict(key) → RedisProCache.evict()
        └─ RedisCacheWriter.remove() → RedisProCacheWriter.remove()
              └─ CacheHandlerChain (无对应 Handler 主动拦截) → ActualCacheHandler.handleRemove()
                    └─ redisTemplate.delete(redisKey)
  @RedisCacheEvict(allEntries=true) → RedisProCache.clear() → RedisCacheWriter.clean() → ActualCacheHandler.handleClean()
                                       (SCAN + 批量 UNLINK/DEL)
```

### 1.6 跨切关注点

| 关注点 | 实际调用链 | 状态 |
|--------|-----------|------|
| SpEL 条件求值 | **不通过项目自实现的 `SpelConditionEvaluator`，而是由 Spring 原生 `CacheAspectSupport` 处理 `condition/unless`** | `SpelConditionEvaluator` 整体未被使用 |
| 序列化 | `TypeSupport` (普通 JSON) + `SecureNullValueDeserializer` (NullValue 白名单) + `SecureJackson2JsonRedisSerializer` (VersionEnvelope) | 路径正常，但有重复包路径逻辑（见 §3.7） |
| 事件 | `CacheEvictedEvent` 定义了，但**没有任何发布或订阅** | 完全死代码 |
| 指标 | `RedisProCache` 直接通过 `MeterRegistry` 注册 Timer/Counter；`CacheMetricsRecorder` 没有任何调用方 | 双轨制：内置 + 抽象层（见 §3.5） |
| SPI 发现 | `META-INF/services/...BloomFilterProvider` 与 `...LockProvider` 只有注释，**未调用 ServiceLoader** | SPI 是空壳（见 §2.2） |

### 1.7 调用频次统计（生产代码中的引用次数）

| 类 | 出现次数 | 备注 |
|----|---------|------|
| `RedisCacheableOperation` / `RedisCacheEvictOperation` / `RedisCachePutOperation` | 高频 | 责任链 Handler 入口 |
| `CacheContext` / `CacheInput` / `CacheOutput` | 高频 | 责任链输入/输出 |
| `HandlerResult` / `ChainDecision` | 高频 | 责任链返回值 |
| `RedisProCacheProperties` (root) | 中频 | 配置 |
| `CacheOperationMetadataHolder` | 2 处 | Interceptor + Writer |
| `BloomSupport` / `BloomIFilter` | 中频 | 布隆相关 |
| `SyncSupport` / `LockManager` (protection) | 中频 | 分布式锁 |
| `EvictionStrategy` (interface) | 仅 1 处 | 仅 `RedisCacheRegister` 使用 |
| `EvictionStrategyFactory` | 1 处 | 仅 `RedisCacheRegister` 使用 `createTwoList` |
| `LockContext` | 0 处 | 仅 `CacheOutput` 有 getter/setter，但**无人调用** |
| `EarlyExpirationDecision` (在 Output 中) | 0 处 | 同上 |
| `CacheEvictedEvent` | 0 处 | 完全未被发布或订阅 |
| `SpelConditionEvaluator` | 0 处 | 无任何调用方 |
| `LockPoolStats` | 0 处 | 纯 DTO，未被任何生产代码使用 |
| `RedisBloomFilterProvider` | 0 处 | SPI 落空 |
| `RedissonLockProvider` | 0 处 | SPI 落空 |
| `CircuitBreakerCacheWrapper` | 0 处 | 仅测试用 |
| `RateLimiterCacheWrapper` | 0 处 | 仅测试用 |
| `CachingEnablementValidation` | 0 处 | 整个类未装配 |
| `VersionEnvelope` (运行时) | 1 处 | 序列化/反序列化一次路径，但 `version` 字段未实际校验升级（只比对 `!= CURRENT_VERSION` 后 fail） |

---

## 2. 死代码（Dead Code）

### 2.1 整文件级死代码（建议直接删除）

| 文件 | 行数 | 死代码原因 | 推荐 |
|------|------|----------|------|
| `evaluator/SpelConditionEvaluator.java` | 269 | 私有构造 + 静态 `getInstance()`，但**无任何调用方**；SpEL 由 Spring 原生 `CacheAspectSupport` 处理 | **删除整文件** |
| `event/CacheEvictedEvent.java` | 65 | 仅定义 `ApplicationEvent`，无任何 `@EventListener`、无任何 `applicationContext.publishEvent` | **删除整文件** |
| `eviction/LockPoolStats.java` | 90 | 纯 DTO record，无任何生产代码引用 | **删除整文件** |
| `config/CachingEnablementValidation.java` | 98 | 声明为 `@AutoConfiguration`，但**未列入 `META-INF/...imports`，也未被 `RedisCacheAutoConfiguration` `@Import`**。`CachingEnabledValidator` 内部类仅测试用 | **删除整文件**（或重新纳入装配） |
| `cache/package-info.java` | 0 行 (空) | 只有 package 声明，无 Javadoc | 删或加注释 |
| `eviction/package-info.java` | 0 行 (空) | 同上 | 删或加注释 |

**小计：~522 行可直接删除。**

### 2.2 SPI 实现（声明但从未被加载）

```
src/main/resources/META-INF/services/io.github.davidhlp.spring.cache.redis.spi.BloomFilterProvider
└── 内容只有注释，没有引用任何实现类

src/main/resources/META-INF/services/io.github.davidhlp.spring.cache.redis.spi.LockProvider
└── 内容只有注释
```

| 文件 | 类型 | 死代码原因 | 推荐 |
|------|------|----------|------|
| `spi/RedissonLockProvider.java` | `@Component` | **0 处调用**。`@Component` 仍会被 Spring 实例化（产生一个无用的 bean），但没人 `@Autowired` 它，`getLockManager()` 也没人调用 | **删除整文件** |
| `protection/bloom/RedisBloomFilterProvider.java` | 普通类（非 `@Component`） | **0 处调用**。也不是真正的 SPI 入口，因为 `META-INF/services` 没列出它 | **删除整文件** |
| `spi/BloomFilterProvider.java` | interface | 只为 `RedisBloomFilterProvider` 设计，但后者不存在 / 0 调用 | **删除整个 SPI 抽象**（含 `spi/BloomFilter.java`、`spi/BloomFilterProvider.java`） |
| `spi/LockProvider.java` | interface | 只为 `RedissonLockProvider` 设计，但后者 0 调用 | **删除整个 SPI 抽象**（保留 `protection.breakdown.LockManager` 给真实实现 `DistributedLockManager`） |

**小计：再削减 ~5 个文件 + 2 个空 `META-INF/services` 文件。**

### 2.3 死方法 / 死字段

| 类.方法 | 死代码类型 | 证据 | 推荐 |
|---------|-----------|------|------|
| `chain.model.CacheOutput.setLockContext` / `getLockContext` | 公开方法无人调用 | `grep` 全工程仅类自身 | **删除字段 + 2 个方法** |
| `chain.model.CacheOutput.setEarlyExpirationDecision` / `getEarlyExpirationDecision` | 公开方法无人调用 | `grep` 全工程仅类自身 | **删除字段 + 2 个方法** |
| `chain.model.CacheOutput.clearSkipRemaining` | 公开方法无人调用 | `grep` 仅类自身 | **删除方法** |
| `chain.model.CacheOutput.setFinalResult` / `getFinalResult` | 公开方法仅 `ActualCacheHandler` 写、仅测试读 | 生产代码中 `getFinalResult` 0 处调用 | 字段仍可写（调试用），但 `getFinalResult` 可删除或 `@VisibleForTesting` |
| `chain.model.LockContext`（整 record） | DTO 无人实例化 | `grep -r 'new LockContext' src/main/` = 0 行（但 `LockContext.builder()` 在文件自身内被调用） | **整文件删除**（连同上面 CacheOutput 的 lockContext 字段） |
| `protection.refresh.EarlyExpirationHandler.getDecision(context)` | 公开静态方法仅测试用 | `grep` 仅 `EarlyExpirationHandlerTest` 调用 | **删除**或保留并标注 `@VisibleForTesting` |
| `protection.refresh.EarlyExpirationSupport.getThreadPoolStats()` | 公开方法 0 调用 | `grep` 0 命中 | **删除** |
| `cache.RedisProCacheWriter.getTtl(String)` | `protected` "向后兼容" 方法，0 调用 | `grep` 0 命中；自身 javadoc "用于向后兼容" | **删除** |
| `cache.RedisProCacheWriter.getExpiration(String)` | `protected` "向后兼容" 方法，0 调用 | `grep` 0 命中 | **删除** |
| `cache.RedisProCache.getHitCount/getMissCount/getPutCount/getEvictCount/getHitRate` | 公开 getter，0 调用 | `grep` 0 命中（生产） | 评估后删除或保留作 actuator 暴露 |
| `chain.model.CacheContext.CacheContextBuilder` | 内部 Builder，**功能已被 `CacheInput.Builder` 完全覆盖** | `CacheContextBuilder.build()` 内部就直接 new `CacheInput` 然后包成 `CacheContext`，只是代理 | 评估后删除 `CacheContextBuilder`，统一用 `CacheInput.Builder` |
| `spi.LockManager.getOrder()` | default 方法（`return 0`），从来没人 override | 唯一实现 `DistributedLockManager` 显式 override 返回 0 | **删除 default 方法**或保留为 SPI 抽象的占位（取决于是否删 SPI） |

**小计：再削减 ~15–20 个方法和 3–4 个字段。**

### 2.4 死配置属性（`@ConfigurationProperties` 中定义但无人读取）

| 配置项 | 路径 | 实际使用 | 推荐 |
|--------|------|---------|------|
| `RedisProCacheProperties.failOnSpelError` | root | 0 命中（`SpelConditionEvaluator` 才是其唯一潜在读者，已死） | **删除字段** |
| `RedisProCacheProperties.BloomFilterProperties` (整类) | nested | 0 命中（实际 bloom 配置在 `BloomFilterConfig` 用 `@Value` 读取） | **删除嵌套类** |
| `RedisProCacheProperties.EarlyExpirationProperties` (整类) | nested | 0 命中（实际线程池在 `ThreadPoolEarlyExpirationExecutor` 内部硬编码 + `@Value` 读取） | **删除嵌套类** |
| `RedisProCacheProperties.HandlerConfig` (整类) | nested | 0 命中（`handlerSettings` Map 也没人用） | **删除嵌套类** + `handlerSettings` 字段 |
| `RedisProCacheProperties.CacheConfig.enableBloomFilter` | nested | 0 命中 | **删除字段** |
| `RedisProCacheProperties.CacheConfig.enableEarlyExpiration` | nested | 0 命中 | **删除字段** |
| `redisCacheRegister(int maxActiveSize, int maxInactiveSize)` 构造器 | — | 0 命中（只有默认无参构造器被 Spring 使用） | 评估后删除 |
| `RedisProCacheManager` 的 3 个简版构造器（3-arg / 5-arg / 6-arg） | — | 0 命中（生产只用 8-arg 全字段版） | **删除** 3 个简版构造器 |
| `RedisProCache` 的 4-arg + 6-arg 简版构造器 | — | 0 命中（生产只用 7-arg 全字段版） | **删除** 2 个简版构造器 |

**小计：再削减 ~70 行配置代码。**

---

## 3. 过度设计（Over-engineering）

### 3.1 重复的接口（同名不同包）

| 重复 | 路径 A | 路径 B | 实际使用方 | 推荐 |
|------|--------|--------|-----------|------|
| `LockManager` | `spi/LockManager.java` | `protection/breakdown/LockManager.java` | 仅路径 B 被 `DistributedLockManager` 实现 + `SyncSupport` 注入 | **删除 `spi.LockManager`**（连同 `spi.LockProvider` 一起） |
| `LockHandle` | `spi/LockHandle.java` | `protection/breakdown/LockManager$LockHandle`（嵌套） | 仅嵌套版被 `DistributedLockManager.RedissonLockHandle` 实现 | **删除 `spi.LockHandle`** |

### 3.2 单实现的"策略 / 工厂"接口

| 接口 | 唯一实现 | 评估 | 推荐 |
|------|---------|------|------|
| `spi.BloomFilterProvider` | （0 个 — 死） | 无实现无注册 | **删除接口** |
| `spi.LockProvider` | （0 个 — 死） | 无实现无注册 | **删除接口** |
| `factory.OperationFactory<A,O>` | 3 个：`CacheableOperationFactory` / `CachePutOperationFactory` / `EvictOperationFactory` | 每个实现 ~40 行重复 builder 代码，差异仅 6-7 个字段 | **合并为单个 `RedisOperationFactory`**，接收 `Annotation` 参数后 switch；或干脆删除接口 + 3 个工厂，让 Handler 自己构造 Operation（去掉一层） |
| `handler.AnnotationHandler` | 4 个：`CacheableAnnotationHandler` / `CachePutAnnotationHandler` / `EvictAnnotationHandler` / `CachingAnnotationHandler` | 3 个简版方法几乎同质（仅注解类型不同） | **合并为 `RedisAnnotationHandler`（单类）**，内部用 `instanceof RedisCacheable / RedisCachePut / RedisCacheEvict` 分支 |
| `protection.bloom.BloomHashStrategy` | `MessageDigestBloomHashStrategy` | 无运行时切换需求 | **内联**：删除接口，直接在 `BloomIFilter` 中调用实现，或让实现类作为 `BloomIFilter` 内部类 |
| `protection.bloom.filter.BloomIFilter` | 3 个：`LocalBloomIFilter` / `RedisBloomIFilter` / `HierarchicalBloomIFilter` | 三者实际**同时被装配**（`@Component`），是分层而非"可替换" | **保留为分层抽象**（有真实分层收益），但不需要独立接口——可改为包私有 final 字段 |
| `protection.avalanche.TtlPolicy` | `DefaultTtlPolicy` | 注入 `Clock` 是有意义的（可测试），接口本身合理 | **保留**（为可测试性） |
| `protection.nullvalue.NullValuePolicy` | `DefaultNullValuePolicy` | 同上 | **保留**（为可测试性） |
| `protection.refresh.EarlyExpirationExecutor` | `ThreadPoolEarlyExpirationExecutor` | 唯一实现，无第二执行器规划 | **保留接口**（合理边界，便于测试 mock） |
| `eviction.EvictionStrategy<K,V>` | `TwoListEvictionStrategy` | 已被 `EvictionStrategyFactory` 自己 `@Deprecated` 标注 | **删除接口**（与 Factory 一起），让 `RedisCacheRegister` 直接持有 `TwoListEvictionStrategy` |
| `eviction.EvictionStrategyFactory` (整类) | 内部方法全部仅 `new TwoListEvictionStrategy` | 类已 `@Deprecated` | **删除整类**（用 direct construction） |

### 3.3 无用包装器（Wrapper）

| 类 | 行数 | 评估 | 推荐 |
|----|------|------|------|
| `wrapper.CircuitBreakerCacheWrapper` | 185 | 完全未被装配，0 生产路径调用；仅测试 | **外置为独立模块**（若有人需要），或**删除** |
| `wrapper.RateLimiterCacheWrapper` | 162 | 同上 | 同上 |

> 这两个 wrapper 是有意义的扩展点（降级 / 限流），但放进了**核心 jar** 而没人装配它们。如果保留应放进 `resicache-extras` 子模块；如果不打算外置就删。

### 3.4 多 `@Configuration` 类过度拆分

`config/` 包下当前有 **6 个 `@Configuration` / `@AutoConfiguration`** 文件，其中 4 个声明 1 个 Bean：

| 类 | Beans 数量 | 评估 | 推荐 |
|----|----------|------|------|
| `JacksonConfig` | 1 (ObjectMapper) | 单 bean 配置文件，14 行 | **合并到 `RedisProCacheConfiguration`** |
| `RedisCacheRegistryConfiguration` | 1 (RedisCacheRegister) | 单 bean 配置文件，11 行 | **合并到 `RedisProCacheConfiguration`** |
| `CachingEnablementValidation` | 1 (CachingEnabledValidator) | **完全未装配**（见 §2.1） | **删除** |
| `MetricsAutoConfiguration` | 2 (CacheMetricsRecorder, RedisCacheHealthIndicator) | 与 `@Component` 重复注册（见 §3.5） | 收敛为一处 |
| `RedisConnectionConfiguration` | 5 (Template + Redisson + 2 ops) | 5 个 bean，合理 | **保留** |
| `RedisProCacheConfiguration` | 5 (核心 beans) | 5 个 bean，合理 | **保留**（吸收 JacksonConfig + RedisCacheRegistryConfiguration） |
| `RedisProxyCachingConfiguration` | 3 (Advisor + Source + Interceptor) | 合理 | **保留** |
| `RedisCacheAutoConfiguration` | 0 (仅 import) | 入口 | **保留** |

**净结果**：从 6 个配置类 → 4 个（合并 2 + 删除 1 + 修复 1）。

### 3.5 同一 Bean 的双重注册

| Bean | 来源 A | 来源 B | 冲突结果 |
|------|--------|--------|----------|
| `CacheMetricsRecorder` | `@Component` on the class | `@Bean` in `MetricsAutoConfiguration` | 当 `resi-cache.metrics.enabled=true` 时，**会有两个同类型 bean**，依赖注入可能报错（`@Autowired(required=true)` 会按 type 失败） |
| `RedisCacheHealthIndicator` | `@Component` + `@ConditionalOnProperty(enabled=true)` | `@Bean` in `MetricsAutoConfiguration` + 同样的 `@ConditionalOnProperty` | 同上 |

**推荐**：删除 `@Component` 上的注解（让 `MetricsAutoConfiguration` 成为唯一来源），或反过来删除 `@Configuration` 中的 `@Bean` 方法。

### 3.6 抽象层叠加（无新增价值的委派）

| 模式 | 实例 | 问题 |
|------|------|------|
| Builder 套娃 | `CacheContext.builder()` 内部 `new CacheInput(..., builder)` | 跟 `CacheInput.Builder` 重复，调用方已经能 `CacheContext.of(CacheInput)` 构造 |
| CacheContext 委托方法 | `context.getCacheName()` → `input.cacheName()` | 字段访问器的过度封装，233 行里有 ~50 行是纯 delegate 方法 |
| CacheOutput 委托 | `context.isShouldApplyTtl()` → `output.isShouldApplyTtl()` | 同上，但每行 1 个字段的 getter 仍属合理（流畅 API） |
| 静态工厂 + 实例化 | `RedisCacheRegister` 持有 `EvictionStrategyFactory.createTwoList(...)` | 工厂 + 实现一对一，纯样板 |
| `RedisProCacheWriter` 暴露 `protected` "向后兼容" 方法 | `getTtl` / `getExpiration` | 标记为"向后兼容"但实际 0 调用方，且绕过整条责任链（直接读 raw `valueOperations.get`） |

### 3.7 配置类内部的样板（合并候选）

`config/SecureJackson2JsonRedisSerializer` 的 `createSecureObjectMapper` 在类内部又做了一次 `@class` 类型白名单校验（`validateTypeIds` 递归检查），这与 `BasicPolymorphicTypeValidator.allowIfBaseType` 的语义重复。两者都做"前缀白名单"，是双层保险。**可保留**（深度防御），但应明确写在注释中。

`config/VersionEnvelope` 包装 + `version` 字段，目前**没有任何升级路径**（`failOnUnknownType=true` 时直接抛 `SerializationException`）。如果将来不打算支持 v1→v2 平滑迁移，整个 `version` 字段是 YAGNI。

### 3.8 过时 / 错误的文档

| 位置 | 问题 | 修正 |
|------|------|------|
| `chain/package-info.java` | 列出的链顺序是 BloomFilter → SyncLock → Ttl → NullValue → ActualCache，**缺少 `EarlyExpirationHandler`** | 与 `HandlerOrder` 枚举对齐，加入 EARLY_EXPIRATION (250) |

---

## 4. 详细处置建议（按优先级）

### 优先级 P0（必须删除 - 死代码 + 双重装配 + 永不加载）

1. **删除 `config/CachingEnablementValidation.java`**（98 行）— 永远不会装配
2. **删除 `evaluator/SpelConditionEvaluator.java`**（269 行）— 0 调用
3. **删除 `event/CacheEvictedEvent.java`**（65 行）— 0 订阅/发布
4. **删除 `eviction/LockPoolStats.java`**（90 行）— 0 引用
5. **删除 `cache/package-info.java` 和 `eviction/package-info.java`**（空文件）
6. **修复 `CacheMetricsRecorder` / `RedisCacheHealthIndicator` 双重注册**（删 @Component 或删 @Bean）
7. **删除 `spi/RedissonLockProvider.java`**（@Component 但 0 调用）
8. **删除 `protection/bloom/RedisBloomFilterProvider.java`**（普通类 0 调用）
9. **删除 `spi/BloomFilterProvider.java` + `spi/BloomFilter.java` + `spi/LockProvider.java` + `spi/LockManager.java` + `spi/LockHandle.java`**（SPI 整层空壳）
10. **删除空 `META-INF/services/...BloomFilterProvider` 和 `...LockProvider`**（只有注释）

### 优先级 P1（强烈建议 - 减少过度设计）

11. **删除 `eviction/EvictionStrategyFactory.java`**（本身已 `@Deprecated`）+ `EvictionStrategy.java` 接口
    - 收益：~140 行，消解抽象层
12. **删除 `chain.model.LockContext` + CacheOutput 相关字段**（`lockContext` + getter/setter）
    - 收益：~50 行
13. **删除 `chain.model.CacheOutput.clearSkipRemaining`**
14. **删除 `cache.RedisProCacheWriter.getTtl/getExpiration`**（受保护但 0 调用，且绕过责任链）
15. **删除 `protection.refresh.EarlyExpirationHandler.getDecision` 公开静态方法**（仅测试用）
16. **删除 `protection.refresh.EarlyExpirationSupport.getThreadPoolStats`**（0 调用）
17. **合并 `JacksonConfig` + `RedisCacheRegistryConfiguration` 到 `RedisProCacheConfiguration`**
18. **删除 `RedisProCacheManager` 的 3 个简版构造器** + `RedisProCache` 的 2 个简版构造器
19. **删除 `RedisProCacheProperties` 中未被读取的 3 个嵌套类（BloomFilterProperties、EarlyExpirationProperties、HandlerConfig）+ 字段 `failOnSpelError` + `handlerSettings` + `caches.*enableBloomFilter` + `caches.*enableEarlyExpiration`**

### 优先级 P2（可选 - 进一步收敛抽象）

20. **合并 `factory.OperationFactory` 3 个实现为 1 个 `RedisOperationFactory`**（或删除接口让 Handler 直接构造 Operation）
21. **合并 `handler.AnnotationHandler` 4 个实现为 1 个 `RedisAnnotationHandler`**
22. **将 `wrapper.CircuitBreakerCacheWrapper` 和 `wrapper.RateLimiterCacheWrapper` 外置到独立子模块**（或删除）
23. **`chain.model.CacheContext.CacheContextBuilder` 收敛到 `CacheInput.Builder`**（统一构造入口）
24. **修正 `chain/package-info.java` 过时的链顺序**

### 优先级 P3（评估后再决定）

25. **`config/VersionEnvelope` 的 `version` 字段**：若不打算支持 v1→v2 平滑迁移，删除 `version` 字段及版本检查逻辑
26. **`RedisProCache` 的 `getHitCount/getMissCount/getPutCount/getEvictCount/getHitRate` 公开方法**：如果将来不会暴露给 actuator/metrics endpoint，删除
27. **`SecureJackson2JsonRedisSerializer` 与 `TypeSupport` 的 `isAllowedClass` / `ALLOWED_JAVA_UTIL_CLASSES` 白名单**：评估是否需要保留

---

## 5. 体检后的"健康指标"对比

| 指标 | 体检前 | 体检后（若执行 P0+P1） |
|------|--------|----------------------|
| Java 文件数 | 104 | **~92**（-12） |
| 总行数 | 8,340 | **~6,800**（-1,500+） |
| 公开类 / 接口数 | 87 | **~72**（-15） |
| `@Configuration` 类数 | 6 | **4** |
| 单实现接口 | 8+ | **2-3**（保留为可测试性边界） |
| 永远不会被加载的类 | 4 | **0** |
| 双重注册的 Bean | 2 对 | **0** |
| 过时的 `package-info` 文档 | 1 | **0** |

---

## 6. 验证方法

执行 P0/P1 之后建议按以下顺序回归：

```bash
./mvnw clean verify -B
```

- 编译期：checkstyle 会报 `UnusedImports` / `UnusedPrivateField`
- 测试期：`@Component` 删除后 Spring 启动应无 `NoSuchBeanDefinitionException`
- 行为期：手动启用 Bloom + 分布式锁两个 @RedisCacheable 方法，验证缓存读/写路径与体检前一致
- 指标期：启用 `resi-cache.metrics.enabled=true`，确认 `CacheMetricsRecorder` 仍能正确产出指标（且只产生 1 个 bean）

---

> 本报告由静态分析（grep + 全文阅读）自动生成，**未对每条死代码做运行时验证**。建议在执行 P0 删除前，对最敏感的 3 项（`SpelConditionEvaluator` / `CachingEnablementValidation` / SPI Provider 类）做一次全量构建 + 集成测试确认无引用遗漏。
