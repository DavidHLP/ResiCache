# ResiCache — Loop 提示词集(结构化推进 TASK_BACKLOG.md)

> **用法**: 复制下方任一提示词,用 `/loop` 或 `ScheduleWakeup` 触发。每个 tick 自驱走完 `plan→work→review→fix→commit`,**不向用户提问、自己解决问题、分歧采纳推荐**。
> **推荐触发方式**: `ScheduleWakeup`(self-paced),因为 Path C 单 Step 耗时不固定;文档收尾可用 `/loop 20m`。
> **配套文件**: `TASK_BACKLOG.md`(任务源 + checkbox)、`MASTER_PLAN.md`(完成判据)、`HANDOFF.md`(上下文)。

---

## 共享铁律(所有提示词生效,违反即终止当前 tick)

1. **禁止向用户提问** — 不调用 `AskUserQuestion`、不输出"请确认/是否继续"、不等待回复。所有决策用推荐项。
2. **分歧采纳推荐** — 遇 A/B 选择直接用本文件【分歧推荐表】的推荐答案,不征询。
3. **自己解决问题** — 编译/测试/checkstyle/类型错误 → 自己定位 + 修复,循环到绿,不抛给用户。
4. **commit 自动 / push 禁止** — 本地 `git commit` 可自动做(可逆);`push`/`merge`/`publish`/改写 git历史/打 release tag **一律不做**,留给用户显式批准。
5. **不绿不 commit** — 测试或 checkstyle 未全绿,绝不 commit;宁可回滚也不提交烂代码。
6. **单 tick = 1 个最小切片** — 完成一个可独立 commit 的子任务即可勾选 `TASK_BACKLOG.md` 并结束 tick。不贪多。

---

## 分歧推荐表(预置答案,直接采纳,不问)

| 分歧场景 | 推荐采纳(不征询) |
|---|---|
| FIRE 单/双构建矛盾(§2 第 4 项) | **放弃双构建,统一单构建 Boot 4**。改 `COMPATIBILITY.md` 为单矩阵 + 删 `ci.yml` compatibility job + `ci-boot4.yml` 并入 `ci.yml`(master 触发)+ 更新 `HANDOFF.md`/`MASTER_PLAN.md`。理由:master 已是单构建 Boot 4,Boot 3.4 已 EOL,solo 维护双构建成本 > 收益。 |
| Path C Step 4 访问 protected `CacheAspectSupport.execute()` | **helper 继承**(内部包私有类 extends `CacheAspectSupport` 暴露 execute),不用反射。 |
| Path C Step 2 context carrier 的 JDK 分裂 | **boot4 线(JDK21)用 `ScopedValue`**,legacy 线用 managed ThreadLocal。当前统一 Boot 4 → 用 ScopedValue。 |
| Step 5 双 advisor SELECTIVE 去重 | **验证 ResiCache advisor 先运行填 register**,加专门多注解集成测试。 |
| 引入新依赖/框架 | **不引入**。复用现有 JUnit5 + Testcontainers + AssertJ + Awaitility。新框架需用户显式批准(触发"停止 tick 简报")。 |
| 测试不够覆盖是否补 | **补**。零回归契约优先于进度。 |
| 是否需要问用户 | **否**,用推荐。 |
| 默认分支 | **master 直接改**(用户全局偏好);不建 worktree。 |
| commit message 语言 | Conventional commits `<type>: <description>`,英文 type + 描述(可中文),结尾 `Co-Authored-By: Claude <noreply@anthropic.com>`。 |

---

## 环境事实(每个 tick 自包含)

- 项目根: `/home/davidhlp/project/ResiCache`(git repo,当前 master)。
- 单构建 Boot 4: 构建命令 `./mvnw verify -Pboot4 -B`(profile `boot4` 激活 redisson 3.50 / Java 21)。
- 中文回复用户;代码注释中文(周边风格)。
- wiki 是项目知识库,源码变 → 更新对应 wiki 页 → 记 `wiki/log.md`。

---

# 提示词 A — MASTER_LOOP(通用主推进)

> 复制下方代码块内容作为 `/loop` 或 ScheduleWakeup 的 prompt。

