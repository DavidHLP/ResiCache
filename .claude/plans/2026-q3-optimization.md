# 2026 Q3 项目优化 Runbook

> **Status**: ACTIVE(2026-06-30 启动)
> **接管**: `archive/autonomous-loop-v2-CLOSED.md`(v2 已 CLOSED)
> **目标**: 项目优化里程碑 — 详见 `wiki/meta/milestone-2026-q3.md`
> **模式**: 不绑定 sequential loop;按需触发,gate-gated

---

## 1. Repository State Snapshot(启动前必确认)

| 项 | 状态 | 备注 |
|---|---|---|
| Branch | `master` | 用户偏好:直接在 main 上做,无 worktree |
| HEAD | `8adc9cb`(用户最近 commit) | `docs(wiki): log round 42 — Round 4 = wait-state (ADR-0008 pending user review)` |
| Working tree | `D AUTONOMOUS_ITERATION_LOOP.md` + `D COMPETITIVENESS_GUIDE.md`(前一会话遗留) | 不在此 runbook scope,需用户决定是否 commit `git rm` |
| Q3 已新增(本会话) | `wiki/meta/milestone-2026-q3.md` + `wiki/log/archive-2026-q2.md` + `.claude/plans/archive/autonomous-loop-v2-CLOSED.md`(从 `autonomous-loop-v2.md` rename) | 本 docs commit 内一次提交 |
| Verify baseline | N/A(纯 docs) | 后续 code 项走 `./mvnw clean verify -B`(test + JaCoCo 70%/40% + checkstyle 0) |
| Local tags | `v0.0.3` / `v1.0` 残留 | 不动 — 属 G1-G7 发布命令链(归用户 gate) |
| ECC_HOOK_PROFILE | unset(默认 = 全开) | GateGuard 已启用 |

## 2. Q3 三大轴 + 候选 backlog

### 轴 A — 可观测性深化(续 WS-1.4)

**Gated by ADR-0008 Accepted**(当前 Status: Proposed,等用户 review)。

| 候选 | 描述 | 优先级 | 状态 |
|---|---|---|---|
| A1 | chain-level Observation 接入 `AbstractCacheHandler.handle()` | P0 | gated ADR-0008 |
| A2 | per-handler Micrometer tags(handler / decision,bounded) | P1 | gated ADR-0008 |
| A3 | MeterFilter 白名单护栏(防未知 tag) | P1 | gated ADR-0008 |

### 轴 B — 测试质量与覆盖

**独立启动**,不依赖任何 ADR。

| 候选 | 描述 | 优先级 | 状态 |
|---|---|---|---|
| B1 | 并发安全测试矩阵(`AbstractCacheHandler.execute()` 多线程) | P0 | 可启动 |
| B2 | Cluster 模式 Testcontainers IT(补 single-only 缺口) | P1 | 可启动 |
| B3 | Property contract tests(`resi-cache.*` keys 行为契约) | P2 | 可启动 |

### 轴 C — 发布工程与项目运营

部分独立,部分需用户协调(samples / labels)。

| 候选 | 描述 | 优先级 | 状态 |
|---|---|---|---|
| C1 | v0.0.6 发布命令链 RUNBOOK(release.yml 经验沉淀) | P0 | 用户 gate 后 |
| C2 | ADOPTERS.md 模板(STABILITY §4.5) | P1 | 独立 |
| C3 | BUS-FACTOR.md 雏形(STABILITY §4.6) | P1 | 独立 |
| C4 | sample module 骨架(README 提到未交付) | P2 | 独立 |
| C5 | benchmark module 骨架(JMH,roadmap v0.3.0) | P2 | 独立 |

## 3. 当前 Q3 排程(头 4 周)

```
W1(2026-06-30 ~ 2026-07-06)
  ├─ 用户 gate: ADR-0008 review + v0.0.6 发布命令
  └─ [可独立] B1: 并发安全测试矩阵 启动

W2(2026-07-07 ~ 2026-07-13)
  ├─ [续 B1] 3+ 个并发场景
  └─ [gated A1] ADR-0008 Accepted → chain Observation 接入

W3(2026-07-14 ~ 2026-07-20)
  ├─ [独立 C2 + C3] ADOPTERS.md + BUS-FACTOR.md
  └─ [独立 C4] sample module 骨架

W4(2026-07-21 ~ 2026-07-27)
  └─ Q3 评审 + Q4 候选决定
```

## 4. Hard Constraints(继承自 v2 runbook + Q3 微调)

| ID | 约束 | 来源 |
|---|---|---|
| §1 | 不 push / 不 amend / 不打 v* tag / `git add` 显式文件列表禁 `-A`/`.` | v2 §1 |
| §2 | 不触 GPG / release.yml deploy / secret env | v2 §2 |
| §3 | 工作目录 `.claude/plans/` 内不入仓(本文件除外) | v2 §5 step 6 |
| §4 | armed-state:本地残留 `v0.0.3` / `v1.0` tag **不可删不可建**,等用户执行 release | v2 §4 |
| §5 | verify 必跑(纯 docs 例外走 §5b 跳过) | v2 §5 |
| §6 | wiki append-only;commit SHA 级细节走归档 | Q3 沉淀 |

