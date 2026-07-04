---
title: "ADR-0038: CachedValue wither / HandlerPriority.order() / NoOpAnnotationChainObserver 三处零调用死代码清理"
type: adr
status: accepted
date: 2026-07-04
deciders: DavidHLP
related:
  - ADR-0037
  - ADR-0030
  - ADR-0013
  - ADR-0022
tags:
  - dead-code-removal
  - deletion-test
  - byte-equivalent
  - round-28
---

# ADR-0038: CachedValue wither / HandlerPriority.order() / NoOpAnnotationChainObserver 三处零调用死代码清理

## 状态

Accepted — 2026-07-04。

## 背景

`/improve-codebase-architecture` round 28 autocratic one-shot 深扫。Explore agent 首扫 6 候选,独裁者亲自核实(三轮红蓝对抗)发现 **3 误诊 + 1 半属实 + 2 属实**:

- ❌ 误诊 `EarlyExpirationThreadFactory`(实为 `ThreadPoolEarlyExpirationExecutor:132` 在用)
- ❌ 误诊 `NullValuePolicy` 浅模块(ADR-0025 已升格为真 seam,5 机制 policy 对称)
- ❌ 误诊 3 Factory 重复(ADR-0017/0028 已收窄至 5 行 body,三个 factory 绑定不同泛型签名 `<RedisCacheable,...>`/`<RedisCachePut,...>`/`<RedisCacheEvict,...>`,合并需 `Map<Class,Function>` 注册表 → move 而非 concentrate)
- 半属实 `TypeSupport` 跨包寄生(agent 自判 MOVES complexity,非 deletion 候选)
- ✅ 属实 `HandlerPriority.order()` deprecated 死参数
- ✅ 属实 `NoOpAnnotationChainObserver` YAGNI 单例

亲自下钻 `serialization/`(7 文件零 ADR 盲区)发现已冶炼(`WhitelistPolicy` 152 行红线设计 + Round 9 深化),非矿脉。继续下钻 `CachedValue`(280 行值对象)揪出 2 个零调用 public wither。

最终收敛 **3 处独立零调用死代码**(均 deletion test 通过):

```java
// 1. CachedValue.java —— 2 个零调用 public wither(三轮 grep 全项目 main+test 零调用)
public CachedValue withExpired() {
    return new CachedValue(value, type, ttl, createdTime, startNanoTime,
                           lastAccessTime, visitTimes, true, version);
}
public CachedValue withAccessUpdate() {
    return new CachedValue(value, type, ttl, createdTime, startNanoTime,
                           System.currentTimeMillis(), visitTimes + 1, expired, version);
}

// 2. HandlerPriority.java —— deprecated 零读取注解成员
@Deprecated
int order() default -1;

// 3. NoOpAnnotationChainObserver.java —— javadoc 自承 YAGNI 单例
//    ("当前版本不强制注入此 default(no-op 钩子已由 AnnotationChainObserver default method 提供),保留以备未来")
public enum NoOpAnnotationChainObserver implements AnnotationChainObserver {
    INSTANCE
}
```

**核实(2026-07-04,基于 working tree)**:

1. **`withExpired` / `withAccessUpdate` 全项目零调用** —— 三轮 grep(含不带括号 bare token 防反射/静态导入/字符串引用):各仅 1 hit = 定义本身。`ActualCacheHandler` 用 `CachedValue.of()` 重建(非 wither 派生),生产/测试均零引用。**纯死代码**。
2. **`HandlerPriority.order()` 零读取** —— 反射读取点仅 `CacheHandlerChainFactory:207/223`,均 `annotation.value().getDisableName()` / `annotation.value().getOrder()`(读 `HandlerOrder` 枚举的 `getOrder()`,非注解 `order()`)。`annotation.order` 全项目 0 hits。21 个 `@HandlerPriority(HandlerOrder.X)` 使用点(6 生产 + 15 测试)无一传 `order=`。**deprecated 兑现删除**。
3. **`NoOpAnnotationChainObserver` 零引用** —— `AnnotationChainObserver` 接口两钩子已是 `default` no-op(line 50-52/64-67),NoOp enum 与之语义重复且 javadoc 自承"保留以备未来"(YAGNI)。grep 全项目仅 1 hit = 定义本身。**YAGNI 单例整删**。

## 决策

**删除 3 处死代码**(byte-equivalent):

