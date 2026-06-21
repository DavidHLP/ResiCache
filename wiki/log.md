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

wiki 演化的时间线,append-only。条目格式 `## [YYYY-MM-DD] <op> | <subject>`(op ∈ init / ingest / improve / colorize / query / lint)。

> 解析最近条目:`grep "^## \[" log.md | tail -5`

---

## [2026-06-21] colorize | graph.json 按目录着色

之前 `improve` 项里 `colorGroups` 用了未加引号的 `path:architecture` + 字符串 palette,被还原。此次按官方规范重写并写入。

**Schema 调研**

- 权威来源:Obsidian 论坛 + 社区 colorize skill
- `colorGroups` 数组,`{query, color}` 对,first-match wins
- `query` 用 Obsidian 搜索语法,`path:"folder"` 必须双引号包(无空格也强烈推荐)
- `color` 两种合法形式:`"1"` 字符串(引用当前 palette)或 `{"a":1,"rgb":<int>}` 对象(rgb = (R<<16)|(G<<8)|B)
- 写盘时机:Obsidian 启动读、关闭重写,要么先关 Obsidian,要么改完立即 Cmd/Ctrl+R

**写入**

- 备份:`.obsidian/graph.json.backup-20260621-1729`(改前)
- 改动:仅 `colorGroups` 由 `[]` 改为 6 项,其它 19 字段全部原样保留
- 配色:用显式 RGB 对象(不依赖 palette 主题),与画布文件 `color` 编号保持同一色系
  - architecture `#E15759` 红
  - mechanisms `#76B7B2` 青
  - modules `#F28E2B` 橙
  - concepts `#B07AA1` 紫
  - how-to `#EDC948` 黄
  - meta `#59A14F` 绿

**未匹配节点走默认色**(4 个根文件 `README` / `index` / `log` / `overview`)— 它们是入口 + 维护层,作为图谱外围。

**效果预期**

- 6 色立即可视区分;`mechanisms/` 5 页青、`architecture/` 5 页红、`modules/` 8 页橙
- 画布节点与 graph view 节点颜色一致
- 第一次 reload 即可看到;若当前 vault 开着,Cmd/Ctrl+R

---

## [2026-06-21] improve | 完善 obsidian 设计

为 wiki 加固结构与视觉一致性,补回 6 块短板。

**Frontmatter 修复**(lint MEDIUM 落地)

- `modules/eviction.md`、`modules/observability.md` 此前缺 `status` / `created` 字段,已补齐 `status: stable` + `created: 2026-06-21`
- 现 32/32 页 `type` / `status` / `created` / `updated` 全部对齐,Dataview dashboard 的「按状态统计」「待完善」查询可正常返回

**启用 Dataview**

- `.obsidian/community-plugins.json` 新增 `dataview` 条目
- 首次启动 Obsidian 时,`Settings → Community Plugins → Browse → Dataview → Install` 即可启用
- `meta/dashboard.md` 重写为分组化结构(全局统计 / 近期更新 / 核心骨架 / 模块速查 / 概念与指南 / 待完善),每段用 callout 框定;纯 markdown 浏览不受影响

**画布整合**

- `index.md` 顶部新增「🗺️ 视觉地图」三栏表,用 `![[meta/overview.canvas]]` / `![[meta/mechanisms-canvas.canvas]]` / `![[meta/modules-canvas.canvas]]` 嵌入三张画布
- `overview.md` 「核心架构」前嵌入 `meta/overview.canvas`,文字流与画布并排
- 新建两张画布:
  - `meta/mechanisms-canvas.canvas` —— 5 机制 + 4 概念,左机制右概念,边色与 `overview.canvas` 一致
  - `meta/modules-canvas.canvas` —— 8 模块按数据流分层(入口 / 核心 / 支撑)+ 架构层 `auto-configuration` 与 `chain-of-responsibility`

**MOC 导航页**

- `meta/mechanisms-moc.md` —— 机制拓扑 MOC:责任链档位表 + 4 问题 ↔ 防御组合 callout + Dataview 动态视角
- `meta/modules-moc.md` —— 模块依赖 MOC:三层模型 + 8 模块分组 + 关键调用链

**图谱视觉与 CSS**

- `.obsidian/graph.json`:
  - 新增 6 个 `colorGroups`(按 `path:` 着 6 色,与画布色彩一致)
  - 调强 `centerStrength` (0.52 → 0.7) + 增大 `nodeSizeMultiplier` (1 → 1.15) + `lineSizeMultiplier` (1 → 1.2)
  - 关闭 `showOrphans`(lint 已确认 0 孤儿,关闭以减少视觉噪声)
- 新建 `.obsidian/snippets/wiki.css`:链接 hover 下划线、callout 主题色、代码块圆角、表格斑马线、Frontmatter 淡化、Dataview 表格继承,共 9 节
- 启用方式:`Settings → Appearance → CSS snippets → 启用 "wiki"`

**Workspace 默认布局**

- 主区从「仅一个 graph tab」扩展为「概览 / Dashboard / 索引 / 关系图谱」四个 tab,首次打开 vault 直接看到导航
- 保留原 Claudian tab 与右栏(反向链接 / 出链 / 标签 / 属性 / 大纲),Claude 工作流不受影响

**未触碰的内容**

- 28 份已有 md / 1 份 canvas 的正文无变更,本次为纯结构与配置层加固
- 全部改动在 `wiki/` 范围内,未触及源码

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
