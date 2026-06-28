# ADR-0002: 保留 interceptor + Advisor,弃用装饰器方案

- **Status**: Accepted(Path C 实施落地 2026-06-29,见 commit `cf4e2b1`)
- **Date**: 2026-06-27
- **Deciders**: DavidHLP
- **Related**: Q3(双 Advisor / nativeAnnotationMode),ADR-0007,Path C 7 步序列(`6fe4505`/`a42a1c1`/`ceb3901`/`a483de9`/`b377c16`/`b9d6b40`/`cf4e2b1`)

## Context

ResiCache 注册了独立的 `redisCacheAdvisor`(`BeanFactoryCacheOperationSourceAdvisor`,
order=50,见 `RedisProxyCachingConfiguration` 类)。当方法带 `@Cacheable` 时,它
与 Spring 原生 `cacheAdvisor` **都可能匹配** → 潜在双拦截(同一方法被两个 advisor 切,
缓存逻辑执行两次)。

曾考虑的修复:**装饰器方案**——删除 interceptor + Advisor,改为
`CacheManager.getCache()` 返回 `ProtectionCacheDecorator`,在 Cache 层注入防护。

### 代码证据(为何装饰器方案不可行)

防护 handler 的 `shouldHandle` 依赖 **per-method 的 `cacheOperation`**:

- `BloomFilterHandler#shouldHandle`:`context.getCacheOperation() != null && isUseBloomFilter()`
- `SyncLockHandler#shouldHandle`:`cacheOperation == null || !isSync()` → 返回 false

而 `cacheOperation` 的唯一来源是 **方法级 ThreadLocal**(Step 7 落地后归
`DefaultMethodMetadataResolver` 所有,详见 §「Path C 实施落地」),由
`ResiCacheMethodInterceptor#invoke`(继承自老 `RedisCacheInterceptor`)在每次调用时
`DefaultMethodMetadataResolver.activateStatic(method, targetClass)` 设置。
**删除 interceptor → ThreadLocal 永不填充 → `cacheOperation` 永远 null → 全部
handler 的 shouldHandle 返回 false → 责任链空转**。

即:防护能力与 interceptor 强耦合,装饰器方案会"看上去保留了 Cache 层",实际链路空转、
防护全失效。这是 Q3↔Q1 的**历史不可能三角**。

## Decision(原 2026-06-27)

1. **保留 interceptor + Advisor**(撤回装饰器方案)。
2. **`nativeAnnotationMode` 默认改为 `SELECTIVE`**(`RedisProCacheProperties#nativeAnnotationMode`、
   `RedisCacheOperationSource` 类):纯 `@Cacheable` 方法在 OperationSource 层返回
   null,`redisCacheAdvisor` 不匹配该方法 → 只有 Spring 原生 `cacheAdvisor` 匹配 →
   **从源头消解双 Advisor**,而非用装饰器错位解决。
3. `@ConditionalOnMissingBean` 退让:允许用户用自定义 bean 顶替。

## Path C 实施落地(2026-06-29 补充)

WS-1.3 Path C 7 步序列收官后,本 ADR 的"ThreadLocal 强耦合"问题有了**结构性解**——
不是绕过(SELECTIVE),而是从架构上**抽出 ThreadLocal 所有权**到一个独立的、Spring
托管的 Bean 接口,使得:
- interceptor 仍是 AOP 入口(本 ADR 决策 1 不变)
- 防护 handler 仍通过 `MethodMetadataResolver.currentKey()` 读方法元数据
  (本 ADR 代码证据 段落不变)
- **但 ThreadLocal 不再是静态类的隐藏副作用**——它是
  `DefaultMethodMetadataResolver.activateStatic/clearStatic` 显式 API,可被
  `withMethodMetadataSnapshot(Supplier)` 包装实现异步透传(supportsAsyncRetrieve=true)

### 新的数据所有权链(取代原 ThreadLocal 隐式)

```
ResiCacheMethodInterceptor#invoke
    ↓
DefaultMethodMetadataResolver.activateStatic(method, targetClass)   ← 写入(替代原 CacheOperationMetadataHolder.setCurrentKey)
    ↓
RedisProCacheWriter.buildContext / RedisProCache.lookupOperation
    ↓
methodMetadataResolver.currentKey()                                 ← 读出(原 CacheOperationMetadataHolder.getCurrentKey)
    ↓
redisCacheRegister.getCacheableOperation(cacheName, elementKey)    ← 防护 handler 拿到 per-method config
```

