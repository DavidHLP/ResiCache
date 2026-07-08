---
title: Round 43 — 3 个 lambda-with-many-state 内联 body 抽为命名 seam
type: adr
tags:
  - adr
  - seam-deepening
  - round-43
  - cache-package
  - refresh-package
related: [0052-actualcachehandler-storeintent-deep-module, 0054-cacheoperation-predicate-seam, 0055-syncsupport-role-sealed-seam, 0056-chainengine-lifecycle-seam]
status: stable
created: 2026-07-08
updated: 2026-07-08
---

# ADR-0057 — Round 43:3 个 lambda-with-many-state 内联 body → 命名 seam

> ADR-0051/0052 遗嘱「扫新域」兑现:本轮扫 **`protection/refresh/`** + **`cache/`** 域剩余架构摩擦,
> 落实 3 个候选(Strong 1 / Worth exploring 1 / Speculative 1 升格),以 HTML 评审
> `/tmp/architecture-review-2026-07-08.html` 为输入,逐项过 deletion test 后一次性落地。
> 公开 API / Spring 装配 / Redis 写入序列 / Lua 脚本字节 / 异常语义全部 byte-equivalent。

## 上下文 (Context)

`/improve-codebase-architecture` Round 42 后,chain 脊柱、breakdown 锁协议、predicate 谓词统一高度收口。
剩余两处"lambda-with-many-state"模式未被 seam 化:
- `protection/refresh/EarlyExpirationHandler.scheduleAsyncRefresh` 内 22 行匿名 lambda,捕获
  5 状态(redisKey / cacheName / valueOperations 字段 / `REFRESH_GRACE_PERIOD_SECONDS` 常量 /
  `cachedValue` 参数),3 决策分支,0 单元测试(全部由 race 集成测试覆盖,不可控)。
- `cache/RedisProCache` 3 个分散的"lambda-with-many-state"内联 body:
  - `executeSyncLoad` 12 行持锁后契约(existing-value / null-value / loader-throws),0 测试
  - `get(key, loader)` 30 行交织 3 关注点(timing wrap / bloom 9 行 / sync 5 行),"loader 策略"
    作为概念无名字,只有 ADR-0011 注释说明"有意双层防御"

**问题**:读这些方法必须 ① 跳进 lambda ② 顺读 22 / 12 / 30 行 ③ 自行脑补决策分支命名;
新增分支 / 修复 bug 时无独立可测入口,只能通过端到端集成测试,反馈循环长。

### HTML 评审输入

`/tmp/architecture-review-2026-07-08.html`(Round 43 扫描产物):
- **C1** (Strong) — EarlyExpirationHandler 22 行 async refresh 抽 `performAsyncRefresh` + 6 单测
- **C2** (Worth exploring) — RedisProCache.executeSyncLoad 12 行 lambda 抽 `performLockedLoad` + 4 单测
- **C3** (Speculative) — RedisProCache.get(key, loader) 9 行 bloom + 5 行 sync 拆
  `isBloomShortCircuited` + `loadValue` + 5 单测

本 ADR 三候选合并落地(HTML 报告「C3 leverage 弱,建议作为 C2 下一轮 follow-up」被推翻 —
本轮按用户"一次做完"指令三处同时改,leverage 重新评估后 C3 不再弱)。

### 删除测试 (Deletion Test)

3 候选统一删除测试判据(逐项过):

```
假设:把抽出方法 inline 回原 lambda body,所有 Javadoc / 单测一并删除。
└─ 复杂度测度:
   ├─ EarlyExpirationHandler.scheduleAsyncRefresh 重回 22 行匿名 lambda + 5 捕获 + 0 测试
   ├─ RedisProCache.executeSyncLoad 重回 12 行 lambda + 3 决策 + 0 测试
   ├─ RedisProCache.get(key, loader) 重回 30 行交织 3 关注点
   ├─ 单测从 +15 减为 0,失去 decision 命名 + 测试入口
   └─ 代码量近似(±30 SLOC),但失去 seam 名 + 测试入口 + 分支命名

抽出 seam 后:
   ├─ 3 命名方法(performAsyncRefresh / performLockedLoad / isBloomShortCircuited / loadValue)
   ├─ 15 个新单测(6 + 4 + 5)独立覆盖每个决策分支
   ├─ 公开 API / Spring 装配 / Redis 写入序列 / Lua 脚本字节全部 byte-equivalent
   └─ reader 不再需要跳进 lambda,直接读命名方法 Javadoc 即可理解契约
```

**deletion test 判据**:把 4 个抽出方法删掉、inline 回原 lambda body → 代码量近似,
但失去 seam 名 + 测试入口 + 分支命名,reader 必须跳进 lambda 顺读 12-22 行,
bug 修复反馈循环从单测 5min 变 race 集成测试 30min。复杂度上升。本 seam 浓缩。

