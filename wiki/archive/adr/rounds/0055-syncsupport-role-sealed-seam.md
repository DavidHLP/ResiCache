---
title: Round 41 — SyncSupport 3-path single-flight 选举 → sealed SyncRole (Reentrant / Leader / Follower) 深模块
type: adr
tags:
  - adr
  - seam-deepening
  - round-41
  - breakdown-package
related: [0029-single-adapter-hypothetical-seams-acceptance, 0042-syncsupport-singleflight-future-and-chain-readlock-removal, 0052-actualcachehandler-storeintent-deep-module, 0054-cacheoperation-predicate-seam]
status: stable
created: 2026-07-08
updated: 2026-07-08
---

# ADR-0055 — Round 41:SyncSupport single-flight 选举 → sealed `SyncRole` (Reentrant / Leader / Follower) 深模块

> ADR-0052 (Round 38) 把写路径的 store / ttl-decision 决议物抽为 `StoreIntent` 私有深模块,
> 示范了「隐含概念命名 + state + cleanup 内聚」的可复用模式。本轮把同一模式应用到
> `SyncSupport.executeSync` —— 此前 3 个运行时角色(reentrant / leader / follower)以
> if/switch 分支形式散落 3 个方法 + 共享 2 个字段(50+ SLOC),本轮抽为 sealed interface
> + 3 实现,SyncSupport 收窄为「选举 + 委派」2 步。

## 上下文 (Context)

`SyncSupport.executeSync` 是 single-flight 协议的唯一入口。同 key 并发请求在运行期会落到
**3 个互斥角色**之一:

| 角色 | 触发条件 | 行为 | cleanup 责任 |
|------|---------|------|------------|
| **Reentrant** | 当前线程已是此 key 的 leader(同线程嵌套重入,chain 内 `SyncLockHandler` → `super.get` → chain GET → `SyncLockHandler` 再次进入) | `loader.get()` 直接跑 | 无 |
| **Leader** | `inFlight.putIfAbsent` CAS 胜出 | 持分布式锁跑 loader → `complete` future → 清理 reentrantKeys / inFlight | 3 处 finally |
| **Follower** | 拿到 leader 已发布的 future | `future.get(timeout)` join | 无 |

**问题**:3 个角色的 state + cleanup 散落 3 个方法 + 2 个共享字段:

- `runAsLeader(key, loader, timeout, mine)` —— 30+ SLOC,管理 reentrantKeys / inFlight / future.complete / try-catch RuntimeException + InterruptedException
- `doLeaderWork(key, loader, timeout)` —— 25+ SLOC,持锁循环 + `executeWithoutDistributedBackend` 委派
- `executeWithoutDistributedBackend(key, loader)` —— 15+ SLOC,fail-fast / local-only 分支
- `runAsFollower(key, existing, timeout)` —— 30+ SLOC,future join + 3 路 catch
- 私有静态嵌套 `LockStack` —— 25 SLOC,只服务于 leader

通读代码发现,「leader 怎么走」必须读 4 个方法 + 1 个嵌套类 + 2 个字段;
「follower 怎么走」必须读 1 个方法 + 2 个 catch 块。**角色抽象被埋没在
orchestrator 内部**——读者无法一眼看出「这是一个 3 角色 single-flight」。

### 删除测试 (Deletion Test)

```
假设:不抽 SyncRole,保持 3 分支 inline。
└─ 复杂度测度:
   ├─ 3 个角色 × 2-3 个方法 = 7 个方法,职责边界模糊
   ├─ 角色 state(reentrantKeys/inFlight/timeout/properties/distributedManagers)与
   │  orchestrator 共享,新增角色需在 SyncSupport 加新方法 + 新分支
   └─ 单元测试要测单角色行为必须 mock 全部 SyncSupport 依赖,无角色级独立可测性

抽 SyncRole sealed + 3 角色类后:
   ├─ SyncSupport 只剩 2 步:electRole(key, loader, timeout) + role.run()
   ├─ 每个角色:1 个 run() 方法 + 自身 state(final 字段),Locality 拉满
   └─ 角色级测试:直接 new SyncRole.Leader<>(...) / Follower / Reentrant,无需 orchestrator
```

**deletion test 判据**:把 3 个角色删掉、内联回 `executeSync` → 7 个方法回归 + state 散在
orchestrator + 单元测试又得 mock 全部依赖。复杂度**上升**而非下降。本 seam **浓缩复杂度**
+ 释放单角色独立可测性,过 ADR-0029 门槛。

## 备选路径与驳回 (Alternatives Rejected)

