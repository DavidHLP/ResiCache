# ResiCache 深度缺陷分析报告

> **视角**：Spring Cache Redis 增强插件  
> **分析日期**：2026-05-23  
> **代码基线**：master 分支（commit c0afd74）  
> **目标**：在现有 gap analysis 基础上，逐层深入代码，定位根因，给出可落地的修复路径

---

## 目录

1. [核心语义错位（Critical）](#一核心语义错位critical)
2. [数据一致性与性能缺陷](#二数据一致性与性能缺陷)
3. [配置与生产就绪性缺陷](#三配置与生产就绪性缺陷)
4. [与 Spring Cache 集成的边界缺陷](#四与-spring-cache-集成的边界缺陷)
5. [修复路线图](#五修复路线图)

---

## 一、核心语义错位（Critical）

作为 Spring Cache 的增强插件，最致命的缺陷不是功能缺失，而是**行为与 Spring Cache 标准语义不一致**。用户在使用 `@RedisCacheable` 时期望获得 "Spring Cache + 增强" 的体验，但当前实现却在多个关键环节偏离了 Spring 的契约。

### 1.1 Key 解析与 Spring 原生机制不一致 — 导致元数据全部失效

**现象**：`CacheableAnnotationHandler` 手动解析 SpEL key 表达式，构建的 key 与 Spring `CacheAspectSupport` 最终生成的 key 可能不一致。当两者不一致时，`RedisCacheRegister` 中注册的 `RedisCacheableOperation` 元数据永远无法被 `RedisProCacheWriter` 正确查找到，导致所有 ResiCache 特有功能（TTL、Bloom、锁、预刷新）**静默失效**。

**根因分析**（三层不一致）：

**第一层：EvaluationContext 不一致**

Spring 的 key 生成路径：`CacheAspectSupport.generateKey()` → `CacheOperationExpressionEvaluator.key()` → 使用 `CacheEvaluationContext`。

`CacheEvaluationContext` 暴露的变量：
- `#root.method` / `#root.target` / `#root.targetClass` / `#root.caches` / `#root.args`
- `#result`（用于 unless，但 context 已包含）
- 方法参数按名称和索引绑定
- `BeanFactoryResolver` 支持引用 Spring beans

ResiCache 的 `CacheableAnnotationHandler.resolveKey()`（第95-116行）使用的 `StandardEvaluationContext`：
- 只暴露了自定义的 `RootObject`（第130-146行），包含 `method`, `args`, `target`, `targetClass`, `methodName`
- **缺少** `#root.caches` —— 当用户 SpEL 引用 `#root.caches` 时解析失败
- **缺少** `BeanFactoryResolver` —— 无法引用 `@beanName`
- 参数绑定只通过反射 `method.getParameters()`，当编译未带 `-parameters` 时，参数名为 `arg0` 而非实际名称

**第二层：key 生成时机不一致**

Spring 的 key 生成在 `CacheAspectSupport.execute()` 内部，**在 condition 评估之后**。这意味着如果 `condition="false"`，Spring 根本不会生成 key。

ResiCache 的 key 生成在 `RedisCacheInterceptor.invoke()`（第47-63行），**在调用 `super.invoke()` 之前**，此时：
- condition 尚未评估
- unless 尚未评估
- 如果 condition 为 false，ResiCache 仍然注册了 operation 元数据，造成注册表污染

**第三层：注册 key 与 writer 查找 key 的分裂**

注册阶段（`RedisCacheRegister.registerCacheableOperation`，第30-39行）：
```java
String key = buildKey(cacheName, cacheOperation.getKey(), "CACHE");
```
这里用的是 annotation 中声明的 **原始 SpEL 表达式字符串**（如 `"#id"`），不是解析后的值。

Writer 查找阶段（`RedisProCacheWriter.buildContext`，第272-273行）：
```java
RedisCacheableOperation cacheOperation =
    redisCacheRegister.getCacheableOperation(cacheName, actualKey);
```
这里传入的是 `extractActualKey()` 从 Redis key 中提取的实际 key 值（如 `"123"`）。

但 `RedisCacheRegister.buildKey()` 的拼接方式是：
```java
sb.append(type).append(':').append(name).append(':').append(key);
```
如果 `cacheOperation.getKey()` 返回 `"#id"`，那么注册表中的 key 是 `CACHE:users:#id`。
如果 `actualKey` 是 `"123"`，那么查找 key 是 `CACHE:users:123`。
两者**永远不匹配**。

**代码位置**：
- `CacheableAnnotationHandler.java:95-116` — 手动 key 解析
- `CacheableAnnotationHandler.java:130-146` — RootObject 不完整
- `RedisCacheRegister.java:30-39, 69-79` — 注册与查找逻辑
- `RedisProCacheWriter.java:262-285, 294-300` — buildContext 与 extractActualKey

**修复建议**：
1. **彻底放弃手动 key 解析**。在 `RedisCacheInterceptor` 中不应尝试解析 key，而是应在 writer 层通过 operation identity 查找元数据。
2. 使用 `(Method, Class<?>)` 或 `AnnotatedElementKey` 作为元数据的查找键，而不是生成的缓存 key。Spring 的 `CacheOperationCacheKey` 就是这样设计的（`CacheAspectSupport.java:345`）。
3. 如果必须继续使用生成后的 key 作为查找键，则在 writer 层通过 Spring 的 `CacheOperationExpressionEvaluator` 重新评估，确保与 Spring 生成的 key 100% 一致。

---

### 1.2 Bloom Filter 无法真正防止缓存穿透 — 产品语义欺诈

**现象**：README 宣称 Bloom Filter 可以防止缓存穿透，但实际在标准 `@Cacheable` 流程中，Bloom negative 只是跳过 Redis 查询，**用户方法仍会被调用**，数据库仍会被穿透。

**根因分析**（Spring Cache 标准执行流程）：

```text
Spring @Cacheable 标准流程：
  1. CacheAspectSupport.execute()
  2. findCachedValue() → cache.get(key) → RedisCache.lookup()
  3. 如果返回 null（cache miss）
  4.   → evaluate() → invokeOperation(invoker) → 调用用户方法 → 查询数据库
  5.   → collectPutRequests() → cache.put(key, value)
```

ResiCache 的 BloomFilterHandler 位于第 3 步的 `RedisCache.lookup()` → `RedisProCacheWriter.get()` 内部：

```java
// BloomFilterHandler.java:73-85
private HandlerResult handleGet(CacheContext context) {
    boolean mightContain = bloomSupport.mightContain(...);
    if (!mightContain) {
        return HandlerResult.terminate(CacheResult.rejectedByBloomFilter());
    }
    ...
}
```

`CacheResult.rejectedByBloomFilter()` 最终返回 `result.getResultBytes() == null`（`RedisProCacheWriter.java:85`）。

Spring 接收到 `null` 后判定为 **cache miss**，于是执行第 4 步——调用用户方法。

**这意味着**：Bloom Filter 只节省了 "Redis 查询" 这一次网络往返，但**没有节省 "数据库查询"**。对于缓存穿透防护来说，这是核心语义错误——防穿透的目的是保护下游数据库，不是保护 Redis。

**真正的防穿透应该在哪里实现**：

有两个正确位置：
1. **CacheAspectSupport 层面**：在 `findCachedValue()` 返回 null 后、`invokeOperation()` 之前插入 Bloom 检查。这需要自定义 `CacheAspectSupport` 或 AOP 环绕通知。
2. **Cache.get(key, Callable) 层面**：在 loader callback 中拦截。Spring Data Redis 的 `RedisCache.get(key, Callable)` 会调用 `RedisCacheWriter.get(name, key, valueLoader, ...)`，可以在 valueLoader 执行前做 Bloom 检查。

当前实现位置（writer 层的 `get(name, byte[] key)`）**太晚**。

**代码位置**：
- `BloomFilterHandler.java:66-98` — GET 处理
- `RedisProCacheWriter.java:67-86` — get 方法返回 result bytes
- `CacheAspectSupport.java:519-538` — findCachedValue 逻辑

**修复建议**：
1. 明确定义 Bloom Filter 的契约：
   - 如果契约是 "防止 Redis 查询"，修改 README 和宣传文案，不要声称防穿透。
   - 如果契约是 "防止数据库穿透"，必须将 Bloom 检查上移至方法调用前。
2. 短期方案：在 `RedisProCache` 中覆写 `get(Object key, Callable<T> valueLoader)`，在调用 `super.get()` 之前或之中插入 Bloom 检查，当 Bloom negative 时返回一个特殊的 null-wrapper（如 `NullValue`）而不调用 loader。
3. 长期方案：提供一个自定义的 `CacheAspectSupport`，在 `evaluate()` 中处理 Bloom 语义。但这会大幅增加与 Spring 版本的耦合度。

---

### 1.3 Sync 锁在 Writer 层无法保证单飞（Single-Flight）语义

**现象**：`@RedisCacheable(sync = true)` 在 ResiCache 中只保证 Redis 操作的原子性，不保证用户方法只被调用一次。高并发 miss 场景下，多个线程仍会同时执行被注解的方法。

**根因分析**：

Spring 的 `sync=true` 语义在 `CacheAspectSupport.executeSynchronized()`（第455-510行）中实现：
```java
private Object executeSynchronized(...) {
    Object key = generateKey(context, NO_RESULT);
    Cache cache = context.getCaches().iterator().next();
    return wrapCacheValue(method, doGet(cache, key, () -> unwrapReturnValue(invokeOperation(invoker))));
}
```

这里 `doGet(cache, key, () -> ...)` 调用的是 `Cache.get(key, Callable valueLoader)`。Spring Data Redis 的 `RedisCache.get(key, Callable)` 实现：
```java
public <T> T get(Object key, Callable<T> valueLoader) {
    return (T) lookup(key, valueLoader); // 使用 RedisCacheWriter.get(name, key, valueLoader, ...)
}
```

`RedisCacheWriter` 接口有专门的带 loader 的方法：
```java
byte[] get(String name, byte[] key, Supplier<byte[]> valueLoader, Duration ttl, boolean timeToIdleEnabled);
```
Spring Data Redis 的 `DefaultRedisCacheWriter` 在此方法内使用 Redis `SET NX` 锁保证只有一个线程执行 valueLoader。

**ResiCache 的问题**：

`SyncLockHandler` 位于 `RedisProCacheWriter.get()` 内部（writer 层），它只保护 "从 Redis 读取" 这一操作：
```java
// SyncLockHandler.java:64-89
protected HandlerResult doHandle(CacheContext context) {
    CacheResult result = syncSupport.executeSync(
        lockContext.lockKey(),
        () -> executeChainInLock(context),  // 执行后续 handler（ActualCacheHandler 的 GET）
        lockContext.timeoutSeconds()
    );
    return HandlerResult.terminate(result);
}
```

但 `executeChainInLock` 最终只调用 `valueOperations.get()`（`ActualCacheHandler.java:107`），即 "先查 Redis，miss 则返回 null"。

此时 `RedisProCacheWriter.get()` 返回 null 给上层 `RedisCache.lookup()`，`RedisCache` 认为 cache miss，然后调用 `invokeOperation(invoker)` —— **这发生在锁之外**。

**代码位置**：
- `SyncLockHandler.java:36-149` — 锁处理逻辑
- `ActualCacheHandler.java:98-120` — GET 操作只读 Redis
- `CacheAspectSupport.java:455-510` — Spring 的 sync 语义
- `RedisCache.java`（Spring Data Redis）— get(key, Callable) 使用 writer 的带 loader 方法

**修复建议**：
1. 在 `RedisProCacheWriter` 中正确实现 `get(String name, byte[] key, Supplier<byte[]> valueLoader, Duration ttl, boolean timeToIdleEnabled)` 方法（当前未覆写，使用接口 default 实现）。
2. 在该方法内，使用 Redisson 或 Redis `SET NX` 实现分布式锁，保证只有一个线程执行 `valueLoader`。
3. 添加高并发集成测试：10 个线程同时请求同一个 miss key，断言底层方法只被调用 1 次。

---

## 二、数据一致性与性能缺陷

### 2.1 缓存命中路径的写放大 — 每次读取都是一次写入

**现象**：`ActualCacheHandler.processCacheHit()`（第127-142行）在每次缓存命中时都会执行 Redis 写操作：

```java
private CacheResult processCacheHit(CacheContext context, CachedValue cachedValue) {
    CachedValue updatedValue = cachedValue.withAccessUpdate();  // 创建新对象
    updateTtlIfExists(context, updatedValue);                   // 写回 Redis
    ...
}

private void updateTtlIfExists(CacheContext context, CachedValue cachedValue) {
    valueOperations.setIfPresent(context.getRedisKey(), cachedValue, Duration.ofSeconds(remainingTtl));
}
```

**影响**：
- **读 QPS 100% 转化为写 QPS**：如果业务读 QPS 是 10万，ResiCache 会产生额外 10万 写 QPS。
- **主从复制流量翻倍**：每次读取都会触发一次 replication。
- **AOF/RDB 膨胀**：写入命令数量与读取量成正比。
- **Redis CPU 升高**：写操作比读操作消耗更多 CPU。
- **竞态条件**：`setIfPresent` 不是原子操作。如果 key 在 `get` 和 `setIfPresent` 之间被 evict，行为不可预期。

**与 Spring Data Redis 的差异**：

Spring Data Redis 的 `RedisCache.lookup()` 实现是纯粹的读取：
```java
@Nullable
protected Object lookup(Object key) {
    byte[] bytes = serializeCacheKey(createCacheKey(key));
    byte[] value = cacheWriter.get(name, bytes);
    return value == null ? null : deserializeCacheValue(value);
}
```

没有写操作。如果用户需要 TTI（Time-To-Idle），Spring Data Redis 3.2+ 提供了 `enableTimeToIdle()`，使用 Redis 6.2+ 的 `GETEX` 命令在读取时原子性地刷新过期时间。

**代码位置**：
- `ActualCacheHandler.java:127-159` — processCacheHit 与 updateTtlIfExists

**修复建议**：
1. **立即移除默认的命中写操作**。`processCacheHit` 应该只返回缓存值，不更新任何元数据。
2. 如果需要访问统计，使用 Micrometer Counter，而不是改写缓存值。
3. 如果确实需要 TTI 功能，使用 Spring Data Redis 的 `enableTimeToIdle()` 机制（`GETEX`），或在 `RedisProCacheWriter` 中覆写 `get` 方法，在读取后使用原子命令刷新 TTL，而不是重写整个 value。

---

### 2.2 PreRefresh 实为提前过期 — 命名与行为不符

**现象**：`PreRefreshHandler` 声称提供"预刷新"功能，但其实现是**提前让缓存失效**，让用户方法重新加载。

**根因分析**：

同步模式（`PreRefreshHandler.java:87-93`）：
```java
if (decision.needsRefresh() && decision.isSync()) {
    context.setAttribute(CacheContext.AttributeKey.PRE_REFRESH_SKIPPED, true);
    return HandlerResult.skipAll();
}
```
然后 `ActualCacheHandler` 检查该标记后返回 miss（第65-67行）。这意味着 Spring Cache 认为 cache miss，调用用户方法。

异步模式（`PreRefreshHandler.java:142-176`）：
```java
preRefreshSupport.submitAsyncRefresh(redisKey, () -> {
    CachedValue liveValue = (CachedValue) valueOperations.get(redisKey);
    if (...) {
        atomicShortenTtlIfValueUnchanged(redisKey, cachedValue);  // 缩短 TTL 到 5 秒
    }
});
```
异步模式只是**缩短 TTL**，让 key 更快过期。它**没有调用原始方法**来重建缓存值。

**为什么这不是预刷新**：

真正的预刷新（refresh-ahead）应该：
1. 检测到 key 即将过期
2. 在后台**调用原始数据加载逻辑**（loader/method）获取新值
3. 将新值写入缓存，重置 TTL
4. 前台用户请求仍然读取到旧值（或短暂等待后读取到新值）

ResiCache 的实现：
1. 检测到 key 即将过期
2. 同步模式：直接返回 miss，让**当前请求**去加载
3. 异步模式：缩短 TTL，让**下一个请求**去加载

两者都没有在后台主动重建缓存。这是 "early expiration"，不是 "pre-refresh"。

**代码位置**：
- `PreRefreshHandler.java:66-97` — sync 模式返回 miss
- `PreRefreshHandler.java:142-176` — async 模式只缩短 TTL

**修复建议**：
1. **诚实命名**：将 `@RedisCacheable.enablePreRefresh` 重命名为 `enableEarlyExpiration`，`preRefreshThreshold` 重命名为 `earlyExpirationThreshold`。
2. 如果坚持要做真正的 refresh-ahead：
   - 需要一个机制来捕获原始方法的 loader callback（Spring 的 `Cache.get(key, Callable)` 提供了这个 callback）。
   - 使用单飞保护（single-flight）确保只有一个后台任务在刷新。
   - 在 bounded 线程池中执行刷新。
   - 刷新成功后更新缓存；刷新失败时保留旧值并记录 metrics。
   - 这是一个复杂功能，建议作为 P2 实现。

---

### 2.3 Bloom Filter 假阳性污染 — 逆向操作

**现象**：`BloomFilterHandler.handleGetPostProcessing()`（第176-193行）在 Bloom positive 但 cache miss 时，将 key 加入 Bloom Filter：

```java
if (!result.isHit()) {
    bloomSupport.add(context.getCacheName(), context.getActualKey());
}
```

**根因分析**：

Bloom Filter 的设计目的是用少量空间判断 "一个元素**可能**在集合中" 或 "**一定不在**集合中"。

向 Bloom Filter 添加元素的正确时机是：**确认元素存在时**（即数据已成功从数据库加载并写入缓存）。

当前代码的添加时机是：**Bloom 认为可能存在，但缓存中没有**。这会导致：
1. 缓存过期后首次 miss → 加入 Bloom → 后续请求 Bloom 始终 positive → 无法利用 Bloom 过滤过期 key 的穿透
2. 数据从数据库删除后 → 缓存 miss → 加入 Bloom → Bloom 永久包含已删除的 key → 假阳性率持续上升
3. 假阳性 key 的查询永远走不到 "被 Bloom 拒绝" 的短路路径

**代码位置**：
- `BloomFilterHandler.java:176-193` — handleGetPostProcessing

**修复建议**：
1. 移除 `handleGetPostProcessing` 中的 `bloomSupport.add()` 逻辑。
2. Bloom Filter 的 add 应该只在**数据被确认存在后**执行：
   - 用户方法执行成功并返回非 null 值后
   - 或缓存 PUT 操作成功后（当前 `addToBloomFilter` 已在 PUT 后置处理中实现，这是正确的）
3. 考虑使用 **Counting Bloom Filter** 或 **Cuckoo Filter** 支持删除操作，以便在缓存 evict 时同步从 Bloom 中移除。

---

## 三、配置与生产就绪性缺陷

### 3.1 声明的配置属性大量未生效

**现象**：`RedisProCacheProperties` 声明了 15+ 个配置项，但 `RedisProCacheConfiguration` 和 `RedisProCacheManager` 中**大量未使用**。

**未生效配置清单**：

| 配置项 | 声明位置 | 应生效位置 | 实际状态 |
|--------|---------|-----------|---------|
| `transactionAware` | `RedisProCacheProperties:47` | `RedisProCacheManager` | ❌ 未使用 |
| `keyPrefix` | `RedisProCacheProperties:50` | `RedisCacheConfiguration` | ❌ 未使用 |
| `caches` (per-cache config) | `RedisProCacheProperties:71` | `RedisProCacheManager` | ❌ 未使用 |
| `disabledHandlers` | `RedisProCacheProperties:74` | `CacheHandlerChainFactory` | ❌ 未使用 |
| `handlerSettings` | `RedisProCacheProperties:77` | 各 Handler | ❌ 未使用 |
| `serializer.failOnUnknownType` | `RedisProCacheProperties:107` | `SecureJackson2JsonRedisSerializer` | ❌ 未使用 |
| `serializer.typeProperty` | `RedisProCacheProperties:109` | `SecureJackson2JsonRedisSerializer` | ❌ 未使用 |
| `redis.*` (deployment) | `RedisProCacheProperties:65` | `RedisConnectionConfiguration` | ⚠️ 部分使用，未测试所有模式 |

**具体代码问题**：

`RedisProCacheConfiguration`（第25-93行）中：
```java
@Bean
public RedisCacheConfiguration defaultRedisCacheConfiguration(...) {
    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(properties.getDefaultTtl())
        .serializeKeysWith(...)
        .serializeValuesWith(...);
    // 没有配置：
    // - .prefixCacheKeyWith(properties.getKeyPrefix())
    // - .enableTimeToIdle()
    // - .disableCachingNullValues() (条件判断)
    // - transactionAware
    return config;
}

@Bean
public RedisProCacheManager cacheManager(...) {
    RedisProCacheManager manager = new RedisProCacheManager(writer, defaultConfig, meterRegistry);
    // 没有配置：
    // - 初始缓存配置（properties.getCaches()）
    // - 事务感知
    // - 运行时缓存创建开关
    return manager;
}
```

`RedisProCacheManager`（第17-62行）继承 `RedisCacheManager`，但构造函数只调用：
```java
super(cacheWriter, defaultCacheConfiguration);
```
没有使用 `RedisCacheManagerBuilder` 来配置：
- `transactionAware()`
- `cacheDefaults()`
- `withInitialCacheConfigurations()`
- `disableCreateOnMissingCache()`

**修复建议**：
1. 使用 `RedisCacheManager.builder(cacheWriter)` 构建 manager，链式配置所有属性。
2. 将 `properties.getCaches()` 映射为 `Map<String, RedisCacheConfiguration>`，传入 `withInitialCacheConfigurations()`。
3. 每个配置项添加 `@PostConstruct` 验证或 `*PropertiesTest` 确保绑定正确。

---

### 3.2 序列化器策略风险

**现象**：`SecureJackson2JsonRedisSerializer` 启用 Jackson default typing，虽然有包前缀限制，但仍是反序列化攻击面。

**根因分析**：

Default typing 意味着 Jackson 会在 JSON 中嵌入 `@class` 字段来恢复类型。如果攻击者能向 Redis 写入恶意 JSON，就可以利用 classpath 中的 gadget chain 触发 RCE。

当前的安全措施：
- 包前缀白名单（默认 `"io.github.davidhlp"`）
- 禁止常见危险包（`java.beans`, `javax.management`, `com.sun.rowset` 等）

但仍存在问题：
1. **白名单模式不如黑名单安全**：用户必须显式配置自己所有的 domain 包，否则反序列化失败。这增加了运维摩擦。
2. **没有版本号**：`CachedValue` 结构变更时，旧缓存数据无法兼容。虽然 `CachedValue` 有 `version` 字段，但序列化器没有使用它。
3. **`typeProperty` 和 `failOnUnknownType` 未使用**：配置文件中声明了，但代码中没有读取。

**代码位置**：
- `SecureJackson2JsonRedisSerializer.java`（未读取到源码，但从配置中可见行为）
- `RedisProCacheProperties.java:100-110` — 序列化器配置
- `RedisProCacheConfiguration.java:50-65` — 序列化器创建

**修复建议**：
1. **默认关闭 default typing**。使用普通 JSON 序列化（不嵌入类型信息），要求用户缓存的值类型单一。
2. 如果必须支持多态，提供一个**显式类型注册表**（`Map<String, Class<?>>`），而不是依赖 default typing。
3. 在 `CachedValue` 外层添加版本信封：
   ```json
   {
     "version": 2,
     "payload": { ... }
   }
   ```
4. 使用 `properties.getSerializer().getTypeProperty()` 和 `isFailOnUnknownType()`。

---

### 3.3 依赖并非真正可选

**现象**：pom.xml 中将 Redisson、Actuator、Micrometer 标记为 `optional`，但代码中在核心路径硬依赖这些类，导致没有这些依赖时无法启动。

**具体硬依赖**：

1. **Micrometer `MeterRegistry`**：
   - `RedisProCacheManager` 构造函数（第23-31行）要求 `MeterRegistry meterRegistry` 参数。
   - `RedisProCacheConfiguration.cacheManager()`（第68-73行）直接注入 `MeterRegistry`。
   - 如果用户没有引入 Micrometer，Spring Boot 不会创建 `MeterRegistry` bean，导致启动失败。

2. **Actuator**：
   - 测试失败根因是 `ProcessorMetrics` bean 创建失败（`CgroupInfo.getMountPoint() NPE`）。
   - 这说明 Actuator 被引入了测试上下文，且与某些容器环境不兼容。

3. **Redisson**：
   - `SyncSupport` 和 `RedisConnectionConfiguration` 中直接引用 Redisson 类。
   - 虽然这些类是 Spring `@Component`，但如果没有 Redisson jar，类加载会失败。

**代码位置**：
- `RedisProCacheManager.java:23-31` — MeterRegistry 硬依赖
- `RedisProCacheConfiguration.java:68-73` — MeterRegistry 注入
- `pom.xml` — optional 声明

**修复建议**：
1. 使用 `ObjectProvider<MeterRegistry>` 或 `@Autowired(required = false)` 使 Micrometer 真正可选。没有时退回到无 metrics 实现。
2. 将 Redisson 相关配置移到独立的 `@Configuration` 类，并用 `@ConditionalOnClass(RedissonClient.class)` 保护。
3. 将 Actuator metrics 配置移到独立类，用 `@ConditionalOnClass(MeterRegistry.class)` 保护。
4. 考虑拆分 artifact：
   - `resicache-spring-boot-starter`：核心功能，仅依赖 Spring Cache + Spring Data Redis
   - `resicache-redisson`：Redisson 分布式锁集成
   - `resicache-micrometer`：Micrometer 指标集成

---

### 3.4 测试基础设施脆弱

**现象**：`./mvnw test` 产生 24 个 error，根因是 Actuator 的 `ProcessorMetrics` 在容器/JVM 环境下抛出 NPE。

**根因分析**：

```text
Error creating bean with name 'processorMetrics'
Cannot invoke "jdk.internal.platform.CgroupInfo.getMountPoint()" because "anyController" is null
```

这个错误发生在 Spring Boot Actuator 尝试读取 cgroup 信息时，某些 JVM/容器环境没有提供完整的 cgroup 信息。这是 Spring Boot 3.2 在特定 JDK 版本上的已知问题。

更严重的问题是：**为什么缓存插件的测试需要加载 Actuator？**

当前的测试上下文可能使用了 `@SpringBootTest` 并加载了整个应用上下文，包括不必要的 Actuator beans。这导致：
- 测试启动慢
- 测试环境依赖主机 cgroup 配置
- 缓存测试与基础设施测试耦合

**修复建议**：
1. 在测试配置中排除 Actuator 自动配置：
   ```java
   @SpringBootTest(properties = "management.metrics.enabled=false")
   ```
   或
   ```java
   @EnableAutoConfiguration(exclude = {MetricsAutoConfiguration.class})
   ```
2. 为缓存集成测试创建专用的 `@TestConfiguration`，只加载必要的 beans。
3. 分离单元测试（不需要 Spring 上下文）和集成测试（使用 Testcontainers Redis）。
4. 确保测试通过后再提 coverage 要求。当前 73.7% 行覆盖率低于期望的 80%+。

---

## 四、与 Spring Cache 集成的边界缺陷

### 4.1 原生 Spring 注解支持是假支持

**现象**：`RedisCacheOperationSource.addSpringNativeCacheOperations()`（第142-160行）声称支持 Spring 原生 `@Cacheable`，但实现为空：

```java
private void addSpringNativeCacheOperations(final Object target, final List<CacheOperation> ops) {
    final Cacheable springCacheable;
    if (target instanceof Method) {
        springCacheable = AnnotatedElementUtils.findMergedAnnotation((Method) target, Cacheable.class);
    } else if (target instanceof Class) {
        springCacheable = AnnotatedElementUtils.findMergedAnnotation((Class<?>) target, Cacheable.class);
    } else {
        return;
    }

    if (springCacheable != null) {
        log.debug("Found Spring @Cacheable annotation on target: {}, forwarding to native handler", target);
        // 注意：这里什么都没做！没有将 springCacheable 转换为 ops.add()
    }
}
```

当方法上只有 Spring 原生 `@Cacheable` 而没有 `@RedisCacheable` 时：
- `RedisCacheOperationSource` 不会为它创建 `RedisCacheableOperation`
- `RedisCacheRegister` 中没有该操作的元数据
- `RedisProCacheWriter` 通过 `buildContext()` 查找时返回 null
- 所有 ResiCache 特有功能（TTL 随机化、Bloom、锁、预刷新）全部不生效
- 但用户仍然在使用 `RedisProCacheWriter`（因为 `CacheManager` 被替换为 `RedisProCacheManager`）

这造成一种**半吊子集成**：用户以为自己在用增强版 Spring Cache，实际上只是用了一个更慢的、带额外序列化开销的 RedisCacheWriter。

**修复建议**：
1. 如果定位为 "仅支持自定义注解"，则明确在文档中声明："要使用 ResiCache 功能，必须使用 `@RedisCacheable` 替代 `@Cacheable`"。
2. 如果定位为 "增强原生 Spring 注解"，则需要：
   - 将 Spring 的 `Cacheable` 注解转换为 `RedisCacheableOperation`（使用全局默认配置）
   - 或提供一种机制，让原生注解也能读取 `resi-cache.*` 的全局/ per-cache 配置
3. 更合理的方案：不要替换 Spring 的 `CacheInterceptor`，而是**扩展**它。让 `RedisCacheableOperation` 继承 `CacheableOperation` 后，Spring 的标准拦截器已经能处理它。ResiCache 只需要在 `RedisCacheWriter` 层面增强，不需要自定义 `RedisCacheInterceptor`。

---

### 4.2 Reactive / Async 支持缺失

**现象**：Spring 6.1+ 原生支持 `Mono`/`Flux` 返回类型的异步缓存（`CacheAspectSupport.ReactiveCachingHandler`），ResiCache 完全没有处理。

`RedisCacheInterceptor`（第27-64行）继承 `CacheInterceptor`，但 `CacheInterceptor.invoke()` 最终会调用 `CacheAspectSupport.execute()`。Spring 6.1+ 在 `execute()` 中检测到 `Mono`/`Flux` 返回类型时，会走 `ReactiveCachingHandler` 路径。

ResiCache 的 `RedisProCacheWriter.retrieve()`（第94-104行）实现：
```java
public CompletableFuture<byte[]> retrieve(String name, byte[] key, Duration ttl) {
    return CompletableFuture.supplyAsync(() -> get(name, key, ttl));
}
```

这只是将同步 GET 包装在 `CompletableFuture` 中，没有真正的异步优化。而且 `supplyAsync` 使用的是 `ForkJoinPool.commonPool()`，对于 I/O 密集型操作不合适。

**修复建议**：
1. 声明 Reactive 支持状态：如果暂不支持，在文档中明确标注 "不支持 Mono/Flux 返回类型"。
2. 如果用户尝试在 `@RedisCacheable` 方法上返回 `Mono`/`Flux`，应该抛出明确的异常或降级到 Spring 原生行为。
3. 真正的 Reactive 支持需要重写 `RedisProCacheWriter` 使用 Reactive Redis 连接（`ReactiveRedisTemplate`）。

---

### 4.3 事务感知未实现

**现象**：`RedisProCacheProperties` 声明了 `transactionAware = false`，但 `RedisProCacheManager` 没有使用它。

Spring 的 `RedisCacheManager` 继承 `AbstractTransactionSupportingCacheManager`，当 `transactionAware = true` 时：
- `put`/`evict` 操作被延迟到事务提交后才真正执行
- 如果事务回滚，缓存操作也会被取消

ResiCache 的 `RedisProCacheManager` 构造函数：
```java
public RedisProCacheManager(RedisProCacheWriter cacheWriter,
                            RedisCacheConfiguration defaultCacheConfiguration,
                            MeterRegistry meterRegistry) {
    super(cacheWriter, defaultCacheConfiguration);  // 没有传入 transactionAware！
}
```

`RedisCacheManager` 有另一个构造函数支持 `boolean allowInFlightCacheCreation`，但没有设置 transactionAware 的入口。

**修复建议**：
1. 使用 `RedisCacheManager.builder(cacheWriter).transactionAware().cacheDefaults(defaultConfig).build()`。
2. 将 `properties.isTransactionAware()` 绑定到 builder。

---

### 4.4 CacheStatistics 重复计算

**现象**：`RedisProCache`（第16-166行）和 `ActualCacheHandler`（第42-384行）各自维护一套统计，导致 metrics 重复或冲突。

`RedisProCache.get()`（第63-76行）：
```java
public ValueWrapper get(Object key) {
    ValueWrapper result = super.get(key);
    if (result != null) { hitCounter.increment(); }
    else { missCounter.increment(); }
}
```

`ActualCacheHandler.handleGet()`（第98-120行）：
```java
CachedValue cachedValue = (CachedValue) valueOperations.get(...);
if (isCacheHit(cachedValue)) {
    statistics.incHits(context.getCacheName());  // Spring Data Redis 的统计
} else {
    statistics.incMisses(context.getCacheName());
}
```

`RedisProCache.get()` 调用 `super.get()`，即 `RedisCache.get()`，后者会调用 `cacheWriter.get()`。`RedisCacheWriter.get()` 在 Spring Data Redis 中也会更新统计。

所以一次缓存命中可能触发：
1. Spring Data Redis 内部统计（通过 `CacheStatisticsCollector`）
2. `ActualCacheHandler` 的统计
3. `RedisProCache` 的 Micrometer 统计

三套统计同时工作，数据不一致风险高。

**修复建议**：
1. 统一统计入口：要么完全依赖 Spring Data Redis 的 `CacheStatisticsCollector`，要么完全自己实现。
2. 如果选择 Micrometer 作为标准，禁用 `CacheStatisticsCollector` 的重复计数，或只在 `RedisProCache` 层计数，不在 writer handler 层计数。

---

## 五、修复路线图

基于以上分析，按优先级和依赖关系给出修复建议。

### P0：信任与正确性（必须修复，否则不能发布）

| # | 问题 | 修复动作 | 验证方式 |
|---|------|---------|---------|
| P0-1 | 测试全部通过 | 隔离 Actuator，修复测试配置 | `./mvnw test` 0 failure/error |
| P0-2 | Key 解析与 Spring 不一致 | 用 `(Method, Class)` 替代生成后的 key 作为元数据查找键；或在 writer 层使用 Spring 的 `CacheOperationExpressionEvaluator` | 集成测试：比较 Spring key 与 ResiCache 元数据查找 key |
| P0-3 | Bloom Filter 语义错误 | 明确文档声明 Bloom 只防 Redis 查询（短期）；或将 Bloom 检查移至 `RedisProCache.get(key, Callable)` 层（长期） | 测试：Bloom negative 时断言用户方法不被调用 |
| P0-4 | 缓存命中写放大 | 移除 `processCacheHit` 中的 `withAccessUpdate` 和 `updateTtlIfExists` | 测试：命中时断言无 Redis 写命令 |
| P0-5 | Sync 锁无法单飞 | 正确实现 `RedisCacheWriter.get(name, key, valueLoader, ttl, tti)` | 并发测试：10 线程 miss，断言方法调用 1 次 |

### P1：配置与生产就绪（修复后可进入生产试用）

| # | 问题 | 修复动作 |
|---|------|---------|
| P1-1 | 配置属性未生效 | 使用 `RedisCacheManager.builder()` 完整配置；每个 property 添加配置绑定测试 |
| P1-2 | 事务感知缺失 | 绑定 `transactionAware` 到 builder |
| P1-3 | 序列化器风险 | 默认关闭 default typing；支持版本信封 |
| P1-4 | 依赖非真正可选 | 使用 `ObjectProvider` + `@ConditionalOnClass` 隔离可选依赖 |
| P1-5 | 原生注解假支持 | 要么完全支持（转换 Spring 注解为元数据），要么明确文档声明不支持 |

### P2：功能诚实与深度（修复后功能可信）

| # | 问题 | 修复动作 |
|---|------|---------|
| P2-1 | PreRefresh 实为提前过期 | 重命名为 `earlyExpiration`；如要做真 refresh-ahead，需捕获 loader callback |
| P2-2 | Bloom 假阳性污染 | 移除 miss 时的 `bloomSupport.add()`；考虑支持删除的 Bloom 变体 |
| P2-3 | 统计重复计算 | 统一统计入口，要么 Spring Data Redis 原生，要么纯 Micrometer |
| P2-4 | Reactive 支持状态不明 | 文档声明不支持；或添加 Reactive Redis 实现 |

### P3：市场级插件打磨

| # | 问题 | 修复动作 |
|---|------|---------|
| P3-1 | Spring Boot 基线升级 | 升级到 Boot 3.4.x；定义兼容性矩阵 |
| P3-2 | 性能基准测试 | 与纯 Spring Data Redis Cache 对比 hit/miss 路径 overhead |
| P3-3 | 拆分 artifact | `resicache-core`, `resicache-redisson`, `resicache-micrometer` |
| P3-4 | 示例应用 | 提供 minimal / cluster / sentinel / actuator 示例 |

---

## 六、关键决策建议

1. **产品定位决策**：ResiCache 到底是 "Spring Cache 的透明增强插件" 还是 "一套独立的缓存注解框架"？
   - 如果是前者，必须让原生 `@Cacheable` 也能享受增强（至少通过全局配置）。
   - 如果是后者，需要明确告知用户迁移成本。

2. **架构决策**：当前自定义 `RedisCacheInterceptor` + `AnnotationHandler` 链 + `RedisCacheRegister` 的架构过于复杂，且与 Spring 的 key/condition 评估路径平行。建议考虑**更轻量的集成方式**：
   - 保留 `RedisCacheableOperation extends CacheableOperation`
   - 让 Spring 的标准 `CacheInterceptor` 处理注解解析和 AOP
   - 只在 `RedisProCacheWriter` 中通过 `CacheOperation` 的扩展属性获取元数据
   - 元数据查找键使用 `AnnotatedElementKey` 或 operation 的 hashCode，而不是生成的缓存 key

3. **功能裁剪决策**：在核心语义未修复之前，不建议添加 L1/L2 本地缓存、真正的 background refresh 等高级功能。先把 advertised 功能做正确、做稳定。

---

*报告结束*
