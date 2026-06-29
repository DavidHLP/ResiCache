# Agent Project Instructions

**This file is reconciled with [CLAUDE.md](./CLAUDE.md) (canonical).**

As of 2026-06-29 the canonical project instructions live in `CLAUDE.md`.
`AGENTS.md` is kept here for tool compatibility — its full content previously
duplicated `CLAUDE.md` and drifted (stale Java 17+, Spring Boot 3.4.13,
Redisson 3.27.0, plus a Project Structure tree that still listed the removed
`wrapper/`, `spi/`, `event/`, `evaluator/`, and `CacheMetricsRecorder` from
before the `a5ab55b` cleanup). To avoid future drift, this file is a thin
pointer instead of a duplicate.

See [CLAUDE.md](./CLAUDE.md) for:

- Tech Stack (current versions)
- Project Structure (current directory tree + 已移除 callout)
- Key Architecture: Chain of Responsibility
- Conventions (handler ordering, properties, context, strategy replacement)
- Where to Look table

If you find yourself wanting to edit project-instructions content, edit
`CLAUDE.md` — do **not** re-expand this file into a duplicate.