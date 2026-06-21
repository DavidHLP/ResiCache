---
title: 分布式锁(200)
type: mechanisms
tags:
  - mechanism
  - 分布式锁
  - 防击穿
  - Redisson
  - SYNC_LOCK
related: [cache-breakdown, handler-result-control, context-data-flow]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/breakdown/SyncLockHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/breakdown/DistributedLockManager.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/protection/breakdown/SyncSupport.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 分布式锁(HandlerOrder 200)

责任链**第二档**。当热点 key 过期、上千请求同时回源时,只让一个请求去查 DB 回填,其余等待——防 [[cache-breakdown|缓存击穿]]。基于 Redisson 分布式锁,锁逻辑**内聚**在本 handler。

## 锁内聚设计

旧设计里锁准备与锁执行分离(SyncLockHandler 只造 LockContext,ActualCacheHandler 执行锁),耦合且难维护。现设计:**SyncLockHandler 直接把整条剩余链包进锁里执行**,锁逻辑完全内聚:

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/breakdown/SyncLockHandler.java:67`

```java
protected HandlerResult doHandle(CacheContext context) {
    LockContext lockContext = createLockContext(context);
    if (!lockContext.requiresLock()) {
        return HandlerResult.continueChain();          // 不需要锁,放行
    }
    context.setAttribute("sync.lock.acquired", true);  // 标记,防下游重复加锁
    CacheResult result = syncSupport.executeSync(
        lockContext.lockKey(),
        () -> executeChainInLock(context),             // ← 锁内跑剩余 handler
        lockContext.timeoutSeconds());
    return HandlerResult.terminate(result);            // 锁内结果即最终结果
}

private CacheResult executeChainInLock(CacheContext context) {
    if (getNext() != null) {
        HandlerResult r = getNext().handle(context);
        return r.result() != null ? r.result() : CacheResult.success();
    }
    return CacheResult.success();
}
```

### 为什么不会重复加锁

`executeChainInLock` 内调 `getNext().handle(context)`。因为已设了 `sync.lock.acquired=true`,本 handler 的 `shouldHandle` 会先检查它:

```java
protected boolean shouldHandle(CacheContext context) {
    if (context.getAttribute("sync.lock.acquired", false)) return false;  // 已加锁,跳过
    if (context.getCacheOperation() == null || !context.getCacheOperation().isSync()) return false;
    CacheOperation op = context.getOperation();
    return op == GET || op == PUT_IF_ABSENT || op == PUT;
}
```

## 超时解析

`resolveTimeout` 按优先级取锁等待超时(见 `SyncLockHandler.java:132`):

1. 注解 `syncTimeout`(>0 时生效);
2. 否则全局 `resi-cache.sync-lock.timeout` + `unit`;
3. 都没有 → 默认 `DEFAULT_LOCK_TIMEOUT = 10`(秒)。

## 底层锁:DistributedLockManager

`src/main/java/io/github/davidhlp/spring/cache/redis/protection/breakdown/DistributedLockManager.java:22`

基于 Redisson,实现 `LockManager` SPI。`@ConditionalOnClass(RedissonClient.class)`——classpath 无 Redisson 时该 bean 不生效。

```java
public Optional<LockHandle> tryAcquire(String key, long timeoutSeconds) throws InterruptedException {
    String lockKey = properties.getSyncLock().getPrefix() + key;
    RLock lock = redissonClient.getLock(lockKey);
    long leaseTimeSeconds = Math.max(MIN_LEASE_TIME_SECONDS,        // ≥ 10s
                                     timeoutSeconds * LEASE_TIMEOUT_MULTIPLIER); // ×3
    boolean acquired = lock.tryLock(timeoutSeconds, leaseTimeSeconds, TimeUnit.SECONDS);
    ...
}
```

关键常量(`DistributedLockManager.java:25` 起):

| 常量 | 值 | 含义 |
|---|---|---|
| `LEASE_TIMEOUT_MULTIPLIER` | 3 | leaseTime = wait × 3 |
| `MIN_LEASE_TIME_SECONDS` | 10 | 最小持锁时间,防 wait 太小导致锁提前释放 |
| `MAX_UNLOCK_RETRIES` | 3 | 释放失败重试次数 |
| `UNLOCK_RETRY_INTERVAL_MS` | 100 | 重试间隔 |

`leaseTime` 自动算大的意义:即使持有者崩溃,锁也会在 lease 到期后自动释放(避免死锁),`MIN_LEASE_TIME_SECONDS=10` 保证即使 wait 很小也有足够持锁窗口。

### RedissonLockHandle.close()

释放时三重保险(`DistributedLockManager.java:101`):

1. `AtomicBoolean closed` CAS 保证只释放一次;
2. `isHeldByCurrentThread()` 确认本线程持锁;
3. 释放失败重试最多 3 次(间隔 100ms),仍失败则记 error 放弃(锁会随 lease 到期自动释放)。

## 自定义锁后端

`LockManager` 是 `breakdown` 包内的接口,`DistributedLockManager`(Redisson)是默认实现,`@ConditionalOnClass(RedissonClient.class)` 自动装配。要换其他锁后端(如 Zookeeper),实现 `LockManager` 并声明为 `@Bean` / `@Component`,配合 `@ConditionalOnMissingBean` 顶替默认实现。`getOrder()` 返回 0,用于多实现并存时排序。

## 配置

全局(`resi-cache.sync-lock.*`):

```yaml
resi-cache:
  sync-lock:
    prefix: "resi-cache:lock:"
    timeout: 10s
```

注解级(`@RedisCacheable`):

```java
@RedisCacheable(value = "hotdata", key = "#id",
    sync = true,            // 开启击穿保护
    syncTimeout = 5)        // 锁等待超时(秒)
```

## 何时触发

只有 `sync=true` 且操作是 `GET` / `PUT` / `PUT_IF_ABSENT` 才加锁。非 sync 的普通读写不经此 handler——锁是有开销的,只给真正需要击穿保护的热点 key 用。

## 相关

- [[cache-breakdown]] —— 击穿的定义与本机制的关系
- [[handler-result-control]] —— CONTINUE/TERMINATE 在锁内聚中的用法
- [[context-data-flow]] —— `sync.lock.acquired` 属性标记