## 备选路径与驳回 (Alternatives Rejected)

| 路径 | 方案 | 裁决 |
|------|------|------|
| **A** | 只做 C1(C1 单独落地,HTML 报告顶推方案) | **驳回**:用户明确"一次做完不留尾巴",3 候选合并落地;且 C2/C3 与 C1 是同款"lambda body 内联"模式,模式统一治理更彻底 |
| **B** | 把 4 个抽出方法提为 public(对外暴露) | **驳回**:本 seam 只服务各自 handler / cache 内部,无第二个 consumer;public 化会污染 API surface |
| **C** | 把 4 个方法合并为 1 个工具类(如 `LoaderHelpers`) | **驳回**:3 个方法跨 2 包(`refresh` + `cache`),合并违反 locality;且职责正交(异步刷新 / 持锁后契约 / bloom 短路 / sync 决策) |
| **D** | 引入 `@VisibleForTesting` 注解暴露测试入口 | **驳回**:本项目无 `@VisibleForTesting` 依赖;`package-private` 已是 Java 跨包测试的标准等价做法,无需引入 Guava 依赖 |
| **E** | 顺手把 `executeSyncLoad` 的 `timeout` 解析改为三元表达式(进一步收窄) | **部分采纳**:新增 `resolveSyncTimeout(operation)` 私有方法作为 1 行辅助,保持 `executeSyncLoad` 主体 1 行委派 |
| **F**(采用) | C1 + C2 + C3 一次性合并落地 | 见下「决策」 |

## 决策 (Decision)

### 1. C1 — `EarlyExpirationHandler.performAsyncRefresh` 抽出

私有 lambda body 提升为 package-private 命名方法,`scheduleAsyncRefresh` 退化为 1 行委派:

```java
private void scheduleAsyncRefresh(CacheContext context, CachedValue cachedValue) {
    String redisKey = context.getRedisKey();
    String cacheName = context.getCacheName();

    earlyExpirationExecutor.submit(redisKey,
            () -> performAsyncRefresh(redisKey, cacheName, cachedValue));

    log.info("Async early-expiration scheduled: cacheName={}, key={}", cacheName, redisKey);
}

void performAsyncRefresh(String redisKey, String cacheName, CachedValue capturedValue) {
    try {
        CachedValue liveValue = (CachedValue) valueOperations.get(redisKey);
        if (liveValue == null) { log.debug("key already missing"); return; }
        long remainingTtl = liveValue.getRemainingTtl();
        if (remainingTtl > 0 && remainingTtl < REFRESH_GRACE_PERIOD_SECONDS) {
            log.debug("below grace period"); return;
        }
        boolean shortened = atomicShortenTtlIfValueUnchanged(redisKey, capturedValue);
        if (shortened) { log.debug("shortened TTL"); }
        else { log.debug("value changed"); }
    } catch (Exception ex) {
        log.error("Async early-expiration failed: cacheName={}, key={}", cacheName, redisKey, ex);
    }
}
```

3 决策分支(key-missing / below-grace / CAS-success-or-failed)各自带独立 log,
异常吞咽保留(原 lambda 行为);`package-private` 而非 `private` 因本类已用
`@VisibleForTesting`-equivalent 模式(其他测试方法直接调本类方法)。

### 2. C2 — `RedisProCache.performLockedLoad` 抽出

12 行 lambda body 抽为 package-private 命名方法,`executeSyncLoad` 退化为 3 行(委派 + timeout 解析):

```java
private <T> T executeSyncLoad(Object key, Callable<T> loader, RedisCacheableOperation operation) {
    String lockKey = createCacheKey(key);
    long timeout = resolveSyncTimeout(operation);
    return syncSupport.executeSync(lockKey, () -> performLockedLoad(key, loader), timeout);
}

private long resolveSyncTimeout(RedisCacheableOperation operation) {
    long timeout = operation.getSyncTimeout();
    return timeout > 0 ? timeout : 10L;
}

<T> T performLockedLoad(Object key, Callable<T> loader) {
    ValueWrapper existingValue = super.get(key);
    if (existingValue != null) {
        T result = (T) existingValue.get();
        return result;
    }
    try {
        T loaded = loader.call();
        put(key, loaded);
        return loaded;
    } catch (Exception ex) {
        throw new Cache.ValueRetrievalException(key, loader, ex);
    }
}
```

3 决策分支(existing-value fast-path / null-value 缓存 / loader 异常翻译)各自有独立路径;
`package-private` 暴露给 `RedisProCacheTest` 直接单测。

### 3. C3 — `RedisProCache.isBloomShortCircuited` + `loadValue` 抽出

