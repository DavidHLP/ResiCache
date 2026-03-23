# 后置处理接口规范化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标:** 规范化责任链后置处理机制，定义统一的 `PostProcessHandler` 接口，使 `CacheHandlerChain` 自动发现并调用所有后置处理器

**架构:** 引入标记接口 `PostProcessHandler`，Handler 可选择实现此接口以支持后置处理。`CacheHandlerChain.executePostProcess()` 遍历所有 Handler，自动调用实现了该接口的后置处理方法

**技术栈:** Java 17, Spring Boot 3.2.4, JUnit 5, Mockito, AssertJ

---

## 文件结构

### 新建文件
| 文件路径 | 职责 |
|----------|------|
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PostProcessHandler.java` | 后置处理器接口定义 |
| `src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/PostProcessHandlerTest.java` | 接口契约测试 |
| `src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChainTest.java` | 责任链集成测试 |

### 修改文件
| 文件路径 | 修改内容 |
|----------|----------|
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChain.java` | 更新 `executePostProcess()` 方法，自动发现后置处理器 |
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java` | 实现 `PostProcessHandler` 接口 |

---

## Task 1: 创建 PostProcessHandler 接口

**文件:**
- 创建: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PostProcessHandler.java`

- [ ] **Step 1: 创建接口文件**

创建文件 `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PostProcessHandler.java`:

```java
package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;

/**
 * 缓存责任链后置处理器接口
 *
 * <p>实现此接口的 Handler 将在责任链执行完成后收到回调，
 * 用于执行需要在主链完成后才能进行的操作。
 *
 * <p>典型使用场景：
 * <ul>
 *   <li>PUT 操作成功后更新辅助数据结构（布隆过滤器、索引等）</li>
 *   <li>记录审计日志或统计信息</li>
 *   <li>发布缓存事件通知</li>
 * </ul>
 *
 * <p>执行顺序：
 * <ul>
 *   <li>后置处理器按责任链中的顺序执行</li>
 *   <li>只有实现了此接口的 Handler 会收到回调</li>
 * </ul>
 *
 * @see CacheHandler
 * @see CacheContext
 * @see CacheResult
 */
public interface PostProcessHandler {

    /**
     * 后置处理回调
     *
     * <p>在责任链所有 Handler 执行完成后调用。
     *
     * @param context 缓存上下文
     * @param result 责任链执行结果
     */
    void afterChainExecution(CacheContext context, CacheResult result);

    /**
     * 判断是否需要执行后置处理
     *
     * <p>默认实现总是返回 true。子类可以重写以支持条件执行，
     * 例如只在特定操作类型或结果状态下执行后置处理。
     *
     * @param context 缓存上下文
     * @return true 表示需要执行后置处理
     */
    default boolean requiresPostProcess(CacheContext context) {
        return true;
    }
}
```

- [ ] **Step 2: 验证编译成功**

```bash
mvn compile -q
```

预期：编译成功，无错误

- [ ] **Step 3: 提交**

```bash
git add src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PostProcessHandler.java
git commit -m "feat: 添加 PostProcessHandler 接口

- 定义后置处理器接口，支持 afterChainExecution 回调
- 提供 requiresPostProcess 方法支持条件执行
- 添加完整的 Javadoc 文档

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 2: 修改 CacheHandlerChain 自动发现后置处理器

**文件:**
- 修改: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChain.java:97-104`
- 测试: `src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChainTest.java`

- [ ] **Step 1: 编写测试 - 验证后置处理器被正确调用**

创建文件 `src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChainTest.java`:

