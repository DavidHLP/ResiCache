# ADR-0012: Path C Interceptor 残骸收敛 + EarlyExpirationSupport 浅模块删除(+ Spring 注解映射不合并裁决)

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0002 / ADR-0009 / `/tmp/architecture-review-1782837301.html`(round 3,候选 A/B/C)
- **Supersedes**: (局部)Path C Step 4/5/7 残骸类 `CacheAspectSupportHelper` / `ResiCacheMethodInterceptor`

---

## 背景

`/tmp/architecture-review-1782837301.html`(round 3)在前两轮(已落地 ADR-0009/0010/0011)未曾审视的
`cache/` interception、`protection/refresh`、`annotation` 映射三域筛出 3 个候选:

| 候选 | 评级 | 裁决 |
| --- | --- | --- |
| A · Path C interceptor 残骸收敛 | Strong | **执行** |
| B · `EarlyExpirationSupport` 浅模块删除 | Worth exploring | **执行**(全文核验后) |
| C · Spring 注解→operation 映射去重 | Worth exploring | **撤销**(语义不同,不合并) |

全文精读核实后,逐条裁决如下。

---

## 决策

### D1 — Path C interceptor 残骸收敛(候选 A,执行)

Path C(WS-1.3)经 Step 4 → 5 → 7 三次方案切换,每次"加新类、留旧类",遗留两个冗余:

| 类 | 状态 | 证据 |
| --- | --- | --- |
| `CacheAspectSupportHelper` | **死代码** | 全仓零代码引用 + JaCoCo `covered=0/0` + 不在任何 `@Bean` + 非 advisor 链所用 |
| `ResiCacheMethodInterceptor` | **pass-through** | `invoke()` 仅 `return super.invoke(invocation)`;构造函数 15 行仅把 8 依赖转发给 `super` |

只有 `RedisCacheInterceptor.invoke()` 真正干活(reactive bypass + `activateStatic` + `handlerChain.handle` + `super.invoke`)。

**落地**:

- 删 `cache/CacheAspectSupportHelper.java`(零行为变化,JaCoCo 铁证)
- 删 `cache/ResiCacheMethodInterceptor.java`,其构造函数 setter 转发(`setCacheOperationSource`/
  `setCacheManager`/`setKeyGenerator`/`afterPropertiesSet`)收口进 `RedisCacheInterceptor` 构造函数
- `RedisProxyCachingConfiguration`:`resiCacheMethodInterceptor` bean 改名 `redisCacheInterceptor`,
  返回 `RedisCacheInterceptor`;advisor `setAdvice(redisCacheInterceptor)` 直接持有
- 清理 dead-injection 参数(原 bean 方法注入了 `redisCacheRegister`/`redisProCacheProperties` 但从未传入构造)

**结果**:`cache/` 拦截器 3 类 → 1 类(~129 SLOC 消失),继承面 3 层 → 2 层(`RedisCacheInterceptor` →
`CacheInterceptor`),与 ADR-0002(keep-interceptor)一致——仍是 interceptor+Advisor 路径,仅少一层残骸。

### D2 — `EarlyExpirationSupport` 浅模块删除(候选 B,执行)

`EarlyExpirationSupport`(`@Component`)5 个 public 方法**全部一行委派**给
`ThreadPoolEarlyExpirationExecutor`,且:

- `submit`/`cancel` 的 null guard 与 Executor **重复**(`Executor.submit` L110、`Executor.cancel` L155 已自带)
- `getThreadPoolStats`/`getRefreshingKeyCount` **无 main 消费者**(仅 `EarlyExpirationSupportTest` 自测)= dead API
- `shutdown`(`@PreDestroy`)纯转发——`Executor.shutdown()` **本就标 `@PreDestroy`**(L220)

→ Support 是 100% 冗余转发层。删除测试:删它 → 两 handler 直注 Executor,复杂度净减且不重现。

**落地**:

- `EarlyExpirationHandler` / `ActualCacheHandler`:字段 `EarlyExpirationSupport` → `ThreadPoolEarlyExpirationExecutor`,
  调用 `submitAsyncRefresh`/`cancelAsyncRefresh` → `submit`/`cancel`