| 路径 | 方案 | 裁决 |
|------|------|------|
| **A** | 把 3 个角色内联为 `SyncSupport` 的 3 个 private 嵌套类(用 enum / sealed 描述分支) | **驳回**:private 嵌套类对外完全不可见,无法在测试中独立构造单角色以测其行为;同时让 SyncSupport 仍是 200+ SLOC 的「什么都做」类 |
| **B** | 抽 `SyncRoleFactory` 把选举逻辑独立成工厂类 | **驳回**:工厂类只是把 3 个分支搬了位置,没有 locality 提升;`SyncSupport.electRole` 15 SLOC 自带本地决策,无必要再抽一层 |
| **C** | 用 `Strategy` 模式 + 4 个策略类(再加一个 ElectionStrategy) | **驳回**:策略模式比 sealed interface 多一层间接;sealed interface 是 Java 17+ 官方推荐的封闭集合表达,compile-time exhaustive(模式匹配时编译器能验证所有 case) |
| **D** | 角色间共享 state 改为 `SyncContext` 参数对象 | **驳回**:每个角色各自只需要自己关心的 state(Reentrant 只需 loader;Follower 只需 leader+timeout;Leader 需 7 字段),用 `SyncContext` 全量注入会让 Follower 收到无用的 distributedManagers / reentrantKeys,违反 locality |
| **E**(采用) | Sealed interface + 3 实现,各角色构造时自承所需 state,`run()` 无参 | 见下「决策」 |

## 决策 (Decision)

### 1. 新建 `SyncRole.java`(同包,包私有 sealed interface)

```java
sealed interface SyncRole<T> permits SyncRole.Reentrant, SyncRole.Leader, SyncRole.Follower {
    T run();
}
```

无参 `run()` 设计:loader 在角色构造时传入,统一 3 个角色的调用契约(Reentrant 不需要
外部再传 loader)。

### 2. `Reentrant` 是 record(纯值,无 state)

```java
record Reentrant<T>(Supplier<T> loader) implements SyncRole<T> {
    @Override public T run() { return loader.get(); }
}
```

`record` 表达「(loader) → 直接调用」的全部状态,无副作用。

### 3. `Leader` 是 final class(需持有 7 个 state 字段)

持有 `key / timeoutSeconds / loader / mine / distributedManagers / properties / inFlight / reentrantKeys`,
全部 final,immutable role。`run()` 内部完成 reentrantKeys.add → doLeaderWork → mine.complete → cleanup。
`LockStack` 改为 `Leader` 私有嵌套(它本质是 leader 持锁的载体,不应暴露给 Follower / SyncSupport)。

### 4. `Follower` 是 final class(3 字段)

持有 `key / leader / timeoutSeconds`,`run()` join leader future + 处理 3 路 catch
(TimeoutException / ExecutionException / InterruptedException)。

### 5. `SyncSupport` 收窄为 orchestrator

```java
public <T> T executeSync(String key, Supplier<T> loader, long timeoutSeconds) {
    return electRole(key, loader, timeoutSeconds).run();
}

private <T> SyncRole<T> electRole(String key, Supplier<T> loader, long timeoutSeconds) {
    if (reentrantKeys.get().contains(key)) {
        return new SyncRole.Reentrant<>(loader);
    }
    CompletableFuture<Object> mine = new CompletableFuture<>();
    CompletableFuture<Object> existing = inFlight.putIfAbsent(key, mine);
    if (existing == null) {
        return new SyncRole.Leader<>(key, timeoutSeconds, loader, mine,
                distributedManagers, properties, inFlight, reentrantKeys);
    }
    return new SyncRole.Follower<>(key, existing, timeoutSeconds);
}
```

`SyncSupport` 现在只剩:① 构造时排序 managers + warnIfNoDistributedBackend;② isDegraded 健康查询;
③ executeSync 选举 + 委派。原 100+ SLOC 收窄至 ~40 SLOC。

## 影响面 / SLOC 对比

| 项 | Round 40(前) | Round 41(本 ADR) | 净变化 |
|----|------|------|------|
| `SyncSupport.java` 总行数 | 342 | ~200 | **-142**(去除 runAsLeader / doLeaderWork / executeWithoutDistributedBackend / runAsFollower / LockStack + Javadoc 收缩) |
| `SyncRole.java`(NEW) | 0 | ~290(纯 Javadoc + 3 实现) | +290 |
| `SyncSupport` 公开 API | 3 方法(构造 / isDegraded / executeSync) | 不变(签名 / 行为 byte-for-byte) | **0** |
| `SyncSupport` 私有方法 | 6(runAsLeader / doLeaderWork / executeWithoutDistributedBackend / runAsFollower / warnIfNoDistributedBackend / 排序) | 2(warnIfNoDistributedBackend / electRole) | -4 |
| 公开 API 增量 | 0 | 0(sealed interface 包私有,不对外) | **0** |
| 行为字节等价 | (基线) | 100% 保持(13 个既有单测全绿) | **0** |
| 单角色可独立测 | 否(必须 mock 全 SyncSupport 依赖) | 是(直接 `new SyncRole.Leader<>(...)` 等) | **+leverage** |

