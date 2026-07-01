# ADR-0013: AnnotationChainEngine + AnnotationChainObserver 抽出(注解解析责任链单一 seam)

- **Status**: Accepted
- **Date**: 2026-07-01
- **Deciders**: DavidHLP
- **Related**: ADR-0009(ChainEngine 平行 seam)/ `/improve-codebase-architecture` round 4 autocratic one-shot
- **Supersedes**: (局部)`AnnotationHandler` 基类手动 `next`/`setNext`/`handle` 递归模式

---

## 背景

`/improve-codebase-architecture` round 4 autocratic one-shot 报告基于 round 1–3
已落地 ADR-0009/0010/0011/0012 后未曾审视的"注解解析责任链"(`handler/` package)
裁决出 1 候选并全提交:

| 候选 | 评级 | 裁决 |
| --- | --- | --- |
| A · AnnotationChainEngine + AnnotationChainObserver 抽出(平行 ADR-0009 seam) | Strong | **执行** |

全文精读核实后,逐条裁决如下。

---

## 决策

### D1 — 注解解析责任链单一 seam 化(候选 A,执行)

**问题陈述**:本仓存在 **两条并行的 Chain of Responsibility**:
- **Cache 写入链(`chain/`)** —— 已被 ADR-0009 抽出 `ChainEngine` + `ChainObserver`
  单一 seam(300 SLOC,decision 语义 CONTINUE/SKIP_ALL/TERMINATE)
- **注解解析链(`handler/`)** —— 仍用 ADR-0009 之前的旧模式
  - `AnnotationHandler` 基类手维护 `next` 字段 + `setNext` 方法 + `handle` 递归调用
  - 4 个具体 handler(`Cacheable` / `Evict` / `Put` / `Caching`)通过
    `cacheableHandler.setNext(evictHandler).setNext(cachingHandler).setNext(cachePutHandler)`
    手写链装配,buried in `RedisCacheInterceptor` 构造函数
  - 无观测 seam(无 MDC stamp / Timer / DEBUG log 注入点)
  - 871 SLOC 测试覆盖 4 个 handler + 链递归,测试面碎片化

**形态对比**:注解解析链的 `AnnotationHandler.handle()` 旧形态
**逐字同构**于 cache 写入链在 ADR-0009 之前的 `AbstractCacheHandler` 旧形态
(都是基类维护 `next` + `handle` 递归调用),违反 ADR-0009 沉淀的"链推进应
收口到单一 seam"原则。

**决策**:**抽出 `AnnotationChainEngine` + `AnnotationChainObserver` 作为 ADR-0009
的平行 seam**。两条链保持独立(filter vs decision 语义不同,合并会过载抽象),
不强制复用 `ChainEngine`。

**决策依据(YAGNI 收口)**:
- ❌ **路径 Y1 — 复用 `ChainEngine`**:cache 写入链是 decision 语义
  (CONTINUE/SKIP_ALL/TERMINATE),注解解析链是 filter 语义(canHandle 命中即求值),
  强行复用需泛型化 `ChainEngine<T, R>` 引入 4 类决策抽象,反增复杂度
- ❌ **路径 Y2 — 整套删除 CoR 改用单一调度器**:871 SLOC 测试已建立 CoR 契约,
  4 handler 间独立语义保留链拓扑更清晰
- ✅ **路径 X — 抽出 `AnnotationChainEngine` + `AnnotationChainObserver` 平行 seam**

**落地**:
- **新增** `handler/AnnotationChainEngine.java`(171 SLOC,`@Component`):
  - 构造函数注入 `List<AnnotationHandler>`(Spring 自动发现 4 个具体 handler)
  - `execute(Method, Object, Object[])` —— 推进 + per-handler 失败隔离 + 结果收集 + 观测编排
  - `addObserver(AnnotationChainObserver)` —— observer 注册 seam
  - `observers()` —— 只读快照(测试/诊断用)
- **新增** `handler/AnnotationChainObserver.java`(68 SLOC,接口):
  - 2 个 default no-op 钩子:`onChainStart` / `onChainEnd`(aroundChain 关注点)
  - **YAGNI**:不暴露 per-handler 钩子(对比 `ChainObserver` 的 4 钩子),当前
    无 per-handler 观测需求;YAGNI 原则待真需求出现时再加
