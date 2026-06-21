---
title: 如何新增防护 Handler
type: how-to
tags:
  - howto
  - 扩展
  - handler
  - 教程
related: [chain-of-responsibility, handler-result-control, context-data-flow, configuration]
source-files:
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/HandlerOrder.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/AbstractCacheHandler.java
  - src/main/java/io/github/davidhlp/spring/cache/redis/chain/CacheHandlerChainFactory.java
status: stable
created: 2026-06-21
updated: 2026-06-21
---

# 如何新增防护 Handler

责任链设计成可插拔:新增一道防护只需 4 步,无需改动任何现有 handler。以「请求频率限制 handler」为例。

## 步骤 1:扩展 HandlerOrder 枚举

`src/main/java/io/github/davidhlp/spring/cache/redis/chain/HandlerOrder.java` —— 在合适位置加一个档位(间隔 100,可用 150/350 等空隙):

```java
public enum HandlerOrder {
    BLOOM_FILTER(100, "布隆过滤器-防穿透"),
    SYNC_LOCK(200, "分布式锁-防击穿"),
    EARLY_EXPIRATION(250, "提前过期-热key保护"),
    TTL(300, "TTL计算"),
    RATE_LIMIT(350, "频率限制-防滥用"),   // ← 新增
    NULL_VALUE(400, "空值处理"),
    ACTUAL_CACHE(500, "实际缓存操作");
    ...
}
```

> 单一真理源:顺序只在这里改。`CacheHandlerChainFactory` 自动按 `order` 排序装配。

## 步骤 2:实现 handler

继承 `AbstractCacheHandler`,实现 `shouldHandle` / `doHandle`:

```java
@Slf4j
@Component
@RequiredArgsConstructor
@HandlerPriority(HandlerOrder.RATE_LIMIT)   // ← 绑定新档位
public class RateLimitHandler extends AbstractCacheHandler {

    private final RateLimiter limiter;       // 你的限流器

    @Override
    protected boolean shouldHandle(CacheContext context) {
        // 只对 GET 生效(写不限流);按需判断注解开关
        return context.getOperation() == CacheOperation.GET;
    }

    @Override
    protected HandlerResult doHandle(CacheContext context) {
        if (!limiter.tryAcquire(context.getRedisKey())) {
            log.warn("Rate limited: key={}", context.getRedisKey());
            return HandlerResult.terminate(CacheResult.miss());  // 限流 → 当作 miss 放过
        }
        context.setAttribute("ratelimit.passed", true);          // 可选:留标记
        return HandlerResult.continueChain();                    // 继续
    }
}
```

## 步骤 3:装配

`@Component` 即可——`CacheHandlerChainFactory` 扫描所有带 `@HandlerPriority` 的 bean,按 order 排入链。**无需手动注册**。

## 步骤 4:测试

- 单元测试:构造 `CacheContext`,直接调 `handler.handle(context)`,断言 `HandlerResult`;
- 集成测试:继承 `AbstractRedisIntegrationTest`(Testcontainers Redis),验证新 handler 在真实链中的效果;
- 顺序测试:确认 `RATE_LIMIT(350)` 在 `TTL(300)` 之后、`NULL_VALUE(400)` 之前执行。

## 要点

| 关注 | 说明 |
|---|---|
| **顺序** | 用 `HandlerOrder` 空隙(150/350/450),别重排现有值 |
| **控制流** | 返回 `continueChain` / `terminate` / `skipAll`,见 [[handler-result-control]] |
| **传话** | 经 `CacheContext.setAttribute/getAttribute` 与其他 handler 协作,见 [[context-data-flow]] |
| **后置** | 需链结束后回调(如「写成功才记录」),额外 `implements PostProcessHandler`,见 [[bloom-filter]] |
| **幂等** | handler 可能被并发/重入,`shouldHandle` 用属性标记防重复(参考 [[breakdown-lock]] 的 `sync.lock.acquired`) |

## 相关

- [[chain-of-responsibility]] —— 链骨架与顺序真理源
- [[handler-result-control]] —— 三态控制流
- [[context-data-flow]] —— handler 间数据传递
- [[configuration]] —— 若需可配置开关,扩 `RedisProCacheProperties`