```java
package io.github.davidhlp.spring.cache.redis.core.writer.chain;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CacheHandlerChain 单元测试
 */
class CacheHandlerChainTest {

    private CacheHandlerChain chain;
    private CacheHandler mockHandler1;
    private CacheHandler mockHandler2;
    private TestPostProcessor mockPostProcessor;

    @BeforeEach
    void setUp() {
        chain = new CacheHandlerChain();
        mockHandler1 = mock(CacheHandler.class);
        mockHandler2 = mock(CacheHandler.class);
        mockPostProcessor = new TestPostProcessor();

        // 设置 mock handler 行为
        when(mockHandler1.getNext()).thenReturn(mockHandler2);
        when(mockHandler2.getNext()).thenReturn(null);
        when(mockHandler1.handle(any())).thenReturn(HandlerResult.continueChain());
        when(mockHandler2.handle(any())).thenReturn(HandlerResult.terminate(CacheResult.success()));
    }

    @Test
    void testExecuteCallsPostProcessor() {
        // Given: 添加一个实现 PostProcessHandler 的处理器
        chain.addHandler(mockPostProcessor);

        // When: 执行责任链
        CacheContext context = createTestContext();
        CacheResult result = chain.execute(context);

        // Then: 后置处理应该被调用
        assertThat(mockPostProcessor.afterChainExecutionCalled).isTrue();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void testExecuteOnlyCallsPostProcessorWhenRequired() {
        // Given: 设置后置处理器为不需要执行
        mockPostProcessor.required = false;
        chain.addHandler(mockPostProcessor);

        // When: 执行责任链
        CacheContext context = createTestContext();
        chain.execute(context);

        // Then: 后置处理不应该被调用
        assertThat(mockPostProcessor.afterChainExecutionCalled).isFalse();
    }

    @Test
    void testPostProcessorExceptionDoesNotAffectMainResult() {
        // Given: 后置处理器会抛出异常
        TestPostProcessor exceptionProcessor = new TestPostProcessor();
        exceptionProcessor.shouldThrowException = true;
        chain.addHandler(exceptionProcessor);

        // When: 执行责任链
        CacheContext context = createTestContext();
        CacheResult result = chain.execute(context);

        // Then: 主链结果应该正常返回
        assertThat(result.isSuccess()).isTrue();
        assertThat(exceptionProcessor.afterChainExecutionCalled).isTrue();
    }

    @Test
    void testMultiplePostProcessorsAllExecuted() {
        // Given: 添加多个后置处理器
        TestPostProcessor processor1 = new TestPostProcessor();
        TestPostProcessor processor2 = new TestPostProcessor();

        chain.addHandler(processor1);
        chain.addHandler(processor2);

        // When: 执行责任链
        CacheContext context = createTestContext();
        chain.execute(context);

        // Then: 所有后置处理器都应该被调用
        assertThat(processor1.afterChainExecutionCalled).isTrue();
        assertThat(processor2.afterChainExecutionCalled).isTrue();
    }

    private CacheContext createTestContext() {
        return CacheContext.builder()
                .operation(CacheOperation.PUT)
                .cacheName("test-cache")
                .redisKey("test:key")
                .actualKey("test:key")
                .build();
    }

    /**
     * 测试用后置处理器
     */
    static class TestPostProcessor extends AbstractCacheHandler implements PostProcessHandler {
        boolean afterChainExecutionCalled = false;
        boolean required = true;
        boolean shouldThrowException = false;

        @Override
        protected boolean shouldHandle(CacheContext context) {
            return false;
        }

        @Override
        protected HandlerResult doHandle(CacheContext context) {
            return HandlerResult.continueChain();
        }

        @Override
        public void afterChainExecution(CacheContext context, CacheResult result) {
            afterChainExecutionCalled = true;
            if (shouldThrowException) {
                throw new RuntimeException("Test exception in post processor");
            }
        }

        @Override
        public boolean requiresPostProcess(CacheContext context) {
            return required;
        }
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=CacheHandlerChainTest -q
```

预期：测试失败，因为 `executePostProcess` 还未使用新接口

- [ ] **Step 3: 修改 CacheHandlerChain.executePostProcess()**

修改 `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChain.java`:

```java
package io.github.davidhlp.spring.cache.redis.core.writer.chain;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.CacheContext;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.CacheHandler;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.HandlerResult;
import io.github.davidhlp.spring.cache.redis.core.writer.chain.handler.PostProcessHandler;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// ... (其他代码保持不变)

/**
 * 执行后置处理
 *
 * <p>遍历所有 Handler，如果实现了 PostProcessHandler 接口且满足条件，
 * 则调用其后置处理方法。
 *
 * <p>执行顺序与责任链顺序一致，确保后置处理按照预期顺序执行。
 *
 * @param context 缓存上下文
 * @param result 责任链执行结果
 */
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

**关键改动：**
1. 导入 `PostProcessHandler` 接口
2. 将 `instanceof BloomFilterHandler` 改为 `instanceof PostProcessHandler`
3. 添加 `requiresPostProcess()` 检查
4. 添加异常捕获，确保后置处理失败不影响主链结果
5. 添加调试日志

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=CacheHandlerChainTest -q
```