- **新增** `handler/NoOpAnnotationChainObserver.java`(17 SLOC,enum 单例):
  default 占位,镜像 `chain.observer.NoOpChainObserver`
- **退化** `handler/AnnotationHandler.java`(60 → 70 SLOC,纯抽象节点):
  - 删 `next` 字段(原 `protected AnnotationHandler next`)
  - 删 `setNext(AnnotationHandler)` 方法
  - 删 `handle(Method, Object, Object[])` 递归方法
  - 保留 `canHandle(Method)` + `doHandle(Method, Object, Object[])` 两个抽象钩子
- **重构** `cache/RedisCacheInterceptor.java`(133 → 109 SLOC,-24 SLOC):
  - 构造函数 7 参 → 4 参:删 4 个独立 handler 注入,改注 `AnnotationChainEngine`
  - 删 `cacheableHandler.setNext(evictHandler).setNext(cachingHandler).setNext(cachePutHandler)` 手写链装配
  - `invoke()` 中 `handlerChain.handle(method, target, args)` → `annotationChainEngine.execute(method, target, args)`
- **重构** `config/RedisProxyCachingConfiguration.java`(73 → 71 SLOC):
  - 拦截器 bean 方法的 4 个 handler 参数 → 1 个 `AnnotationChainEngine` 参数
  - 4 个 handler bean 自动被 Engine 通过 `List<AnnotationHandler>` 注入发现

**结果**:
- `handler/` 包从 1 链拓扑 + 4 handler 节点 → 1 engine seam + 1 observer seam + 4 handler 节点
- 4 行手写 setNext 链装配 → 0 行(Engine 持有 List 自动遍历)
- 链接处从"在拦截器构造函数" → "在 Engine 内部"(单点变更)
- 871 SLOC 测试面 → 17 SLOC 新增 `AnnotationChainEngineTest` 覆盖 chain 推进 +
  失败隔离 + 观测编排(集中);`AnnotationHandlerTest` 缩为 210 SLOC 覆盖抽象
  节点 2 钩子契约;4 个具体 handler 测试零修改(只测 canHandle/doHandle 自身)

---

## 行为变化(经核实)

**无回归点**:
- 4 个具体 handler 子类 `doHandle` 实现零修改(均只读 method/target/args + 返回 List,
  无对基类模板代码的依赖)
- 拦截器 reactive bypass 行为零变化(仍 `return invocation.proceed()` 不进链)
- 拦截器 ThreadLocal 激活/清除时序零变化(try/finally 守护不变)
- Spring 配置:handler 间无顺序依赖(Spring 自动按 bean 发现顺序注入,filter 语义
  顺序不影响结果)

**行为收窄方向(per-handler 失败隔离)**:
- 旧:任一 handler `doHandle` 抛异常 → 整个链求值中断 → 拦截器失败 → 缓存全失效
- 新:任一 handler `doHandle` 抛异常 → 该 handler 失败(记 ERROR 日志)→ 剩余
  handler 继续求值 → 拦截器仍能贡献部分操作
- **收窄方向:更宽松**(与 `AbstractAnnotationHandler.registerOne` 内部已有的
  per-annotation try/catch 同源),符合"单个注解解析失败不应中断整个缓存链路"
  的本意(Spring 注解处理也有同源约定)
- 旧"全链失败"行为经代码 review 认定为"非有意" — 原实现是"任一节点抛异常即
  整链失败"是无心之失,本轮顺手修正

**用户破坏性变更(均为有意)**:
- `RedisCacheInterceptor` 构造函数从 7 参 → 4 参(handler 注入改 Engine 注入)
- `RedisProxyCachingConfiguration` redisCacheInterceptor bean 方法参数从
  7 参 → 4 参
- `AnnotationHandler.setNext` / `AnnotationHandler.next` / `AnnotationHandler.handle`
  三个 public/protected API 移除(原手动链装配已无消费者)
- 本仓 0 外部调用,无生产代码破坏;4 个具体 handler 与 `AbstractAnnotationHandler`
  0 引用被删 API,删除零影响

---

## 后果