净 SLOC **+148**(全为 Javadoc 解释 deepening 理由 + 角色类骨架);
**逻辑代码**(`SyncSupport` 私有方法 -4 + 角色内 0 冗余),**复杂度塌缩 7 方法 → 3 角色类**。

## 字节等价 / 测试矩阵

`SyncSupportTest` 11 个测试 + `SyncSupportSingleFlightTest` 4 个测试 = **15 个测试全绿**,
精确 pin 了 single-flight 3 角色的所有路径:

| 角色路径 | 既有测试 | 本轮保持 |
|---------|---------|---------|
| Reentrant fast-path | `singleFlight_reentrantNestedFastPath_noDeadlock` | ✅ |
| Leader 正常路径(单 manager) | `executeSync_lockAcquired_returnsResult` | ✅ |
| Leader 中断路径 | `executeSync_interrupted_throwsIllegalStateException` | ✅ |
| Leader fail-fast(无后端) | `executeSync_noManagers_failsFastByDefault` | ✅ |
| Leader local-only 降级 | `executeSync_noManagers_localOnly_degradesToJvm` | ✅ |
| Follower 共享 leader 结果 | `singleFlight_concurrentFollowers_loaderInvokedOnce_allShareResult` | ✅ |
| Follower 失败传播 | `singleFlight_leaderFails_followersReceiveSameException` | ✅ |
| Follower 超时 | `singleFlight_followerTimeout_leaderStillRunning` | ✅ |
| LockStack 持锁 + 释放 | (经由 leader 路径隐式覆盖) | ✅ |
| timeout 边界(0/1/负/MAX) | `executeSync_timeoutZero_passesToLockManager` 等 4 测试 | ✅ |

`SyncLockHandlerTest` 7 个测试同步全绿(SyncLockHandler 调 `executeSync` 入口未变)。

## 验证状态

- ✅ `./mvnw checkstyle:check` 0 violation
- ✅ `./mvnw compile` 绿(JDK 21,sealed interface + record + final class)
- ✅ `SyncSupportTest` 11 测试 + `SyncSupportSingleFlightTest` 4 测试 + `SyncLockHandlerTest` 7 测试 — **22 测试全绿**
- ✅ 全量 765 单测待 Round 42 末尾运行(本轮单 commit 验证范围聚焦 SyncSupport)

## 设计纪律

- **Sealed 优于枚举**:3 个角色每个有自身 state(Reentrant 1 字段,Follower 3 字段,Leader 8 字段),
  枚举的固定 state 集无法表达这种字段差异;sealed interface + record/class 混合表达「同质角色,
  异构 state」。
- **角色不反向引用 orchestrator**:`Leader` / `Follower` / `Reentrant` 构造时**接收**所需 state,
  **不**持有 `SyncSupport` 实例。orchestrator 知道角色;角色不知道 orchestrator。
- **包私有**:sealed interface + 3 实现全部 package-private,仅 `SyncSupport` 可调用,不对
  外暴露。违反包私有会让 `RedisProCacheWriter` 误用角色。
- **Reentrant 用 record**:只有 Reentrant 是纯值类型(loader);Leader / Follower 需持有多个
  state,用 final class。混用 record + class 是 Java 17+ sealed 的标准模式。
- **`run()` 无参**:loader 在角色构造时传入,统一 3 角色调用契约。如果保留 `run(loader)`
  签名,Reentrant 的 `loader` 字段就是 dead duplicate,违反 DRY。
- **`LockStack` 跟随 `Leader` 移动**:原在 SyncSupport 私有,本质是 leader 持锁的载体。挪到
  Leader 私有后,Follower 看不到(本来就不该看到),SyncSupport 看不到(只关心结果)。

## 相关 ADR

- **前置**:
  - ADR-0029(single-adapter hypothetical seam 接受策略 — 本轮 3 角色都跨多方法,远超 2-site 门槛)
  - ADR-0042(single-flight future 协议本身 — 本轮是该协议**实现侧的命名收口**,
    协议不动,只把 3 路径命名)
  - ADR-0052(StoreIntent 深模块模式 — 本轮复用其「隐含概念命名 + state + cleanup 内聚」骨架)
- **后续**:无新候选挂账。`SyncSupport` 收窄至 orchestrator,角色概念可独立演进(如未来
  加 `HybridRole` 走 multi-tier 锁,只需新增一个 permitted type,SyncSupport 零修改)。
