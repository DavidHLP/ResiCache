# ADR-0002: 保留 interceptor + Advisor,弃用装饰器方案

- **Status**: Accepted
- **Date**: 2026-06-27
- **Deciders**: DavidHLP
- **Related**: Q3（双 Advisor / nativeAnnotationMode）

## Context

ResiCache 注册了独立的 `redisCacheAdvisor`(`BeanFactoryCacheOperationSourceAdvisor`,
order=50,见 `RedisProxyCachingConfiguration.java:39`)。当方法带 `@Cacheable` 时,它
与 Spring 原生 `cacheAdvisor` **都可能匹配** → 潜在双拦截(同一方法被两个 advisor 切,
缓存逻辑执行两次)。

曾考虑的修复:**装饰器方案**——删除 interceptor + Advisor,改为
`CacheManager.getCache()` 返回 `ProtectionCacheDecorator`,在 Cache 层注入防护。

### 代码证据(为何装饰器方案不可行)

防护 handler 的 `shouldHandle` 依赖 **per-method 的 `cacheOperation`**:

- `BloomFilterHandler.shouldHandle`(`BloomFilterHandler.java:46`):
  `context.getCacheOperation() != null && isUseBloomFilter()`
- `SyncLockHandler.shouldHandle`(`SyncLockHandler.java:57`):
  `cacheOperation == null || !isSync()` → 返回 false

而 `cacheOperation` 的唯一来源是 `CacheOperationMetadataHolder` 的 ThreadLocal,由
`RedisCacheInterceptor.invoke`(`RedisCacheInterceptor.java:60`)在每次调用时
`setCurrentKey(method, targetClass)` 设置。**删除 interceptor → ThreadLocal 永不填充 →
`cacheOperation` 永远 null → 全部 handler 的 shouldHandle 返回 false → 责任链空转**。

即:防护能力与 interceptor 强耦合,装饰器方案会"看上去保留了 Cache 层",实际链路空转、
防护全失效。这是 Q3↔Q1 的不可能三角。

## Decision

1. **保留 interceptor + Advisor**(撤回装饰器方案)。
2. **`nativeAnnotationMode` 默认改为 `SELECTIVE`**(`RedisProCacheProperties.java:79`、
   `RedisCacheOperationSource.java:43`):纯 `@Cacheable` 方法在 OperationSource 层返回
   null,`redisCacheAdvisor` 不匹配该方法 → 只有 Spring 原生 `cacheAdvisor` 匹配 →
   **从源头消解双 Advisor**,而非用装饰器错位解决。
3. `@ConditionalOnMissingBean` 退让:允许用户用自定义 bean 顶替。

## Consequences

- **正面**:双 Advisor 由 SELECTIVE 消解(有 `RedisCacheOperationSourceSelectiveTest`
  为证);防护链路完整(handler 能读 per-method 配置);变更面小。
- **取舍**:`@Cacheable` 不再被 ResiCache 接管(等价原生行为)——但这正是期望语义
  (防护在 `@RedisCacheable`)。需要 FULL 模式的用户可显式
  `resi-cache.native-annotation-mode=FULL`。
- **验证**:`RedisCacheOperationSourceSelectiveTest` 断言 SELECTIVE 下纯 `@Cacheable`
  返回 null、`@RedisCacheable` 正常解析。