**Q3 独有约束**:

- §q3-1:不动 `wiki/adr/0001-0008` 既有 ADR(沿用,新建 ADR 自 0009 起)
- §q3-2:不动 CHANGELOG.md `[Unreleased] — planned for v0.0.6` 段(用户已锁版本)
- §q3-3:不动 `wiki/meta/milestone-2026-q3.md` 已写排程(变更排程需更新里程碑文档)
- §q3-4:不动 `wiki/log/archive-2026-q2.md` 内容(归档已 CLOSED)
- §q3-5:不动 `wiki/adr/0008-observation-spans-attribution.md`(等用户 review,不被外部动作驱动)

## 5. Stop Conditions(逐 commit 而非逐 round)

满足任一即收尾本 commit:

| 触发 | 行动 |
|---|---|
| commit 完成 + verify 绿(若适用)+ log 同步 | 下一 commit |
| 任何"重新评估定位"信号(见 milestone-2026-q3.md §5 范围外) | 暂停,写 ADR 草案 |
| token 余量 < 30% | 提前收尾,未完成项写进 log |
| verify 连续 2 次失败 | 暂停,回滚最近 commit |
| 用户中断 | 立刻收尾 |

## 6. Hook Profile & GateGuard

| 项 | 状态 | 备注 |
|---|---|---|
| `ECC_HOOK_PROFILE` | unset(默认 = 全开) | 安全 |
| `ECC_GATEGUARD` | enabled | 每次 Write / Bash 前 fact-forcing;Q3 工作已通过规划 |

## 7. 启动命令(给用户复制)

### 7.1 一次性:确认 Q3 起点

```bash
cd /home/davidhlp/project/ResiCache
git status --short
# 期望:看到 AUTONOMOUS_ITERATION_LOOP.md / COMPETITIVENESS_GUIDE.md 仍 deleted(用户决定 commit 或留)

ls wiki/meta/milestone-2026-q3.md
ls wiki/log/archive-2026-q2.md
ls .claude/plans/archive/autonomous-loop-v2-CLOSED.md
# 三文件应都在(本会话已创建/归档)
```

### 7.2 启动 Q3 工作(选 1 个候选)

```
在 Claude Code 输入:
> 按 /home/davidhlp/project/ResiCache/.claude/plans/2026-q3-optimization.md §2 选 1 个可独立候选(B/C 轴)执行 1 commit
```

### 7.3 后续 commit 入口

```
在 Claude Code 输入:
> 按 /home/davidhlp/project/ResiCache/.claude/plans/2026-q3-optimization.md §2 选下 1 候选执行
```

## 8. 监控命令(每 commit 收尾后)

### 8.1 本 commit 是否合规

```bash
cd /home/davidhlp/project/ResiCache
git log -1 --format="%H%n%s%n%b" | head -20
git show --stat HEAD
git status --short
# 期望:working tree clean,本地 tag 不变
```

### 8.2 verify 状态(若本 commit 有代码改动)

```bash
./mvnw clean verify -B
# 期望:exit 0,JaCoCo 70%/40%,checkstyle 0
```

### 8.3 wiki/log 是否同步

```bash
grep -c "^## \[" /home/davidhlp/project/ResiCache/wiki/log.md
# 期望:每 commit 在主 log.md 留 1 条「日期 + 主题」摘要(细节归档)
```

## 9. 已知 Gates(等用户决策,本 runbook 不触碰)

来源:`archive/autonomous-loop-v2-CLOSED.md` §10 继承(Q3 沿用,不动)。

| # | Gate 项 | 用户需执行 |
|---|---|---|
| G1 | pom `maven-gpg-plugin` sign-artifacts `<phase>deploy</phase>` → `<phase>verify</phase>` | 改 pom + commit |
| G2-G7 | tag 删建 + push + GitHub Release | `git tag` + `git push` 命令链 |

## 10. 与 v2 runbook 的差异

| 维度 | v2(已 CLOSED) | Q3 |
|---|---|---|
| Loop 模式 | sequential(plan → working → CR → docs) | 触发式(无固定循环) |
| Model tier | opus + sonnet | 继承默认 |
| N commits/round | 3(可调 [1,5]) | 不绑定(逐 commit 评审) |
| Pattern source | `AUTONOMOUS_ITERATION_LOOP.md`(已删) | `wiki/meta/milestone-2026-q3.md`(active) |
| Round 编号 | 1 ~ 42(归档) | N/A(无 round 概念) |
| Log 风格 | 70 行 round-by-round(已归档) | 「日期 + 主题」摘要 |

## 11. Runbook 维护

- 本文件由 Q3 启动会话创建
- 每次 Q3 评审后更新(排程 / 候选优先级 / 状态)
- 不改 `wiki/meta/milestone-2026-q3.md` 除非里程碑文档本身需要更新
- 决策变更走 ADR(0009+),不走本 runbook

---

**Runbook 创建**:2026-06-30
**Runbook 版本**:Q3 v1
**下次维护触发**:Q3 W4 评审 / 候选优先级变更 / 用户 gate 落地