预期：所有测试通过

- [ ] **Step 5: 运行完整测试套件**

```bash
mvn test -q
```

预期：所有现有测试仍然通过（如果有）

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChain.java
git add src/test/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChainTest.java
git commit -m "refactor: CacheHandlerChain 自动发现后置处理器

- 修改 executePostProcess() 使用 PostProcessHandler 接口
- 自动发现所有实现了该接口的 Handler
- 添加异常处理，后置处理失败不影响主链结果
- 添加单元测试验证后置处理器调用逻辑

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 3: BloomFilterHandler 实现 PostProcessHandler 接口

**文件:**
- 修改: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java`

- [ ] **Step 1: 修改 BloomFilterHandler 类声明**

修改 `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java`:

在类声明处添加 `implements PostProcessHandler`:

```java
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerOrder(HandlerOrders.BLOOM_FILTER)
public class BloomFilterHandler extends AbstractCacheHandler
        implements PostProcessHandler {  // ← 添加此行
```

- [ ] **Step 2: 实现 requiresPostProcess() 方法**

在 `BloomFilterHandler` 类中添加方法（放在类末尾，`addToBloomFilter` 和 `clearBloomFilter` 方法之后）:

```java
/**
 * 判断是否需要执行后置处理
 *
 * <p>只在标记了 POST_PROCESS_KEY 且操作成功时执行。
 */
@Override
public boolean requiresPostProcess(CacheContext context) {
    Boolean postProcess = context.getAttribute(POST_PROCESS_KEY);
    return postProcess != null && postProcess;
}
```

- [ ] **Step 3: 重构 afterChainExecution() 方法**

修改现有的 `afterChainExecution()` 方法，将成功检查移入方法内部:

```java
/**
 * 后置处理：责任链执行完成后调用
 *
 * <p>此方法由 CacheHandlerChain 在所有 Handler 执行完成后自动调用。
 */
@Override
public void afterChainExecution(CacheContext context, CacheResult result) {
    // 空值检查
    if (context == null || result == null) {
        log.warn("Post-processing skipped: null context or result");
        return;
    }

    // 只在成功时执行后置处理
    if (!result.isSuccess() || context.isSkipRemaining()) {
        return;
    }

    // 根据操作类型执行相应的后置处理
    switch (context.getOperation()) {
        case PUT, PUT_IF_ABSENT -> addToBloomFilter(context);
        case CLEAN -> clearBloomFilter(context);
        default -> { /* GET 等操作无需后置处理 */ }
    }
}
```

- [ ] **Step 4: 修改 addToBloomFilter() 和 clearBloomFilter() 方法签名**

将这两个方法改为不再需要 `result` 参数:

```java
/**
 * 添加 key 到布隆过滤器
 */
private void addToBloomFilter(CacheContext context) {
    bloomSupport.add(context.getCacheName(), context.getActualKey());
    log.debug("Added key to bloom filter: cacheName={}, key={}",
              context.getCacheName(), context.getRedisKey());
}

/**
 * 清空布隆过滤器
 */
private void clearBloomFilter(CacheContext context) {
    if (context.getKeyPattern() != null && context.getKeyPattern().endsWith("*")) {
        bloomSupport.clear(context.getCacheName());
        log.debug("Bloom filter cleared along with cache: cacheName={}",
                  context.getCacheName());
    }
}
```

- [ ] **Step 5: 移除公共静态方法 getDecision()**

如果存在公共静态 `getDecision()` 方法，将其删除（不再需要）:

```java
// 删除以下方法（如果存在）
// public static PreRefreshDecision getDecision(CacheContext context) {
//     return context.getAttribute(DECISION_KEY, PreRefreshDecision.noRefresh());
// }
```

注意：检查文件中是否有 `DECISION_KEY` 常量用于其他用途。如果有，保留该常量；如果没有，也可以删除 `DECISION_KEY`。

- [ ] **Step 6: 验证编译成功**

```bash
mvn compile -q
```

预期：编译成功，无错误

- [ ] **Step 7: 运行测试验证通过**

```bash
mvn test -q
```

预期：所有测试通过

- [ ] **Step 8: 提交**

```bash
git add src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java
git commit -m "refactor: BloomFilterHandler 实现 PostProcessHandler 接口

- 实现 PostProcessHandler 接口
- 实现 requiresPostProcess() 方法支持条件执行
- 重构 afterChainExecution() 添加空值检查
- 简化 addToBloomFilter() 和 clearBloomFilter() 方法签名

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 4: 验证向后兼容性

**目标:** 确保现有代码无需修改即可继续工作

- [ ] **Step 1: 编译验证**

```bash
mvn clean compile -q
```

预期：编译成功，无错误

- [ ] **Step 2: 运行所有测试**

```bash
mvn test -q
```

预期：所有测试通过

- [ ] **Step 3: 检查未实现 PostProcessHandler 的 Handler**

验证以下 Handler 不受影响：

```bash
# 使用 grep 检查哪些 Handler 实现了 PostProcessHandler
grep -r "implements PostProcessHandler" src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/
```

预期输出应该只有 `BloomFilterHandler.java`

- [ ] **Step 4: 提交兼容性验证**

```bash
git log --oneline -5
```

确认最近的提交：
- `feat: 添加 PostProcessHandler 接口`
- `refactor: CacheHandlerChain 自动发现后置处理器`
- `refactor: BloomFilterHandler 实现 PostProcessHandler 接口`

---

## Task 5: 更新设计文档引用

**文件:**
- 修改: `docs/superpowers/specs/2026-03-23-post-process-handler-design.md`

- [ ] **Step 1: 更新设计文档状态**

修改设计文档头部状态:

```markdown
# 后置处理接口规范化设计

**日期**: 2026-03-23
**状态**: ✅ 已实现
**作者**: Claude Code
**类型**: 架构改进
```

- [ ] **Step 2: 添加实施计划引用**

在设计文档末尾添加:

```markdown
---

## 9. 实施状态

| 任务 | 状态 | 提交 |
|------|------|------|
| 创建 PostProcessHandler 接口 | ✅ 完成 | feat: 添加 PostProcessHandler 接口 |
| 修改 CacheHandlerChain | ✅ 完成 | refactor: CacheHandlerChain 自动发现后置处理器 |
| BloomFilterHandler 改造 | ✅ 完成 | refactor: BloomFilterHandler 实现 PostProcessHandler 接口 |
| 向后兼容性验证 | ✅ 通过 | - |
```

- [ ] **Step 3: 提交文档更新**

```bash
git add docs/superpowers/specs/2026-03-23-post-process-handler-design.md
git commit -m "docs: 更新后置处理设计文档状态为已实现

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## 验收标准

完成后验证以下清单：

- [ ] 所有单元测试通过 (`mvn test`)
- [ ] 所有集成测试通过（如果有）
- [ ] 布隆过滤器功能正常工作（PUT 后 key 被添加，GET 拦截不存在 key）
- [ ] 后置处理异常不影响主链结果
- [ ] 未实现 `PostProcessHandler` 的 Handler 不受影响
- [ ] 代码编译无警告
- [ ] Git 提交信息清晰，包含 Co-Authored-By

---

## 附录：测试数据示例

### CacheContext 创建示例

```java
// PUT 操作上下文
CacheContext putContext = CacheContext.builder()
    .operation(CacheOperation.PUT)
    .cacheName("user-cache")
    .redisKey("user:123")
    .actualKey("123")
    .deserializedValue(new User("123", "Alice"))
    .ttl(Duration.ofMinutes(30))
    .build();

// GET 操作上下文
CacheContext getContext = CacheContext.builder()
    .operation(CacheOperation.GET)
    .cacheName("user-cache")
    .redisKey("user:123")
    .actualKey("123")
    .build();

// CLEAN 操作上下文
CacheContext cleanContext = CacheContext.builder()
    .operation(CacheOperation.CLEAN)
    .cacheName("user-cache")
    .keyPattern("user:*")
    .build();
```

### 后置处理器扩展示例

```java
/**
 * 审计日志后置处理器示例
 */
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
        // 标记需要审计
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
        if (result == null || !result.isSuccess()) {
            return;
        }

        AuditLogEntry entry = AuditLogEntry.builder()
            .cacheName(context.getCacheName())
            .key(context.getActualKey())
            .operation(context.getOperation().name())
            .success(result.isSuccess())
            .timestamp(System.currentTimeMillis())
            .build();

        auditLogService.log(entry);
        log.debug("Audit log recorded: {}", entry);
    }
}
```