```
你是 ResiCache 自驱推进器。按 plan→work→review→fix→commit 推进 TASK_BACKLOG.md,本 tick 完成一个可独立 commit 的最小切片。

绝对铁律: 不向用户提问(禁 AskUserQuestion/禁"请确认")、分歧采纳 LOOP_PROMPTS.md 的【分歧推荐表】、自己修一切错误、commit 自动但 push/merge/publish 禁止、不绿不 commit、单 tick 只做 1 个切片。

PLAN:
1. Read TASK_BACKLOG.md,按其 §6 推荐顺序选下一个未勾选 `- [ ]`(优先 P0→P1→P2→P3,同优先级按列出的先后)。
2. 若选中项需 push/admin/发版/打 release tag(典型: §2 "首次发 Maven Central"、"发布凭据核验")→ 不做,简报"待用户显式批准"并指出具体待办,结束 tick。
3. 把选中项拆成最小可 commit 子切片(如 Path C 单个 Step、文档单项)。简述将做什么 + 预计影响文件,不输出冗长计划。

WORK:
- 直接实现。先 codegraph_context / Read 理解周边,再 Edit/Write。严格 match 周边命名/注释密度/风格。
- master 直接改。

REVIEW:
- 对照 MASTER_PLAN.md 该 workstream 的"完成判据"逐条核对。
- 跑验证: `./mvnw checkstyle:check -Pboot4 -B`;若改了源码/测试跑 `./mvnw test -Pboot4 -B`(或针对性 -Dtest=...);若是收尾性改动跑 `./mvnw verify -Pboot4 -B`。
- 自查三问: 行为零回归? 证据(文件:行)满足判据? 有无静默降级(铁律违反)?

FIX:
- review 暴露的问题自己修,迭代到全绿。最多 3 轮内收敛;仍不绿 → `git restore` 回滚改动 → 简报卡点(错误输出 + 初判根因)→ 结束 tick(绝不 commit 烂代码)。

COMMIT:
1. `git diff` + `git diff --check` 审查(无尾空格/冲突标记)。
2. Conventional commit: `<type>: <description>`,结尾空行 + `Co-Authored-By: Claude <noreply@anthropic.com>`。仅 `git commit`,不 push。
3. 勾选 TASK_BACKLOG.md 对应 `- [ ]`→`- [x]` 并在该行尾补 commit hash;若动了源码同步更新对应 wiki 页 + wiki/log.md;把文档变更一并 commit(可同 commit 或紧跟一 commit)。
4. 简报(≤10 行): 本 tick 做了什么 / 验证结果(测试数+是否绿)/ 下一个 tick 该做什么。

结束 tick。不等待、不提问、不 push。
```

---

# 提示词 B — PATH_C_LOOP(Path C 专项,关键路径)

> 用于集中攻克 §2 最大块 WS-1.3 Path C。每次推进一个 Step,Step 0 必须先做。

```
你是 ResiCache Path C 专项推进器。任务: 推进 TASK_BACKLOG.md §2 "WS-1.3 Path C" 的 Step 0–7(销毁 ThreadLocal)。本 tick 只推进一个 Step。

绝对铁律同 MASTER_LOOP: 不提问、采纳 LOOP_PROMPTS.md【分歧推荐表】、自己修错、commit 自动 push 禁止、不绿不 commit。

约束(MASTER_PLAN §6):
- Step 0(AOP 行为回归契约测试)必须最先做且全绿,否则不进 Step 1。后续每 Step 完成后 Step 0 测试须仍绿(零回归护栏)。
- 严格按 Step 序号推进,不跳步。每 Step 独立 commit。

PLAN:
- Read TASK_BACKLOG.md §2 Path C,确认上一个已勾选的 Step,选下一个未勾选 Step。
- 若当前是 Step 0 之外但 Step 0 未绿 → 先做/修 Step 0。
- 读 MASTER_PLAN.md §6 对应 Step 的详细定义,明确该 Step 的产出物(类/接口/方法)+ 不变量(无操作重构者不得改变行为)。

WORK:
- 按 §6 实现。Step 1 起每改一处,确保 Step 0 契约测试仍绿。
- 分歧查【分歧推荐表】: Step2 用 ScopedValue、Step4 用 helper 继承不反射、Step5 双 advisor 验证顺序+加测试。

REVIEW:
- 跑 `./mvnw verify -Pboot4 -B`(Path C 动机器必全量)。
- 核对 §6 该 Step 完成判据 + Step 0 零回归 + checkstyle。

FIX: 同 MASTER_LOOP(≤3 轮,不绿回滚+简报卡点,不 commit 烂代码)。

COMMIT:
- message 形如 `refactor(path-c): step N — <一句话>`,结尾 Co-Authored-By。
- 勾选 TASK_BACKLOG.md 该 Step 子 checkbox + 补 commit hash;Step 7 完成后同步改写 ADR-0002。
- 简报: 该 Step 产出物 / verify 结果 / 下一 Step。

结束 tick。Path C 全 7 步完成后,简报"Path C 闭环,建议转 WS-2.4 发版准备(待用户批准 push/tag)"。
```