- 删 `EarlyExpirationSupport.java` + `EarlyExpirationSupportTest.java`
- 3 个测试(`EarlyExpirationHandlerTest` / `EarlyExpirationHandlerRaceConditionTest` /
  `ActualCacheHandlerTest`)mock 同步重命名

**行为微变**:`Support.submitAsyncRefresh` 在 null 输入时 `log.warn`,Executor.submit 在 null 时 silent return。
但调用方(`EarlyExpirationHandler.scheduleAsyncRefresh`)传入的 `redisKey` 与 task lambda 不可能为 null,
故实际无影响——null 输入属编程错误,不应依赖 warn。

### D3 — Spring 注解映射**不**合并(候选 C,撤销)

round 3 报告称 `SpringAnnotationAdapter`(操作源解析)与 `SpringCacheableAdapterFactory`(操作执行)
字段填充重复。**全文精读后纠正此判断**:

| 路径 | 产出类型 | 消费者 |
| --- | --- | --- |
| `SpringAnnotationAdapter.buildRedisCacheableOperation` | Spring `org.springframework.cache.interceptor.CacheableOperation` | Spring AOP `CacheAspectSupport`(拦截决策) |
| `SpringCacheableAdapterFactory.materialize` | ResiCache `RedisCacheableOperation` | ResiCache 责任链(执行) |

两路径产出**不同类型**给**不同消费者**。deletion test:删任一,另一个无法替代。强行合并需引入
"Spring 注解 → 中间 attributes → 两种 Builder"双向映射,反增复杂度。**不合并即唯一最优解**,
本 ADR 显式封口,避免未来 re-suggest。

---

## 后果

**增益**:

- Path C interception seam 收敛到单一 advice(`RedisCacheInterceptor`),未来改拦截行为只动一处
- `protection/refresh` 包消除一层无语义转发,两 handler 直面有状态的 Executor(locality + leverage)
- 净删 4 文件 + ~200 SLOC,继承面与依赖链同步简化

**代价**:

- `EarlyExpirationSupport.submitAsyncRefresh` 的 null-warn 行为消失(实际无影响,见 D2)
- ADR-0002 的 Step 4/5/7 历史叙事类被删——本 ADR 取代其残骸定位

**不变**:

- ADR-0002 interceptor+Advisor 路径
- ADR-0009 ChainEngine/Observer seam
- ADR-0010 投影层 + SpringCacheableAdapterFactory
- `@RedisCacheable` / Spring `@Cacheable` 双路径契约

---

## 实施

### 删除(4 文件)

- `cache/CacheAspectSupportHelper.java`(-65 SLOC)
- `cache/ResiCacheMethodInterceptor.java`(-64 SLOC)
- `protection/refresh/EarlyExpirationSupport.java`(-69 SLOC)
- `test/.../EarlyExpirationSupportTest.java`

### 修改

- `cache/RedisCacheInterceptor.java` — 吸收 setter 转发,成为 advisor 直接持有的 advice
- `config/RedisProxyCachingConfiguration.java` — bean 改名 + 返回类型 + 清理 dead-injection 参数
- `protection/refresh/EarlyExpirationHandler.java` — 字段/参数/调用改直注 Executor
- `chain/ActualCacheHandler.java` — import/字段/调用改直注 Executor
- `test/.../EarlyExpirationHandlerTest.java` / `EarlyExpirationHandlerRaceConditionTest.java` /
  `ActualCacheHandlerTest.java` — mock 类型/方法名同步重命名

### 验证

- `mvnw test-compile` — PASS(main + test 零编译错误)
- `mvnw test`(全量 unit)— **BUILD SUCCESS,0 failures,0 errors**;
  关键契约零回归:Path C AOP 契约(`纯 Spring @Cacheable 经 ResiCache 链路`)+ EarlyExpiration
  竞态/异步/同步 + ActualCache PUT-cancel + ChainFactory 装配
- **限制**:Testcontainers 集成测试(`mvnw verify` 的 failsafe 阶段)需 Docker 环境,本轮本地未跑;
  unit test 已覆盖编译 + 直接受影响逻辑,IT 由 CI(Docker 可用)兜底

---

**最后更新**:2026-07-01
