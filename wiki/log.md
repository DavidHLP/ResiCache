---
title: 操作日志
type: meta
tags:
  - meta
  - 日志
  - 历史
related: [index, overview, README]
status: stable
created: 2026-06-21
updated: 2026-06-21
---


# 操作日志

wiki 演化的时间线,append-only。条目格式 `## [YYYY-MM-DD] <op> | <subject>`(op ∈ init / ingest / query / lint)。

> 解析最近条目:`grep "^## \[" log.md | tail -5`

---

## [2026-06-21] lint | 发现 CLAUDE.md / README 的 stale facts

建库过程中以**实际源码**核查,发现项目文档(`CLAUDE.md`、`README.md` 的 Project Structure)描述了若干在重构后已不存在的模块。wiki 全程以代码为准,不照抄文档。

已移除/不存在的目录与类(对比文档声明):

| 文档声称存在 | 实际状态 |
|---|---|
| `wrapper/`(`CircuitBreakerCacheWrapper`、`RateLimiterCacheWrapper`) | **不存在** |
| `spi/`(`BloomFilterProvider`、`LockProvider`、`RedissonLockProvider`) | **不存在** |
| `event/`(`CacheEvictedEvent`) | **不存在** |
| `evaluator/`(`SpelConditionEvaluator`) | **不存在** |
| `observability/CacheMetricsRecorder` | **不存在**(仅 `RedisCacheHealthIndicator`) |

处理:

- 未为这些创建 wiki 页;
- `mechanisms/bloom-filter.md`、`mechanisms/breakdown-lock.md` 原拟写的「SPI 可替换」章节,改为基于真实代码的「Spring bean 注入 + `@ConditionalOnMissingBean`」;
- `modules/observability.md`、`concepts/cache-avalanche.md`、`modules/cache-core.md`、`overview.md` 均注明此差异;
- 实际可替换性:`BloomIFilter` 三实现与 `LockManager` 均为普通 `@Component`,通过 bean 覆盖即可,无 Java ServiceLoader。

根因:`a5ab55b refactor: remove dead code and simplify over-engineering` 删除了这些过度工程,但 `CLAUDE.md`/`README` 未同步。建议后续 ingest 时同步修正这两份文档(本 wiki 不改源码,只记录)。

实际存在的包(90 个主源文件):`annotation` `cache` `chain(/model)` `config` `eviction` `factory` `handler` `holder` `observability` `operation` `protection/{avalanche,bloom(/filter),breakdown,nullvalue,refresh}` `serialization`。

---

## [2026-06-21] init | 创建 ResiCache LLM Wiki 知识库

从零建立 `wiki/`,共 28 个 markdown 文件:

- 3 meta:`README.md`(schema)、`index.md`、`log.md`
- 1 概览:`overview.md`
- 5 架构页:`chain-of-responsibility`、`cache-lifecycle`、`context-data-flow`、`handler-result-control`、`auto-configuration`
- 5 机制页:`bloom-filter`(100)、`breakdown-lock`(200)、`early-expiration`(250)、`ttl-jitter`(300)、`null-value`(400)
- 8 模块页:`cache-core`、`annotations`、`operations`、`configuration`、`serialization`、`observability`、`eviction`、`holder-and-config`
- 4 概念页:`cache-penetration`、`cache-breakdown`、`cache-avalanche`、`hot-key`
- 2 指南页:`add-protection-handler`、`configure-behavior`

事实来源:全部经 CodeGraph(`codegraph_explore`)+ 源码核查确认,关键设计(责任链 `HandlerOrder`、`AbstractCacheHandler.handle` 模板、`SyncLockHandler` 锁内聚、`EarlyExpirationHandler` Lua CAS、`TtlHandler` 抖动、`BloomFilterHandler` PostProcess、`DistributedLockManager` leaseTime 计算)均引自当前源码。全中文撰写,技术标识符保留原文。

约定:wikilink `[[slug]]`(slug=文件名 kebab-case);源码引用 `src/.../Foo.java:行`;frontmatter 含 title/category/tags/related/source-files/updated。

---

## [2026-06-21] ingest | 将 `docs/wiki/` 提升为顶层 `wiki/`

`docs/wiki/` 的两层嵌套(`docs` 再包 `wiki`)对仓库布局无价值,反而要求所有引用多写一段路径。本次 ingest 将整个 `docs/wiki/` 目录 `git mv` 到顶层 `wiki/`,同步清理位于 `docs/` 下的 stale Obsidian vault 配置(`.obsidian/`,被 .gitignore 忽略),最终 `docs/` 目录被删除。

影响:

- `git mv docs/wiki wiki` —— 31 个 git 跟踪文件(30 md + 1 canvas)带历史一并迁移;随目录移动的还有 `wiki/.obsidian/`(5 个 untracked 配置文件,被 `.gitignore` 忽略);
- `CLAUDE.md`、`README.md` 中 `docs/wiki/` 路径全部改写为 `wiki/`;同时移除一条指向 `docs/CODEMAPS/dependencies.md` 的死链(该文件已在 `9025e16` 删除,完整依赖矩阵现位于 `COMPATIBILITY.md`);
- `wiki/README.md`、`wiki/log.md`、`wiki/meta/dashboard.md` 内的自引用同步更新;
- Obsidian 用户需在 `wiki/` 下重新打开 vault(原 `docs/wiki/.obsidian/` 内的 `workspace.json` 仍记录旧路径,可在 Obsidian 中接受迁移提示或在 vault 根重新配置)。

无内容变更,纯路径重构。
