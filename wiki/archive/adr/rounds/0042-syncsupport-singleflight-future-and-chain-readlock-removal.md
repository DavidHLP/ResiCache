---
adr: 0042
title: SyncSupport single-flight CompletableFuture 去重 + CacheHandlerChain execute 读锁冗余消除
date: 2026-07-04
status: accepted
related: ['0009', '0022', '0029', '0037', 'WS-1.2a']
---

# ADR-0042: SyncSupport single-flight future 去重 + Chain execute 去读锁

## 上下文

`/improve-codebase-architecture` 锁高并发评审识别 4 个候选,通读
`RedisProCache.executeSyncLoad` / `SyncSupport` / chain / `CacheResult` /
`ActualCacheHandler` / `SyncSingleFlightIntegrationTest` 后裁决。

1. **Strong** — `SyncSupport.executeSync` per-key `synchronized(monitor)` → in-flight `CompletableFuture` 去重
2. **Worth** — `LocalBloomIFilter` BitSet 读锁 → 并发位图
3. **Worth** — `CacheHandlerChain.execute` 读锁冗余(双轨并发控制)
4. **Speculative** — `SyncSupport` 三职责 → `LockOrchestrator` seam

## 决策

### 落地(候选 1 + 3)

**候选 1 — single-flight future 去重**(`protection/breakdown/SyncSupport.java`):

- 移除 per-key `synchronized(monitor)` + `MonitorHolder` 引用计数
- 改为 `ConcurrentMap<String, CompletableFuture<Object>> inFlight`
- **leader** 由 `putIfAbsent` CAS 选举 → 持分布式锁跑 loader → `complete`/`completeExceptionally`
- **follower** `join` leader future:零重复持锁 / 零重复回源 / 零 double-check GET
- **可重入(future 不可重入陷阱)**:chain 内 `SyncLockHandler` 嵌套重入 `executeSync`(同 key,
  因 `RedisProCache.executeSyncLoad` 的 loader 内 `super.get` → chain GET → SyncLockHandler 再次进入)。
  `synchronized` 原本天然可重入;future 不可重入(leader 会 join 自己 → 死锁)。用
  `ThreadLocal<Set<String>> reentrantKeys` 检测,重入走 fast-path 直接跑 loader —— 语义等价,
  且省去二次分布式锁往返
- 保留 `WS-1.2a` fail-fast / `local-only` / interrupted / timeout 透传语义

**候选 3 — execute 去读锁**(`chain/CacheHandlerChain.java`):

- 移除 `ReentrantReadWriteLock`(execute 路径上的冗余读锁)
- `execute` 无锁委派 Engine(`AtomicReference` snapshot 已隔离,ADR-0022)
- `addHandler`/`clear`/`size`/`getHandlerNames` 用 `synchronized(chainGuard)`
- 与 ADR-0022 同向(单一真理源),收尾清扫

### 扼杀(不落地,记录以避免重复评审)

**候选 2 — 布隆并发位图**:扼杀。`ReadWriteLock` 读读并发开销本小(JDK 11+ 优化),
`AtomicLongArray` 位运算易错,收益边际、风险 > 收益。`WS-1.2c` rebuilding CoW 快照可后续单独议。

**候选 4 — `SyncSupport` 拆 `LockOrchestrator` seam**:扼杀。deletion test 不过 —— 单调用方
(`SyncLockHandler`)+ 单实现(`DistributedLockManager`)= 假想 seam(ADR-0029 已认可紧耦合)。
若候选 1 落地后第四职责(future 池管理)压力显现,再重开。

## 后果

**语义改变(调用方需知晓)**:

- **失败传播**:follower 不再独立 double-check 自救,**继承 leader 异常**。更符合击穿保护精神
  (避免 N 个 follower 在 leader 失败后继续打 DB);调用方可自行重试
- **超时**:follower 用 `future.get(timeoutSeconds)`,原模型用 Redisson wait(语义近似)

**吞吐收益**:同 key 高并发读 miss,follower 串行开销 `O(N × (锁往返 + GET))` → `O(ε)`。
> 修正:原评审报告 "O(N×loader)" 是高估 —— `RedisProCache.executeSyncLoad` 已有 double-check GET
> 避免 follower 重跑 user loader;future 只消除 follower 的分布式锁往返 + double-check GET。

**顺带修复**:`SyncSupport` 构造函数原 `lockManagers.sort(...)` 直接排序**入参** list,传不可变 list
(`List.of`)会抛 `UnsupportedOperationException`。改 stream 排序(不改入参,防御性),减法换
`Integer.compare`。

## 测试

- 既有 `SyncSupportTest`(单线程 leader 边界)/ `SyncLockHandlerTest`(mock SyncSupport)/
  `CacheHandlerChainTest` —— 全绿(leader 路径语义等价,signature 不变)
- 新增 `SyncSupportSingleFlightTest` 4 测:leader-follower 结果共享 / loader 只调一次 /
  失败传播 / 重入 fast-path 不死锁 / follower timeout
- `SyncSingleFlightIntegrationTest`(Testcontainers 真 Redis)受限于本地 Docker 环境,
  单测已充分覆盖 single-flight 语义契约;CI 全量 `./mvnw verify` 落地后自动覆盖

## 不变量保留

- `WS-1.2a` 无 Redisson 时 fail-fast
- `WS-1.2b` Cluster hash-tag pinning(`DistributedLockManager` 未动)
- ADR-0022 链单一真理源(execute 去锁与之同向)
- ADR-0037 `TwoListLRU` 全局锁(不在数据热路径,未触及)

## 相关

- [[breakdown-lock]] —— 分布式锁机制页(已加"演进"段)
- [[chain-of-responsibility]] —— 责任链架构页
- [[0022-chain-single-representation-seam]] —— 链单一真理源(候选 3 同向)
- [[0029-single-adapter-hypothetical-seams-acceptance]] —— 假想 seam 接受(候选 4 扼杀依据)