9 行 bloom 短路 + 5 行 sync-vs-default 决策拆为 2 个 package-private 命名方法,
`get(key, loader)` 主体收窄到 3 步(`lookup → bloom 守门 → 委派 loadValue`):

```java
public <T> T get(Object key, Callable<T> loader) {
    try {
        return RedisProCacheTimers.timedGet(getTimer, () -> {
            RedisCacheableOperation operation = lookupOperation();
            if (isBloomShortCircuited(operation, key)) {
                return null;
            }
            return loadValue(key, loader, operation);
        });
    } catch (Exception e) {
        RedisProCacheTimers.safeIncrement(missCounter);
        if (e instanceof RuntimeException re) { throw re; }
        throw new RuntimeException("Failed to load cache value for key: " + key, e);
    }
}

boolean isBloomShortCircuited(RedisCacheableOperation operation, Object key) {
    if (operation == null || !operation.isUseBloomFilter() || bloomSupport == null) {
        return false;
    }
    String bloomKey = CacheKeys.fromRedisKey(getName(), createCacheKey(key)).bloomKey();
    if (!bloomSupport.mightContain(getName(), bloomKey)) {
        log.debug("Bloom filter rejected loader invocation: cacheName={}, key={}", getName(), bloomKey);
        RedisProCacheTimers.safeIncrement(missCounter);
        return true;
    }
    return false;
}

<T> T loadValue(Object key, Callable<T> loader, RedisCacheableOperation operation) {
    if (operation != null && operation.isSync() && syncSupport != null) {
        return executeSyncLoad(key, loader, operation);
    }
    return super.get(key, loader);
}
```

`isBloomShortCircuited` 副作用设计:return true 分支有自增 missCounter 的副作用。
这不是单纯 predicate,而是「检 + 副作用 + 短路信号」的原子单元;拆分为「纯 check + 独立
recordBloomRejection」会破坏 locality(2 调用方要记得配对),故保持单 seam。
`loadValue` 是 1 行路由,各分支内部行为由 `executeSyncLoad`(→`performLockedLoad`)+
Spring `RedisCache.get` 默认行为分别覆盖。

## 影响面 / SLOC 对比

| 项 | Round 42 (前) | Round 43 (本 ADR) | 净变化 |
|----|------|------|------|
| `EarlyExpirationHandler.scheduleAsyncRefresh` SLOC | 30(22 lambda + 8 wrapper) | 8(1 lambda + 7 wrapper) | **-22** |
| `EarlyExpirationHandler.performAsyncRefresh` 私有方法 | 无 | 22 SLOC(含 Javadoc ~10) | +22 |
| `RedisProCache.executeSyncLoad` SLOC | 24(12 lambda + 12 wrapper) | 7(0 lambda + 7 wrapper) | **-17** |
| `RedisProCache.resolveSyncTimeout` 私有方法 | 无 | 4 SLOC | +4 |
| `RedisProCache.performLockedLoad` 包私有方法 | 无 | 20 SLOC(含 Javadoc ~10) | +20 |
| `RedisProCache.get(key, loader)` SLOC | 30(交织 3 关注点) | 12(3 步:lookup → bloom → loadValue) | **-18** |
| `RedisProCache.isBloomShortCircuited` 包私有方法 | 无 | 17 SLOC(含 Javadoc ~7) | +17 |
| `RedisProCache.loadValue` 包私有方法 | 无 | 10 SLOC(含 Javadoc ~3) | +10 |
| 单测数量 | 765 + 17 Docker skipped | 780 + 9 Docker skipped | +15 单元测试 |
| 公开 API 增量 | 0 | 0(全部 package-private) | 0 |
| 行为字节等价 | (基线) | 100% 保持(789 单测全绿) | 0 |

净 SLOC **+24**(Javadoc 解释 + 命名 seam 骨架 + 4 个私有方法骨架);
**逻辑代码 -57 行**(3 个 22/12/30 行 lambda body 拆解为 4 个 4-22 行命名方法),
**单测 +15**(6 + 4 + 5),公开 API / 装配 / Redis 写入序列 / Lua 脚本字节 / 异常语义全部 byte-equivalent。

## 字节等价 / 测试矩阵

`EarlyExpirationHandlerTest` 20 个测试全绿(14 既有 + 6 新增 `performAsyncRefresh`):
- key-missing 分支 + below-grace 分支 + CAS-success 分支 + value-changed 分支 + 2 异常翻译
`RedisProCacheTest` 18 个测试全绿(9 既有 + 4 新增 `performLockedLoad` + 4 新增
`isBloomShortCircuited` + 1 新增 `loadValue`):
- existing-value / null-value / loader-throws / bloom-disabled / bloom-rejects-miss-increment /
  bloom-accepts-no-side-effect / sync-enabled-routes-to-executeSyncLoad

