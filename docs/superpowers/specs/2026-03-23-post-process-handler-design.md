# 后置处理接口规范化设计

**日期**: 2026-03-23
**状态**: 设计阶段
**作者**: Claude Code
**类型**: 架构改进

---

## 1. 概述

### 1.1 问题陈述

当前 ResiCache 责任链中的后置处理机制存在以下问题：

1. **违反开闭原则**: 添加新的后置处理器需要修改 `CacheHandlerChain.executePostProcess()`
2. **硬编码类型检查**: 使用 `instanceof BloomFilterHandler` 判断
3. **无统一接口**: `afterChainExecution()` 是 `BloomFilterHandler` 特有的方法
4. **无法自动发现**: 新的后置处理器不会自动被调用

### 1.2 设计目标

1. 定义统一的 `PostProcessHandler` 接口
2. `CacheHandlerChain` 自动发现并调用所有后置处理器
3. 保持向后兼容，现有代码无需修改
4. 支持条件后置处理
5. 扩展性好，便于添加新的后置处理器

---

## 2. 当前实现分析

### 2.1 现有代码

```java
// CacheHandlerChain.java - 硬编码后置处理
private void executePostProcess(CacheContext context, CacheResult result) {
    for (CacheHandler handler : handlers) {
        if (handler instanceof BloomFilterHandler bloomHandler) {
            bloomHandler.afterChainExecution(context, result);
        }
        // 可以在此添加其他支持后置处理的 Handler
    }
}

// BloomFilterHandler.java - 自定义后置处理方法
public class BloomFilterHandler extends AbstractCacheHandler {
    public void afterChainExecution(CacheContext context, CacheResult result) {
        // 后置处理逻辑
    }
}
```

### 2.2 问题影响

| 问题 | 影响 |
|------|------|
| 硬编码 instanceof | 添加新后置处理器需修改 `CacheHandlerChain` |
| 无统一接口 | 后置处理逻辑分散，难以维护 |
| 无法自动发现 | 扩展性差，不符合开闭原则 |

---

## 3. 设计方案

### 3.1 接口标记法（采用）

#### 3.1.1 新接口定义

```java
package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;

/**
 * 缓存责任链后置处理器接口
 */
public interface PostProcessHandler {

    /**
     * 后置处理回调
     *
     * @param context 缓存上下文
     * @param result 责任链执行结果
     */
    void afterChainExecution(CacheContext context, CacheResult result);

    /**
     * 判断是否需要执行后置处理
     *
     * @param context 缓存上下文
     * @return true 表示需要执行后置处理
     */
    default boolean requiresPostProcess(CacheContext context) {
        return true;
    }
}
```

#### 3.1.2 CacheHandlerChain 修改

```java
private void executePostProcess(CacheContext context, CacheResult result) {
    for (CacheHandler handler : handlers) {
        if (handler instanceof PostProcessHandler postHandler) {
            if (postHandler.requiresPostProcess(context)) {
                try {
                    postHandler.afterChainExecution(context, result);
                    log.debug("Post-processing executed for: {}",
                              handler.getClass().getSimpleName());
                } catch (Exception e) {
                    log.error("Post-processing failed for: {}, operation: {}, key: {}",
                              handler.getClass().getSimpleName(),
                              context.getOperation(),
                              context.getRedisKey(), e);
                    // 后置处理失败不影响主链结果
                }
            }
        }
    }
}
```

#### 3.1.3 BloomFilterHandler 改造

```java
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerOrder(HandlerOrders.BLOOM_FILTER)
public class BloomFilterHandler extends AbstractCacheHandler
        implements PostProcessHandler {  // ← 新增接口实现

    private static final String POST_PROCESS_KEY = "bloom.postProcess";

    // ... doHandle() 方法保持不变 ...

    @Override
    public void afterChainExecution(CacheContext context, CacheResult result) {
        if (context == null || result == null) {
            return;
        }

        switch (context.getOperation()) {
            case PUT, PUT_IF_ABSENT -> addToBloomFilter(context, result);
            case CLEAN -> clearBloomFilter(context, result);
            default -> { }
        }
    }

    @Override
    public boolean requiresPostProcess(CacheContext context) {
        return context.getAttribute(POST_PROCESS_KEY, false);
    }

    private void addToBloomFilter(CacheContext context, CacheResult result) {
        if (!result.isSuccess() || context.isSkipRemaining()) {
            return;
        }
        bloomSupport.add(context.getCacheName(), context.getActualKey());
    }

    private void clearBloomFilter(CacheContext context, CacheResult result) {
        if (!result.isSuccess() || context.isSkipRemaining()) {
            return;
        }
        if (context.getKeyPattern() != null && context.getKeyPattern().endsWith("*")) {
            bloomSupport.clear(context.getCacheName());
        }
    }
}
```

### 3.2 错误处理策略

| 场景 | 处理方式 |
|------|----------|
| 后置处理抛出异常 | 捕获并记录日志，不影响主链结果和其他后置处理器 |
| context 或 result 为 null | 后置处理器内部做空值检查，直接返回 |
| 操作失败（!result.isSuccess()） | 后置处理器内部检查，跳过处理 |

### 3.3 向后兼容性

- ✅ 现有 Handler 无需修改
- ✅ `BloomFilterHandler` 只需实现新接口
- ✅ `RedisProCacheWriter` 无需修改
- ✅ 未实现 `PostProcessHandler` 的 Handler 不受影响

