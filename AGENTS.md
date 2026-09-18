# Agent Project Instructions

Project conventions live in [CLAUDE.md](./CLAUDE.md), including the
project-scoped workflow and verification commands. Read the relevant sections;
linked documents are pointers, not a requirement to load the whole repository.

For documentation or architecture work, start with
[`docs/README.md`](docs/README.md), then read only the task-relevant core page.
Keep one canonical owner per topic. Verify current behavior against source,
configuration, tests, and scripts before changing prose.

Keep shared agent instructions in `CLAUDE.md` and this file as the entry point.
Do not turn task logs, dated summaries, generated reports, or ignored local
status files into permanent current-state documentation.

For documentation maintenance, verify commands and versions against `pom.xml`
and the relevant CI scripts. Preserve existing local edits and update the
canonical document instead of duplicating its rules here.