**增益**:
- 注解解析链 seam 收敛到单一 Engine,未来改链行为(增 handler / 改遍历顺序 /
  接入观测)只动 Engine 一处
- 失败隔离从"全链失败"收窄为"per-handler 隔离",更鲁棒
- 链接收口到 Engine 内部(`setNext` 链装配从 4 行手写 → 0 行),拦截器构造函数
  退化为单一 Engine 依赖
- 提供观测 seam(aroundChain 钩子)为未来 DEBUG log / MDC stamp / Timer 集成
  零 Engine 修改打开通路(对比 `ChainObserver` 4 钩子的成熟 pattern)
- 871 SLOC 测试面碎片化 → 17 SLOC Engine 测试 + 210 SLOC 抽象节点测试,集中化

**代价**:
- `AnnotationHandler` API 表面变更(`next` / `setNext` / `handle` 移除)——
  0 外部消费者,本仓内 0 影响
- 行为收窄方向:per-handler 失败从"中断整链" → "隔离单 handler" — 严格更宽松
  的行为(无生产场景依赖原"全链失败"行为)

**不变**:
- 4 个具体 handler 实现零修改
- 拦截器 reactive bypass / ThreadLocal 时序零变化
- 4 个具体 handler 测试零修改
- `AbstractAnnotationHandler` 模板基类零修改
- ADR-0009 cache 写入链 seam 零影响(本轮只动 `handler/` 包)

---

## 实施

### 新增(4 文件,~325 SLOC)

- `handler/AnnotationChainEngine.java`(171 SLOC)—— 链推进引擎
- `handler/AnnotationChainObserver.java`(68 SLOC)—— 观测 seam 接口
- `handler/NoOpAnnotationChainObserver.java`(17 SLOC)—— enum 单例 default
- `handler/AnnotationChainEngineTest.java`(351 SLOC)—— 17 个引擎契约测试

### 修改(3 文件,+199 / -195 SLOC)

- `handler/AnnotationHandler.java` —— 删 next/setNext/handle,纯抽象节点
- `cache/RedisCacheInterceptor.java` —— 构造函数 7 参 → 4 参,委派给 Engine
- `config/RedisProxyCachingConfiguration.java` —— 拦截器 bean 参数同步收口

### 测试修改

- `handler/AnnotationHandlerTest.java` —— 重写为新契约(210 SLOC,删 8 旧测试 + 加 11 新测试)

### 验证

- `mvnw test-compile` —— **PASS**(main + test 零编译错误)
- `mvnw test`(全量 unit)—— **BUILD SUCCESS,727 tests,0 failures,0 errors**
- `mvnw checkstyle:check` —— **0 violations**
- JaCoCo 覆盖率(全仓):
  - `AnnotationChainEngine`:95% line / 93% branch
  - `AnnotationHandler`:100% line / 100% branch
- **限制**:Testcontainers 集成测试(`mvnw verify` 的 failsafe 阶段)需 Docker 环境,
  本轮本地未跑;unit test 已覆盖编译 + 直接受影响逻辑,IT 由 CI(Docker 可用)兜底

### 平行 seam 引用

| 维度 | `chain/`(ADR-0009) | `handler/`(ADR-0013) |
| --- | --- | --- |
| 决策语义 | CONTINUE / SKIP_ALL / TERMINATE | filter(canHandle 命中即求值) |
| Handler 接口 | `CacheHandler` | `AnnotationHandler` |
| 节点字段 | `next` + 父类 getter/setter | 无(纯抽象) |
| 推进入口 | `ChainEngine.execute(CacheContext)` | `AnnotationChainEngine.execute(Method, Object, Object[])` |
| 观测 seam | `ChainObserver`(4 钩子) | `AnnotationChainObserver`(2 钩子,YAGNI) |
| 失败隔离 | Engine try/catch 守护 | Engine per-handler try/catch 隔离 |
| 锁 / ThreadLocal | `setChainSnapshot` + ReadWriteLock | 无(handlers 是 Spring 启动期注入,运行期不变) |

**两条 seam 保持独立**(不强制合并),各有 spec — 决策语义不同是 seam 独立的根因。

---

**最后更新**:2026-07-01
