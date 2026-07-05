---
title: "ADR-0023: Executor graceful-shutdown seam (shutdown 优雅关闭样板双写收敛)"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0014
tags:
  - protection
  - refresh
  - deepening
  - duplication-removal
  - round-15
---

# ADR-0023: Executor graceful-shutdown seam (shutdown 优雅关闭样板双写收敛)

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0014(同款「小规模 locality 收敛」先例:RedisProCache + RedisProCacheManager 构造重载墙)

## 背景

第 15 轮架构评审基于 round 1–14 已落地 ADR-0009~0022,系统化扫描前 14 轮**未触及的域**:`serialization/`、`config/`(序列化侧 + `RedisProCacheProperties`)、`eviction/`、`observability/`、`cache/RedisProCache.get`、`protection/refresh/`、`protection/bloom/filter/`(三实现)、`protection/breakdown/`(RedissonLockHandle.close)。这些域经 deletion test 核验**绝大多数健康**(WhitelistPolicy 已是深模块、bloom 三实现是真 seam、RefreshRetryPolicy 纯函数在挣价值、RedissonLockHandle.close 高复杂度是必要锁释放防御、RedisProCacheProperties 9 配置类 Lombok 消样板)。

唯一经 deletion test 通过的真实 friction 在 `protection/refresh/ThreadPoolEarlyExpirationExecutor.shutdown()`:`@PreDestroy` 关闭方法对 `cleanupScheduler`(line 224-236)与 `executorService`(line 238-250)**逐字重复同一段优雅关闭样板**(~13 行):

```
shutdown() → awaitTermination(N, SECONDS) → 未终止则 shutdownNow() + WARN → 正常则 INFO
catch InterruptedException → shutdownNow() + interrupt() + ERROR
```

两段唯一差异:超时阈值(`5` vs `10` 秒)与日志名称(`"Pre-refresh cleanup scheduler"` vs `"Pre-refresh executor"`)。

**Friction**:优雅关闭策略变更(如调整超时梯度、加关闭回调、改日志级别)需**两处同步修改**,易漂移——locality 缺失。

**deletion test**:删两段重复、抽成单一 `shutdownGracefully(executor, timeoutSeconds, name)` 私有 seam → 优雅关闭逻辑集中一处,`shutdown()` 退化为 2 行委派 → 复杂度不散开(只此一处 2 副本,非跨 N 调用方重复),locality 到手。反向(保留双写)则每次策略变更改 2 处。

## 决策

### D1 — 抽 `shutdownGracefully(ExecutorService, long, String)` 私有 seam(执行)

```java
private void shutdownGracefully(ExecutorService executor, long timeoutSeconds, String name) {
    executor.shutdown();
    try {
        if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            log.warn("{} did not terminate gracefully, forced shutdown", name);
        } else {
            log.info("{} shut down successfully", name);
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
        log.error("{} shutdown interrupted", name, e);
    }
}
```

`shutdown()` 退化为:

```java
@PreDestroy
public void shutdown() {
    log.info("Shutting down early-expiration executor thread pool...");
    shutdownGracefully(cleanupScheduler, 5, "Pre-refresh cleanup scheduler");
    shutdownGracefully(executorService, 10, "Pre-refresh executor");
}
```

### D2 — byte-for-byte 行为等价(执行)

两段调用与原代码完全等价:
- `cleanupScheduler`:`shutdown()` → `awaitTermination(5)` → `shutdownNow()` + `"Pre-refresh cleanup scheduler did not terminate gracefully, forced shutdown"` / `"...shut down successfully"` / `"...shutdown interrupted"`——日志消息与超时常量逐字保留
- `executorService`:同上,`10` 秒 + `"Pre-refresh executor"` 前缀

关闭顺序(`cleanupScheduler` 先、`executorService` 后)保留。`InterruptedException` 处理(`shutdownNow()` + `Thread.currentThread().interrupt()` + ERROR log)逐字保留。

### D3 — 不抽 `ExecutorServiceShutdown` 工具类到 util 包(撤销,over-engineering)

歧路:把 `shutdownGracefully` 提升为 `infrastructure/` 或 `util/` 包下的公共工具类。

**否决理由**:该优雅关闭样板**当前只此一处 2 副本**,无第三个消费者(全仓仅 `ThreadPoolEarlyExpirationExecutor` 同时持有两个需优雅关闭的 `ExecutorService`)。提前抽公共工具类 = 假 seam(无第二个 adapter,撞项目 2026-06-29 C2「删单实现/单消费者接口」纪律)。类内 `private` helper 是当前规模下的正确形态:locality 到手,不引入未兑现的抽象。等第三个消费者(如未来另一个持双 executor 的组件)出现,再提升为工具类——同款 ADR-0011「CacheKeys 双实现 seam 等第 3 个 use case」纪律。

## 后果

**增益(locality + leverage)**:

1. **优雅关闭策略单一真理源**:调超时梯度 / 加关闭回调 / 改日志级别,现只改 `shutdownGracefully` 一处,不再两处同步
2. **重复消除**:~13 行逐字样板 × 2 → 1 个 helper + 2 行委派,净减 body SLOC
3. **可读性**:`shutdown()` 一眼看出"先关清理调度器、再关主执行器"的编排意图,关闭机制细节下沉 helper

**代价**:无(public API 零变化,行为 byte-for-byte 等价)。

**不变**:
- `shutdown()` 签名(`@PreDestroy` public 无参)零变化
- 关闭顺序、超时阈值、日志消息、`InterruptedException` 处理 全部 byte-for-byte 保留
- 8 个引用方(`ActualCacheHandler` / `EarlyExpirationHandler` / 4 个 test 等)零影响
- `submit` / `cancel` / `getStats` / `getActiveCount` / `initCleanupScheduler` / `cleanFinished` / `EarlyExpirationThreadFactory` 全部不动

## 规模与性质诚实声明

本 ADR **规模小于** round 9–14 的多数 ADR:
- **2 处重复**(对比 ADR-0018 的 5 处 `onAttachMetrics` 样板、ADR-0021 的 3 处 `fromAttributes` 重复墙)
- **类内 private helper**(对比 ADR-0009/0013 的 cross-module Engine 抽出、ADR-0022 的接口收窄)

性质是 **locality 收敛 + 重复消除**,非 cross-module seam 提取或双轨消除。对标 **ADR-0014**(2 类构造重载墙收敛的小规模 locality ADR)先例——同属「重复不足以成墙但 locality 收益真实」的 polish 级 deepening。

第 15 轮在扫尽前 14 轮未触及域后,未见更强的 cross-module friction(架构经 14 轮 deepening 已趋饱和),本 ADR 是该轮唯一经 deletion test 验证为「在挣价值」的落地项;同期 Explore agent 提出的 5 个候选(RedisProCache.get 拆私有方法、RedisCacheHealthIndicator 抽接口、TwoListLRU 内部分离等)经红蓝博弈全部扼杀——理由见 `wiki/log.md` round 15 条目。

## 参考

- ADR-0014:RedisProCache + RedisProCacheManager 构造重载墙收敛(同款小规模 locality 先例)
- ADR-0011:CacheKeys 双实现 seam「等第 3 个 use case」纪律(本 ADR D3 撤销工具类的同源理由)
- Ousterhout《A Philosophy of Software Design》deep/shallow module + deletion test
