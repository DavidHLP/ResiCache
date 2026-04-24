# Phase 1: 技术债务修复 - Research

**Date:** 2026-04-24
**Phase:** 01-tech-debt

## TECH-01: TwoListLRU 读锁优化

### 问题分析
- `getActiveSize()` 和 `getInactiveSize()` 在 lines 225-246 每次调用获取读锁
- 高并发下造成锁竞争，性能下降

### 解决方案
**AtomicInteger 计数器方案：**

```java
// 当前实现：读锁保护
public int getActiveSize() {
    listLock.readLock().lock();
    try {
        return activeSize;
    } finally {
        listLock.readLock().unlock();
    }
}

// 优化后：无锁读取
private final AtomicInteger activeSizeCounter = new AtomicInteger(0);
private final AtomicInteger inactiveSizeCounter = new AtomicInteger(0);

public int getActiveSize() {
    return activeSizeCounter.get();
}
```

### 写操作时的计数器更新
```java
// put 时
activeSizeCounter.incrementAndGet();

// promote 时 (active -> inactive)
activeSizeCounter.decrementAndGet();
inactiveSizeCounter.incrementAndGet();

// evict 时需要判断当前节点在哪个列表
```

### 风险评估
- **风险**: 并发情况下计数器可能暂时不一致（最终一致）
- **可接受**: size() 是近似值，允许短暂不一致
- **替代方案**: StripedReadWriteLock — 减少锁粒度但仍有开销

---

## TECH-02: cleanFinished() 清理机制

### 问题分析
- `cleanFinished()` (lines 262-268) 只在 `getActiveCount()` 调用时运行
- 高预刷新吞吐量下，已完成 futures 可能堆积

### 解决方案
**独立定期清理：**

```java
private final ScheduledExecutorService cleanupScheduler =
    Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pre-refresh-cleanup");
        t.setDaemon(true); // 不阻止 JVM 退出
        return t;
    });

private volatile long cleanupIntervalMs = 30_000; // 可配置

@PostConstruct
public void init() {
    cleanupScheduler.scheduleAtFixedRate(
        this::cleanFinished,
        cleanupIntervalMs,
        cleanupIntervalMs,
        TimeUnit.MILLISECONDS
    );
}
```

### 线程安全
- `cleanFinished()` 使用 `Iterator.remove()` 安全删除
- 或使用 `inFlight.entrySet().removeIf()` (Java 8+)

---

## TECH-03: PreRefreshHandler TTL 竞态

### 问题分析
- 预刷新任务异步执行，不检查 TTL
- 如果线程池饱和，预刷新可能在数据过期后才执行

### 解决方案
**TTL 检查后填充：**

```java
// 在执行预刷新前
String actualKey = context.getActualKey();
Long remainingTtl = cache.getTtl(actualKey); // 假设存在此 API

if (remainingTtl != null && remainingTtl < refreshAheadThresholdMs) {
    // TTL 即将过期，跳过填充
    log.debug("Skipping pre-refresh for key {}: TTL={}ms", actualKey, remainingTtl);
    return;
}

// 执行填充...
```

### 备选方案
- 使用分布式锁保证一致性（复杂，不推荐）
- 延长预刷新数据 TTL（改变语义，不推荐）

---

## TECH-04: getChain() DCL 简化

### 问题分析
- 当前模式: `volatile` + 内层 `synchronized`
- 实际上 volatile + DCL 在 Java 5+ 是正确的，但理解困难

### 解决方案
**简化为 synchronized：**

```java
// 当前代码 (lines 303-313):
private volatile CacheHandlerChain cachedChain;

private CacheHandlerChain getChain() {
    if (cachedChain == null) {
        synchronized (this) {
            if (cachedChain == null) {
                cachedChain = buildChain();
            }
        }
    }
    return cachedChain;
}

// 简化后：
private final CacheHandlerChain cachedChain = buildChain(); // 饿汉式

// 或：
private final Supplier<CacheHandlerChain> chainSupplier = this::buildChain;
```

**推荐方案 — 饿汉式（延迟极小）：**
```java
private final CacheHandlerChain cachedChain = buildChain();
```

如果需要真正延迟初始化：
```java
private final Supplier<CacheHandlerChain> chainSupplier = this::buildChain;
```

---

## 验证策略

1. **编译测试**: 所有修改必须通过 `./mvnw compile`
2. **单元测试**: 现有测试必须通过 `./mvnw test`
3. **并发测试**: TwoListLRU 并发测试验证计数器正确性
4. **内存测试**: cleanFinished 定期调用后 inFlight Map 不增长
