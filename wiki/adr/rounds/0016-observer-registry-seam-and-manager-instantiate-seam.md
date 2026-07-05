---
title: "ADR-0016: ObserverRegistry 抽出 (跨 engine observer 列表去重) + RedisProCacheManager instantiate seam"
type: adr
status: accepted
date: 2026-07-01
deciders: DavidHLP
related:
  - ADR-0009
  - ADR-0013
  - ADR-0014
  - ADR-0015
tags:
  - chain
  - handler
  - cache
  - observer
  - registry
  - deepening
  - round-6
---

# ADR-0016: ObserverRegistry 抽出 + RedisProCacheManager instantiate seam 收敛

## 状态

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0009 / ADR-0013 / ADR-0014 / ADR-0015

## 背景

`/improve-codebase-architecture` round 6 autocratic one-shot 扫描 `chain/` + `handler/` + `cache/` 三域,基于 round 1–5 已落地 ADR-0009/0010/0011/0012/0013/0014/0015 状态,筛出 2 个候选:

| 候选 | 评级 | 裁决 |
| --- | --- | --- |
| A · `ObserverRegistry<O>` 抽出(跨 engine observer 列表去重 seam) | Strong | **执行** |
| B · `RedisProCacheManager.createRedisCache` + `getMissingCache` 8 参构造委派收敛 | Worth exploring | **执行**(同 commit 合并,fixing 8-arg 重复样板) |
| C · Factory `materialize` builder 填充统一 | Worth exploring | **仍延后**(YAGNI) |
| D · `RedisCacheAttributesProjector.from(...)` 3 方法字段映射 | Worth exploring | **仍延后**(需泛型 type token) |
| E · `CacheResult.success()` ≡ `CacheResult.miss()` 同一对象 | Speculative | **不动**(微优化,非真问题) |
| F · `CacheHandlerChainFactory` 4 disabled-handler if-block | Worth exploring | **仍延后**(单文件 1 处,未达 seam 标准) |

全文精读核实后,逐条裁决如下。

## 决策

### D1 — `chain/ObserverRegistry<O>` 抽出(候选 A,执行)

**问题陈述**:`ChainEngine` 与 `AnnotationChainEngine` 两个 engine 各自持有
`CopyOnWriteArrayList<O> observers` 字段 + 重复的 `addObserver(O)` /
`observers()` 样板:

```java
// chain.ChainEngine  ---  ~30 SLOC 重复样板
private final List<ChainObserver> observers = new CopyOnWriteArrayList<>();
public void addObserver(ChainObserver o) {
    if (o == null) throw new IllegalArgumentException("observer must not be null");
    observers.add(o);
}
public List<ChainObserver> observers() { return List.copyOf(observers); }
// for (ChainObserver o : observers) { ... }  ← 3 处遍历

// handler.AnnotationChainEngine  ---  ~25 SLOC 重复样板(同构)
private final List<AnnotationChainObserver> observers = new CopyOnWriteArrayList<>();
public void addObserver(AnnotationChainObserver o) { ... }
public List<AnnotationChainObserver> observers() { ... }
// for (AnnotationChainObserver o : observers) { ... }  ← 2 处遍历
```

**形态对比**:
- 重复墙:2 engine × ~25 SLOC observer 列表样板 = ~50 SLOC 重复(若再有第 3 个
  observer-bearing engine,会继续重复)
- 模板版:1 个泛型 `ObserverRegistry<O>` = 24 SLOC code(60 SLOC total 含 Javadoc)
- 两 engine 各调用 `observers.add(o)` / `observers.snapshot()` /
  `observers.forEach(...)` — 完全同构

**deletion test**:
- 删 `ObserverRegistry` → 两 engine 恢复各自持有 `CopyOnWriteArrayList<O>`
  字段 + 重复 IAE 守卫;**两处状态机若漂移**(eg. 一个用 `ArrayList` 一个用
  `COW`,或一个加 `null` check 一个忘加)回归本 ADR 解决的问题
- 替换为 `List<O>` 直持(无 utility)→ 失去 `add` 时的 `null-check` 中心化,
  两处各自写 IAE 守卫(易漂移)

**决策**:抽出 `chain/ObserverRegistry<O>` seam。

**关键设计**:
- **泛型 `<O>`**:Observer 接口由调用方决定(ChainObserver / AnnotationChainObserver)
  ,registry 自身零 domain 依赖 — 纯泛型 utility
- **位置 `chain/` 包**:chain 是 observer 模式的发源域(本项目 5+ 生产 observer
  都在 `chain.observer`),由 `handler` 域的 `AnnotationChainEngine` 反向依赖
  本 utility 符合 "domain → utility" 的依赖方向(utility 无 domain 依赖,纯泛型)