- `cache/CachedValue.java`: 删 `withExpired()` + `withAccessUpdate()`(含 javadoc)
- `chain/HandlerPriority.java`: 删 `order()` + `@Deprecated` + javadoc
- `handler/NoOpAnnotationChainObserver.java`: 整文件 `git rm`

理由:deletion test 全部干净通过 ——

- **`withExpired/withAccessUpdate`**:零调用,复杂度直接消失。值对象"不可变派生"wither 是为 LRU/访问统计预留,但 `ActualCacheHandler` 用 `of()` 直接重建,从未调用。删后读者不会被"存在派生 API"误导。
- **`order()`**:deprecated 死参数,反射只读 `value()`。删后注解只剩 `value()`,与 `HandlerOrder` 单一真理源([[0022-chain-single-representation-seam]])对齐 —— 兑现 `@Deprecated` 承诺。
- **`NoOpAnnotationChainObserver`**:接口 default method 已提供 no-op 行为,枚举单例与接口语义重复且零引用。删后"未注册 observer 时的占位"由空 observer list + default method 承担(更简洁,与 `chain.ChainObserver` 平行 seam 一致)。

## 后果

**增益**:

- `CachedValue` 接口更诚实:消除 2 个零调用 wither,降低未来维护者误以为"存在不可变派生 API"的风险。
- `HandlerPriority` 注解收敛为单一 `value()` 成员,消除 deprecated 死参数的认知负担。
- `handler/` 包减少 1 个 YAGNI 单例文件,`AnnotationChainObserver` 的 default method 是唯一 no-op 真理源。
- 净 -36 行 + 1 类文件整删。

**代价**:零(零行为变化、零序列化字段变化、零测试调整 —— 三处均零调用)。

**不变**:

- `CachedValue` 的序列化字段布局(value/type/ttl/createdTime/startNanoTime/lastAccessTime/visitTimes/expired/version)完全不变 —— `withExpired/withAccessUpdate` 是构造后派生方法,不参与 Jackson 字段序列化。Redis 已存数据 100% 兼容。
- `CachedValue` public 契约(`of`/`builder`/10 getter/`checkExpired`/`getRemainingTtl`/`getAge`/`isUsingMonotonicClock`/`Expiry`)完全不变。
- `@HandlerPriority(HandlerOrder.X)` 注解使用语义完全不变(`value()` 是唯一成员)。`CacheHandlerChainFactory` 反射装配(`getHandlerDisableName`/`getOrder`)零感知。
- `AnnotationChainEngine` 的 observer 装配零感知(NoOp 从未被注册)。

## 实施

### 修改(2 main + 1 删)

- `cache/CachedValue.java`:删 `withExpired` + `withAccessUpdate`(原 line 216-230,含 javadoc)
- `chain/HandlerPriority.java`:删 `order()` + `@Deprecated` + javadoc(原 line 24-30)
- `handler/NoOpAnnotationChainObserver.java`:`git rm`(整文件)

无 import 变化(三处均无外部引用)。

### 验证(JDK 21)

- `mvnw clean test`(相关测试覆盖:`ActualCacheHandler*` / `EarlyExpirationHandler*` / `SecureJacksonRedisSerializer*` / `SecureJacksonSerializerFactory*` / `CacheHandlerChainFactory*`)
- `mvnw checkstyle:check`
- byte-equivalent:三处删除均零调用,无调用点需内联,语义 100% 等价。

### 附带 wiki 同步(index.md ADR-0033 错位修正)

通读 `wiki/index.md` 发现 ADR-0033 条目错位到 footer 之后(0032 → 0034 之间缺 0033),系历史编辑残留。一并修正:把 0033 条目移回 0032/0034 之间,更新 footer 计数。诚实化原则,与 [[0037-twolistlru-lock-wrapper-dead-code-and-false-seam-removal]] 附带清 eviction.md stale 同源。

## 相关

- [[0037-twolistlru-lock-wrapper-dead-code-and-false-seam-removal]] —— TwoListLRU 死 wrapper 删除(本 ADR 同款 dead-code + deletion-test 模式,直接前驱)
- [[0030-redisprocachewriter-dead-accessors-removal]] —— 死 protected accessor 删除(同款)
- [[0013-annotation-chain-engine-extraction]] —— `AnnotationChainObserver` 抽出(本 ADR 删 NoOp 的接口来源,default method 是 NoOp 的语义替代)
- [[0022-chain-single-representation-seam]] —— `HandlerOrder` 单一真理源(本 ADR 删 `order()` 与之对齐)