---

## 4. 扩展示例

### 4.1 审计日志处理器

```java
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerOrder(HandlerOrders.AUDIT)
public class AuditLogHandler extends AbstractCacheHandler
        implements PostProcessHandler {

    private final AuditLogService auditLogService;
    private static final String AUDIT_ENABLED_KEY = "audit.enabled";

    @Override
    protected boolean shouldHandle(CacheContext context) {
        if (context.getCacheOperation() != null
                && context.getCacheOperation().isAuditEnabled()) {
            context.setAttribute(AUDIT_ENABLED_KEY, true);
        }
        return false;
    }

    @Override
    public boolean requiresPostProcess(CacheContext context) {
        return context.getAttribute(AUDIT_ENABLED_KEY, false);
    }

    @Override
    public void afterChainExecution(CacheContext context, CacheResult result) {
        AuditLogEntry entry = AuditLogEntry.builder()
            .cacheName(context.getCacheName())
            .key(context.getActualKey())
            .operation(context.getOperation().name())
            .success(result.isSuccess())
            .timestamp(System.currentTimeMillis())
            .build();

        auditLogService.log(entry);
    }
}
```

### 4.2 缓存事件发布器

```java
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerOrder(HandlerOrders.EVENT_PUBLISHER)
public class CacheEventPublisherHandler extends AbstractCacheHandler
        implements PostProcessHandler {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return false;
    }

    @Override
    public boolean requiresPostProcess(CacheContext context) {
        return context.getOperation() == CacheOperation.PUT
                || context.getOperation() == CacheOperation.REMOVE;
    }

    @Override
    public void afterChainExecution(CacheContext context, CacheResult result) {
        CacheOperationEvent event = new CacheOperationEvent(
            context.getCacheName(),
            context.getActualKey(),
            context.getOperation(),
            result.isSuccess(),
            System.currentTimeMillis()
        );
        eventPublisher.publishEvent(event);
    }
}
```

### 4.3 纯后置处理器

```java
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerOrder(HandlerOrders.METRICS_COLLECTOR)
public class MetricsCollectorHandler extends AbstractCacheHandler
        implements PostProcessHandler {

    private final MetricsService metricsService;

    @Override
    protected boolean shouldHandle(CacheContext context) {
        return false;  // 不参与主链
    }

    @Override
    public boolean requiresPostProcess(CacheContext context) {
        return true;
    }

    @Override
    public void afterChainExecution(CacheContext context, CacheResult result) {
        metricsService.record(
            context.getCacheName(),
            context.getOperation(),
            result.isSuccess() ? "SUCCESS" : "FAILURE"
        );
    }
}
```

---

## 5. 文件清单

### 5.1 新增文件

| 文件路径 | 说明 |
|----------|------|
| `.../chain/handler/PostProcessHandler.java` | 后置处理器接口 |

### 5.2 修改文件

| 文件路径 | 修改内容 |
|----------|----------|
| `CacheHandlerChain.java` | 更新 `executePostProcess()` 方法 |
| `BloomFilterHandler.java` | 实现 `PostProcessHandler` 接口 |

### 5.3 未修改文件

| 文件路径 | 说明 |
|----------|------|
| `CacheHandler.java` | 无需修改 |
| `AbstractCacheHandler.java` | 无需修改 |
| `SyncLockHandler.java` | 无需修改 |
| `PreRefreshHandler.java` | 无需修改 |
| `TtlHandler.java` | 无需修改 |
| `NullValueHandler.java` | 无需修改 |
| `ActualCacheHandler.java` | 无需修改 |
| `RedisProCacheWriter.java` | 无需修改 |

---

## 6. 实施计划

### 阶段 1: 创建接口
1. 创建 `PostProcessHandler.java` 接口

### 阶段 2: 修改框架
1. 更新 `CacheHandlerChain.executePostProcess()`

### 阶段 3: 改造现有处理器
1. 让 `BloomFilterHandler` 实现 `PostProcessHandler`
2. 调整后置处理逻辑

### 阶段 4: 测试验证
1. 单元测试
2. 集成测试
3. 向后兼容性验证

---

## 7. 验收标准

- [ ] 所有单元测试通过
- [ ] 所有集成测试通过
- [ ] 布隆过滤器功能正常工作
- [ ] 后置处理异常不影响主链结果
- [ ] 新增后置处理器无需修改 `CacheHandlerChain`
- [ ] 代码审查通过

---

## 8. 附录

### 8.1 方案对比

| 方案 | 优点 | 缺点 | 选择 |
|------|------|------|------|
| 接口标记法 | 改动小、清晰、扩展性好 | 仍需 instanceof 检查 | ✅ 采用 |
| 双接口分离 | 职责完全分离 | 复杂度高、两套注入逻辑 | ❌ |
| 事件驱动 | 完全解耦 | 引入 Spring 事件依赖 | ❌ |

### 8.2 设计决策记录

| 决策 | 理由 |
|------|------|
| 使用接口标记而非注解 | 接口提供类型安全，编译时检查 |
| 后置处理异常不影响主链 | 后置处理是辅助功能，不应影响核心流程 |
| 保留 requiresPostProcess() 方法 | 支持条件执行，避免不必要的后置处理 |
| 后置处理器按链顺序执行 | 保持可预测性和一致性 |
