---
title: 上下文数据流
type: architecture
tags:
  - architecture
  - CacheInput
  - CacheContext
  - CacheOutput
  - 数据流
  - 属性袋
related: [handler-result-control, cache-lifecycle, chain-of-responsibility]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/model/CacheInput.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/model/CacheContext.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/model/CacheOutput.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 上下文数据流(CacheInput → CacheContext → CacheOutput)

责任链里 6 个 handler 共享一次请求的数据。这些数据被封装进一个三件套:**不可变输入、贯穿全链的可变上下文、承载结果的可变输出**。理解它,才能理解 handler 之间怎么「传话」。

## 三件套

```
CacheInput  ──构造──▶  CacheContext  ──持有──▶  CacheOutput
 (不可变)               (可变,贯穿全链)         (可变,承载决策结果)
```

### CacheInput —— 不可变请求参数

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/model/CacheInput.java`

请求的「身份证」,一旦构造不再改:

- `operation` —— `CacheOperation`(GET/PUT/PUT_IF_ABSENT/REMOVE/CLEAN)
- `cacheName` / `redisKey` / `actualKey` —— 缓存名与键(带前缀的完整 key vs 去前缀的实际 key)
- `valueBytes` / `deserializedValue` —— 待写值(字节数组 / 反序列化对象)
- `ttl` —— 调用方传入的 TTL(可能为 null,由 [[ttl-jitter]] 决定)
- `cacheOperation` —— `RedisCacheableOperation`,注解解析出的运行时配置(sync、useBloomFilter、variance 等),见 [[annotations]] / [[operations]]

### CacheContext —— 可变贯穿上下文

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/model/CacheContext.java:1`

组合 `CacheInput`(只读视图)+ `CacheOutput`(可变)+ 一个 **`attributes` 属性袋**(`ConcurrentHashMap`)。它是 handler 间传递中间状态的主通道:

```java
public <T> T getAttribute(String key);               // 取
public <T> T getAttribute(String key, T defaultValue); // 取(带默认)
public void setAttribute(String key, Object value);  // 存
public void removeAttribute(String key);
public boolean hasAttribute(String key);
```

#### 约定的属性键(AttributeKey)

框架预定义了一批键,跨 handler 协作:

| 键 | 写入者 | 读取者 | 用途 |
|---|---|---|---|
| `PREFETCHED_CACHED_VALUE` | [[early-expiration]] | [[cache-lifecycle]] 的 ActualCache | 复用预取值,避免双重 Redis GET |
| `EARLY_EXPIRATION_SKIPPED` | [[early-expiration]] | ActualCacheHandler | 同步提前过期 → 链尾返回 miss |
| `sync.lock.acquired` | [[breakdown-lock]] | SyncLockHandler 自身 | 防止锁内嵌套重复加锁 |
| `bloom.postProcess` | [[bloom-filter]] | BloomFilterHandler 后置 | 标记链结束后要把 key 加进过滤器 |
| `earlyExpiration.decision` | [[early-expiration]] | 后置/观察 | 提前过期决策记录 |

还有 `isSkipRemaining()` / `markSkipRemaining()` 控制链路短路,详见 [[handler-result-control]]。

### CacheOutput —— 可变结果载体

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/model/CacheOutput.java:20`

各 handler 把「我这一步算出来的东西」写进 output,供下游消费:

- **TTL 段**:`shouldApplyTtl` / `finalTtl` / `ttlFromContext` —— [[ttl-jitter]] 计算后写入,ActualCache 落盘时读。
- **值段**:`storeValue` —— [[null-value]] 把真实值或空值占位放这。
- **提前过期段**:`earlyExpirationCheckEnabled`。
- **控制段**:`skipRemaining`(默认 false)。
- **CLEAN 专用**:`keyPattern`。
- **最终结果**:`finalResult`(`CacheResult`)—— ActualCacheHandler 写入,链的产出。

## 数据流动示例:一次 PUT

```
RedisProCacheWriter.buildContext(PUT, …)
        │  构造 CacheInput(含 ttl=null, cacheOperation 带 randomTtl=true, variance=0.2)
        ▼
  CacheContext.of(input)
        │
        ▼
[BloomFilter]  shouldHandle=true(开启布隆) → setAttribute("bloom.postProcess", true) → continue
        ▼
[SyncLock]     shouldHandle=false(非 sync) → 放行
        ▼
[TTL]         shouldHandle=true → output.finalTtl = jitter(baseTtl, 0.2) → continue
        ▼
[NullValue]   shouldHandle=true → output.storeValue = wrap(value) → continue
        ▼
[ActualCache] 读 output.finalTtl + output.storeValue → SET → output.finalResult = success → terminate
        ▼
  链结束 → BloomFilterHandler.afterChainExecution → 把 key 加进布隆过滤器
```

## 为什么这样设计

- **input 不可变**:请求参数在链中不会被误改,每次 handler 拿到的「事实」一致。
- **context 可变 + 属性袋**:handler 之间是松耦合的(不直接调用彼此),靠属性袋传递中间产物。这让 [[add-protection-handler|新增 handler]] 不用改动现有 handler。
- **output 承载结果**:把「计算」和「执行」分离——上游 handler 只负责算(算 TTL、包装值),最终由链尾 `ActualCacheHandler` 统一执行,避免多处写 Redis。

## 相关

- [[handler-result-control]] —— 属性标记如何配合 `HandlerResult` 控制链路
- [[cache-lifecycle]] —— 这些数据在 GET/PUT 中如何被使用
- [[chain-of-responsibility]] —— 上下文穿过的链