- **API 表面**:`add(O)` / `snapshot()` / `forEach(Consumer<? super O>)` / `size()`
  4 个方法,无任何调用方 API 变化(两 engine 的 `addObserver` / `observers` /
  内部 `for` 循环全部保留兼容)
- **内部 `CopyOnWriteArrayList`**:保留原线程模型(启动期单写 + 运行期多读 +
  弱一致性迭代 — 遍历期间 add 不抛 CME)

**落地**:
- `chain/ObserverRegistry.java` 新建(60 SLOC total / 24 code-only)
- `chain/ChainEngine.java`: 移除 `CopyOnWriteArrayList` import + 字段替换
  + `addObserver` body 改为 `observers.add(o)`(1 行) + `observers()` body
  改为 `observers.snapshot()`(1 行) + 3 处 `for` 循环改为 `observers.forEach(...)`
  (净代码 -16 SLOC)
- `handler/AnnotationChainEngine.java`: 同上模式(净代码 -3 SLOC)
- `import io.github.davidhlp.spring.cache.redis.chain.ObserverRegistry` 新增(handler 包)

### D2 — `RedisProCacheManager.instantiateRedisProCache(name, cfg)` 抽出(候选 B,执行)

**问题陈述**:`createRedisCache` 与 `getMissingCache` 两个 protected 方法各自
调用 8 参 `new RedisProCache(...)`,仅 `resolveCacheConfiguration(...)` 参
不同(一个传 `cacheConfiguration`、一个传 `null`):

```java
// 重复样板:8 参构造调用 × 2 处,只有 cfg 解析不同
@Override
protected RedisCache createRedisCache(String name, RedisCacheConfiguration cfg) {
    return new RedisProCache(
            name, redisProCacheWriter, resolveCacheConfiguration(cfg),
            meterRegistry, bloomSupport, redisCacheRegister,
            syncSupport, methodMetadataResolver);
}
@Override
protected RedisCache getMissingCache(String name) {
    return new RedisProCache(
            name, redisProCacheWriter, resolveCacheConfiguration(null),
            meterRegistry, bloomSupport, redisCacheRegister,
            syncSupport, methodMetadataResolver);
}
```

**形态对比**:
- 重复墙:8 SLOC × 2 处 = 16 SLOC,其中 14 SLOC 是同构样板
- 模板版:1 个 `instantiateRedisProCache(name, cfg)` = 12 SLOC body
- **Spring 框架是扩展点**:Spring 4.0/5.0 多次为 `RedisCache` 构造增减参数
  (eg. `RedisCacheConfiguration` 8.x → 9.x),本类 8 参调用是脆弱接触面 — 参数
  漂移时两处必漏改一边

**deletion test**:
- 删 `instantiateRedisProCache` → 两 protected 方法恢复各自 8 参调用;
  Spring 框架增删 `RedisCache` 构造参数时(本类是扩展点,易受影响)必漏改一边
- 替换为 Lombok `@Builder` on `RedisProCache` → 失去显式参数顺序,
  Spring 反射调用方可能不识别

**决策**:抽出 `private RedisProCache instantiateRedisProCache(name, cfg)` seam。

**关键设计**:
- **位置 `RedisProCacheManager` 私有方法**:本 helper 仅本 manager 内部用,
  不暴露 public API(与 `resolveCacheConfiguration` 同模式)
- **API 表面**:`createRedisCache` / `getMissingCache` 签名零变化;
  `resolveCacheConfiguration` 保持(配置归一化仍在)
- **零调用方修改**:两 protected 方法各自 8 参调用 → 1 行 helper 委派
  (净代码 -5 SLOC)

**落地**:
- `RedisProCacheManager.java`: 新增 `instantiateRedisProCache(name, cfg)` 私有方法
  (12 SLOC body) + `createRedisCache` / `getMissingCache` body 改为 1 行委派
  (净代码 -5 SLOC)

### D3 — Factory `materialize` builder 填充统一(候选 C,继续延后,YAGNI)

**未执行原因**:
- Round 5 / ADR-0015 同源 YAGNI 决策
- 需 Spring `CacheableOperation.Builder` / `CachePutOperation.Builder` /
  `CacheEvictionOperation.Builder` 共建泛型接口 — Spring 父类未设计扩展,需要
  `@Delegate` Lombok 注解增加 runtime 开销
- 当前 3 factory 的 `materialize` 方法虽然 SLOC 略高,但每处都是单行
  `.field(attrs.getField())` 映射,**可读性高于抽象接口的反射成本**
- **触发条件**:下次新增字段时(eg. `ResiCache v0.2.0` 加新 mechanism 字段)若
  3 处 `materialize` 全部需改 → 重审本候选