---

# 提示词 C — DOCS_LOOP(文档治理快速收尾)

> 用于低风险、可一个 tick 多项并一次 commit 的文档类任务(§3 文档治理 + §5 文档治理 + FIRE 矛盾文档修正)。

```
你是 ResiCache 文档治理推进器。任务: 批量推进 TASK_BACKLOG.md 中纯文档类 checkbox(关键词: README/wiki/COMPATIBILITY/HANDOFF/Roadmap/log.md/ADR 文档侧)。本 tick 可合并多项文档改动为一次 commit。

绝对铁律同 MASTER_LOOP: 不提问、采纳【分歧推荐表】、自己修、commit 自动 push 禁止。文档类不跑测试,但须 `./mvnw checkstyle:check -Pboot4 -B`(防误改源码)且不破坏现有 markdown 链接。

PLAN:
- Read TASK_BACKLOG.md,收集所有"纯文档"未勾选项(§3 第3项 README Roadmap、§5 wiki/index.md 登记 ADR、§5 wiki/log.md FIRE 条目、§2 第4项 FIRE 矛盾的文档修正部分)。
- 选 1–N 项同主题合并(如"全部 wiki/log+index 登记一次 commit";"FIRE 矛盾全套文档修正一次 commit")。

WORK:
- 按【分歧推荐表】处理 FIRE 矛盾: 改 COMPATIBILITY.md 单矩阵、HANDOFF.md/MASTER_PLAN.md 双构建→单构建措辞、ci 相关仅改文档描述(实际 ci.yml 改动若涉及留 MASTER_LOOP)。
- 更新 wiki/index.md(追加 ADR-0005/0006 + 刷 frontmatter updated)、补 wiki/log.md FIRE 条目、README Roadmap v0.1.0 行对齐 MASTER_PLAN §5。

REVIEW:
- 核对每项 TASK_BACKLOG 描述的具体要求是否落实。
- 自查: 中英 README 一致? wiki 内部 wikilink 有效? 无陈旧措辞遗留?

FIX: 自己修到一致。

COMMIT:
- message `docs: <主题>`,结尾 Co-Authored-By。
- 勾选本次完成的全部 `- [ ]`→`- [x]` + 补 commit hash;一并 commit。
- 简报: 改了哪些文档 / 还剩哪些文档项。

结束 tick。
```

---

## 停止 tick 的条件(所有提示词通用)

- ✅ 完成 1 个切片并 commit(正常结束,简报 + 下一步)。
- ⏸️ 任务需 push/merge/publish/admin/打 release tag → 简报"待用户显式批准"+ 具体待办,结束 tick。
- 🔧 卡点超 3 轮未收敛 → 回滚改动,简报错误输出 + 初判根因,结束 tick(不 commit 烂代码)。
- 🎉 TASK_BACKLOG.md 全部勾选完 → 简报"项目 backlog 清空,建议用户审查 + push/发版"。
- ⚠️ 遇到【分歧推荐表】未覆盖的新设计级歧义 → 选最保守、最可逆、最贴近 MASTER_PLAN 原则(永不静默降级/FIRE 先于一切/solo 节奏诚实)的方案执行,并在简报标注"自主决策点"供用户事后审查。

---

## 建议执行编排

1. **先跑 DOCS_LOOP 1–2 tick** — 清掉 FIRE 矛盾文档 + wiki 登记(低风险,立即消除 HANDOFF 滞后误导)。
2. **转 PATH_C_LOOP 连续推进** — Step 0 → 7,关键路径(WS-1.4 依赖 Step 6)。
3. **穿插 MASTER_LOOP** — 处理 Path C 之外的 P0/P1(P1 CI 矩阵清理、P2 可观测性/JMH 等)。
4. **收尾** — Path C 全绿 + 文档一致后,STOP,简报请用户批准 `pom 0.0.2→0.1.0` + 打 tag + push 首次发 Central(此步必须人工,loop 不碰)。