静态 `CacheOperationMetadataHolder` 类已在 commit `cf4e2b1` 删除。ThreadLocal
所有权从「静态类隐藏副作用」→「Spring Bean 显式 API」,**Q3↔Q1 的不可能三角不再成立**
——可以独立测试 resolver、独立替换实现(Step 6 推迟的 ScopedValue 迁移现在可直接
在 `DefaultMethodMetadataResolver` 内部替换,不动外部调用方)、异步透传在
`RedisProCacheWriter.withMethodMetadataSnapshot` 显式处理。

### 取代关系

- **本 ADR 决策 1(保留 interceptor)** 不变——`ResiCacheMethodInterceptor` 仍是
  `RedisProxyCachingConfiguration#redisCacheAdvisor` 的 advice(Step 5 切换)。
- **本 ADR 决策 2(SELECTIVE 模式)** 不变——仍由 `RedisProCacheProperties#nativeAnnotationMode`
  默认 SELECTIVE 控制,`RedisCacheOperationSourceSelectiveTest` 持续验证。
- **本 ADR 决策 3(`@ConditionalOnMissingBean` 退让)** 不变——用户可自定义
  `MethodMetadataResolver` Bean 顶替默认 `DefaultMethodMetadataResolver`
  (见 ADR-0006 长寿对冲设计 + 实际接入示例)。
- **本 ADR Context 段落** 更新为"历史不可能三角",因 Path C 落地后三角已解开
  (通过将 ThreadLocal 所有权迁到独立 Bean,实现可测试 + 可替换 + 可异步透传)。

## Consequences

- **正面**:
  - 双 Advisor 由 SELECTIVE 消解(有 `RedisCacheOperationSourceSelectiveTest`
    为证)
  - 防护链路完整(handler 能读 per-method 配置)
  - ThreadLocal 所有权从静态类迁到 Spring Bean,实现可测试 + 可替换 +
    异步透传(supportsAsyncRetrieve=true 路径已通过 `withMethodMetadataSnapshot`
    显式处理,无静默丢失)
- **取舍**:
  - `@Cacheable` 不再被 ResiCache 接管(等价原生行为)——但这正是期望语义
    (防护在 `@RedisCacheable`)。需要 FULL 模式的用户可显式
    `resi-cache.native-annotation-mode=FULL`
  - 继承面 = 2 层(`ResiCacheMethodInterceptor extends RedisCacheInterceptor extends
    CacheInterceptor`)——Step 3 决策「独立 `implements MethodInterceptor`」因
    Spring AOP 6.x `BeanFactoryCacheOperationSourceAdvisor` 对 `CacheInterceptor`
    子类有特殊处理而部分未达,Step 7 收尾时明确记录妥协形态
- **验证**:
  - `RedisCacheOperationSourceSelectiveTest` 断言 SELECTIVE 下纯 `@Cacheable`
    返回 null、`@RedisCacheable` 正常解析
  - `PathCAopContractIT` 4 tests 绿(纯 `@Cacheable` 走 ResiCache 链 +
    `@RedisCacheable` + useBloomFilter/sync/ttl 走链 + Redis 实际 TTL 严格断言)
  - `RedisProCacheWriterTest` 8 tests 绿
  - 12/12 全程保持(Path C 7 步序列验证零回归)
- **引用规范**:本 ADR 引用源码使用 `类#方法` 锚点(如 `BloomFilterHandler#shouldHandle`)
  而非 `文件:行号`——行号随重构漂移,符号锚点更稳定(CI `docs-link-check` 已校验关键类存在)。

## 后续可选工作(非本 ADR 范围)

- ADR-0002 改写的反推文档:`docs-link-check` 现在校验 wiki ADR 引用的类存在,
  本 ADR 仍引用 `BloomFilterHandler`/`SyncLockHandler` 等存在类,无需额外更新
- `PathCAopAsyncIT`(Step 6 遗留):验证 `supportsAsyncRetrieve=true` 后 async 路径
  通过 `withMethodMetadataSnapshot` 显式透传 ThreadLocal 状态
- ScopedValue 迁移(Step 6 推迟):改 `DefaultMethodMetadataResolver` 内部
  ThreadLocal 为 ScopedValue(接口契约不变,实现可热替换)