### D4 — `RedisCacheAttributesProjector.from(...)` 3 方法字段映射(候选 D,继续延后)

**未执行原因**:
- 3 方法 90% 字段映射相同,但每个 `from(annotation)` 必须按 annotation 类型
  调用不同 getter(eg. `annotation.cacheNullValues()` 在
  `RedisCacheable` / `RedisCachePut` 上有,`RedisCacheEvict` 上无 — 投影时给
  false 默认)
- 统一需要 3 种 annotation 的公共接口或泛型 type token — 前者要求用户实现
  公共接口(破坏 annotation 兼容性),后者反射开销
- **deletion test**:删 1 个 `from(...)` 方法 → 该 annotation 类型的投影失败
  → 失败(挣得起存在)
- **触发条件**:新增第 4 个 `ResiCache*` annotation 时(目前没有该计划)→ 重审

### D5 — `CacheResult.success()` ≡ `CacheResult.miss()` 同一对象(候选 E,不动)

**事实**:两个静态工厂返回对象完全相同:
```java
public static CacheResult success() {
    return CacheResult.builder().success(true).hit(false).build();
}
public static CacheResult miss() {
    return CacheResult.builder().success(true).hit(false).build();
}
```

**不动的理由**:
- 2 SLOC 微优化,非真问题
- `miss()` 命名是 "cache 未命中" 语义,`success()` 是 "操作成功" 语义 — 同
  对象但语义不同(Spring `Cache.ValueWrapper` miss 时也是 success)
- 合并会破坏调用方 `result.isHit()` / `result.isSuccess()` 周围的语义可读性
- 留作未来"miss() 改为带 hit=false 显式标记"的扩展点

### D6 — `CacheHandlerChainFactory` 4 disabled-handler if-block(候选 F,继续延后)

**未执行原因**:
- 4 个 `if (Boolean.FALSE.equals(protection.getXxxEnabled()))` 单文件单处,
  已是局部最优(集中在一个 `createChain` 方法内)
- 抽象成 `Map<HandlerOrder, Supplier<Boolean>>` 或 `List<HandlerOrder> disabledOrders`
  会增加配置类复杂度,反而降低可读性
- **deletion test**:删 4 if-block → `protection.enabled=false` 路径失效
  → 失败(挣得起存在)
- **触发条件**:新增第 5 个 protection 机制时 → 4 → 5 if-block 重审

## 后果

### D1 + D2 增益

- **observer 列表管理单一 seam**:两 engine 共享 `ObserverRegistry<O>`,新增
  第 3 个 observer-bearing engine 时直接复用,**1 行委派而非 25 SLOC 重复**
- **代码净变化 -24 SLOC**:两 engine + manager 共减 -24 SLOC(去重),
  `ObserverRegistry` 净增 +24 SLOC,**净 0 但 leverage +∞**
- **测试集中化**:`ObserverRegistryTest` 8 个 contract 测试覆盖 observer 列表
  全部行为(add / snapshot / forEach / size / 线程安全 / null-check / 重复注册);
  两 engine 测试零修改(API 兼容)
- **Spring 框架扩展点韧性**:`RedisProCacheManager.instantiateRedisProCache`
  8 参调用收敛为 1 处 — Spring `RedisCache` 构造参数漂移时改 1 处而非 2 处
- **零公开 API 变化**:两 engine 的 `addObserver` / `observers` /
  `RedisProCacheManager.createRedisCache` / `getMissingCache` 签名零变化,
  全部现有测试零修改

### D1 + D2 代价

- **新增 1 个 public class**:`ObserverRegistry<O>`,API 表面 +4 方法
  (add / snapshot / forEach / size)
- **handler 包 → chain 包新依赖**:`AnnotationChainEngine` 现在 import
  `chain.ObserverRegistry`(此前 `handler` 与 `chain` 包零依赖)
- **零破坏性变更**

## 触发重评估

- 新增第 3 个 observer-bearing engine(目前无该计划)— ObserverRegistry 复用
  兑现 leverage
- Spring 框架 `RedisCache` 构造参数变化(高频)— instantiate seam 兜底
- WS-1.4 OTel Span 升级(ADR-0008 关联)— 现有 ObserverRegistry.forEach 不变,
  新增 `SpanObserver implements ChainObserver` 即可(本 ADR 不阻塞)

## 参考

- ADR-0009:ChainEngine 抽出(平行 seam 模式先例)
- ADR-0013:AnnotationChainEngine 抽出(本 ADR 复用其 observer list 模式)
- ADR-0014:Constructor telescoping collapse(单 seam 收敛模式先例)
- ADR-0015:AnnotationHandler registerAll 模板下沉(模板下沉模式先例)