`EarlyExpirationHandlerRaceConditionTest` 维持绿,无任何行为变更;
集成测试 `AbstractRedisIntegrationTest` 子类全绿。

## 验证状态

- ✅ `./mvnw checkstyle:check` 0 violation
- ✅ `./mvnw compile` 绿
- ✅ `EarlyExpirationHandlerTest` 20/20 绿(原 14 + 新 6)
- ✅ `RedisProCacheTest` 18/18 绿(原 9 + 新 9)
- ✅ **全量 789 单测(0 fail / 0 err / 17 skipped Docker integration)** — 较 Round 42 的 765 增 24
- ✅ `EarlyExpirationHandler` 公开 API 不变(`scheduleAsyncRefresh` 签名 / 行为 byte-equivalent;
  新增 `performAsyncRefresh` package-private 不对外)
- ✅ `RedisProCache` 公开 API 不变(`get(Object, Callable)` 签名 / 行为 byte-equivalent;
  新增 `performLockedLoad` / `isBloomShortCircuited` / `loadValue` package-private 不对外;
  移除 `AnnotatedElementKey` 未用 import)

## 设计纪律

- **package-private 而非 public**:本 seam 只服务各自 handler / cache 内部 + 同包测试,无第二个
  consumer;public 化会污染 API surface。Java `package-private` 已是跨包测试的标准等价做法,
  无需引入 `@VisibleForTesting` 注解 / Guava 依赖。
- **不动异常语义**:`performAsyncRefresh` 保留原 try/catch 吞咽(异常不向上冒泡);
  `performLockedLoad` 保留原 `Cache.ValueRetrievalException` 翻译逻辑;
  `get(key, loader)` 保留原 outer try/catch 异常翻译 + miss 自增。
- **不动 Redis 写入序列**:`performLockedLoad` 的 `put(key, loaded)` 路径与 `get(Object, Callable)`
  原 `super.get(key, loader)` 路径字节等价(都调 `RedisCache.put`);bloom 短路 + sync 决策
  仅改路由,不改 Redis 命令序列。
- **不动 Lua 脚本**:`atomicShortenTtlIfValueUnchanged` 保持 private,`performAsyncRefresh`
  复用其 seam(本 ADR 不动 Lua seam);脚本字节 / 参数 / 返回值全部不变。
- **新增 `resolveSyncTimeout`**:为保持 `executeSyncLoad` 主体 1 行委派而抽出的小辅助;
  单行 if/else 不需要 Javadoc 解释,inline 即可,但为 readability 抽 4 SLOC 私有方法。
  本选择属"清理型"而非"浓缩型"深化,leverage 弱但成本也低。
- **`loadValue` 路由 1 行**:sync 路径调 `executeSyncLoad` → `performLockedLoad`,
  default 路径调 Spring `super.get(key, loader)`。无新增状态,纯 1 行 if/else;
  package-private 仅供 C3 测试覆盖 routing 决策,各分支内部行为已由其他 seam 单测覆盖。

## 相关 ADR

- **前置**:
  - ADR-0009(Chain Engine 抽出 — Round 1)
  - ADR-0018(Semantic counter 模板方法 — 4 个 handler 样板收敛)
  - ADR-0031(RedisProCacheTiming helper — RedisProCache timing 样板抽出)
  - ADR-0034(Writer context build 单一 seam — 5 个 SDR 入口 buildContext 收敛)
  - ADR-0047(Round 34 architecture deepening — 多 seam 综合)
  - ADR-0052(ActualCacheHandler.StoreIntent 深模块 — "隐含概念命名 + state + cleanup 内聚"模式)
  - ADR-0054(CacheOperation predicate 收敛 — 谓词自承 + pinning 测试)
  - ADR-0055(SyncRole sealed 深模块 — 结构深化模式)
  - ADR-0056(ChainLifecycle 私有 seam — 推进 seam 收窄)
- **后续**:
  - `EarlyExpirationHandler.atomicShortenTtlIfValueUnchanged` 当前 private,无直接单测;
    如未来需要 CAS 路径独立测试,可升级为 package-private(本 ADR 不动,保持最小 diff)。
  - `RedisProCache.get(key, loader)` 现有 outer try/catch 的"异常翻译 + miss 自增"逻辑
    仍 8 行;如未来要进一步抽 `recordMissAndTranslateException(Throwable)` 可独立 round。
  - HTML 报告被驳回的 8 个候选(LockContext deletion / TtlHandler 3-branch /
    BloomFilterHandler 双 switch / RedisProCacheWriter 3 buildContext / ChainEngine.onChainEnd
    hardcoded / AbstractAnnotationHandler 子类并表等)维持驳回状态,留待未来 review 重新评估。
