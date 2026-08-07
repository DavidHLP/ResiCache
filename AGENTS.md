# Agent Project Instructions

**This file is reconciled with [CLAUDE.md](./CLAUDE.md) (canonical).**

As of 2026-06-29 the canonical project instructions live in `CLAUDE.md`.
`AGENTS.md` is kept here for tool compatibility — its full content previously
duplicated `CLAUDE.md` and drifted (stale Java 17+, Spring Boot 3.4.13,
Redisson 3.27.0, plus a Project Structure tree that still listed the removed
`wrapper/`, `spi/`, `event/`, `evaluator/`, and `CacheMetricsRecorder`).
To avoid future drift, this file is a thin
pointer instead of a duplicate.

See [CLAUDE.md](./CLAUDE.md) for:

- Tech Stack (current versions)
- Project Structure (current directory tree + 已移除 callout)
- Key Architecture: Chain of Responsibility
- Conventions (handler ordering, properties, context, strategy replacement)
- Where to Look table

If you find yourself wanting to edit project-instructions content, edit
`CLAUDE.md` — do **not** re-expand this file into a duplicate.

<!-- headroom:rtk-instructions -->
# RTK (Rust Token Killer) - Token-Optimized Commands

When running shell commands, **always prefix with `rtk`**. This reduces context
usage by 60-90% with zero behavior change. If rtk has no filter for a command,
it passes through unchanged — so it is always safe to use.

## Key Commands
```bash
# Git (59-80% savings)
rtk git status          rtk git diff            rtk git log

# Files & Search (60-75% savings)
rtk ls <path>           rtk read <file>         rtk grep <pattern>
rtk find <pattern>      rtk diff <file>

# Test (90-99% savings) — shows failures only
rtk pytest tests/       rtk cargo test          rtk test <cmd>

# Build & Lint (80-90% savings) — shows errors only
rtk tsc                 rtk lint                rtk cargo build
rtk prettier --check    rtk mypy                rtk ruff check

# Analysis (70-90% savings)
rtk err <cmd>           rtk log <file>          rtk json <file>
rtk summary <cmd>       rtk deps                rtk env

# GitHub (26-87% savings)
rtk gh pr view <n>      rtk gh run list         rtk gh issue list

# Infrastructure (85% savings)
rtk docker ps           rtk kubectl get         rtk docker logs <c>

# Package managers (70-90% savings)
rtk pip list            rtk pnpm install        rtk npm run <script>
```

## Rules
- In command chains, prefix each segment: `rtk git add . && rtk git commit -m "msg"`
- For debugging, use raw command without rtk prefix
- `rtk proxy <cmd>` runs command without filtering but tracks usage
<!-- /headroom:rtk-instructions -->
