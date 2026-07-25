# ResiCache Open Issue Ledger

Last synchronized: 2026-07-26
Repository baseline: `main` @ `75ed279a71b17f227c3170d738eb93e50d876c8a` (`origin/main`, ahead 0 / behind 0)
GitHub repository: `DavidHLP/ResiCache`

## Execution order

| Order | Issue | Priority | Status | Dependency / triage |
|---:|---|---|---|---|
| 1 | [#4](issue-4.md) Per-handler Micrometer tags | P0 observability | INVESTIGATING | Independent and explicitly highest priority |
| 2 | [#2](issue-2.md) Redis Cluster slot IT | P1 correctness | DISCOVERED | Validates existing production key construction; must use real Cluster |
| 3 | [#5](issue-5.md) Serialization migration CLI | P1 compatibility | DISCOVERED | Large migration capability; pre-flight probe already exists |
| 4 | [#3](issue-3.md) JMH module | P1 performance | DISCOVERED | External contributor declared intent on 2026-07-21 but no PR/branch is linked; review after higher-priority work and re-sync before takeover |

## Live GitHub facts

- Open Issues: `#2`, `#3`, `#4`, `#5`.
- Open PRs: none.
- Linked closing PRs: none for all four issues.
- Assignees: none for all four issues.
- Only active external signal: `Shubh2-0` volunteered for #3 on 2026-07-21; no implementation is currently reviewable.

## Validation gates

- Preserve `testcontainers-bom:1.20.4`; Spring Boot 4.0.0's transitive 1.20.6/docker-java path is incompatible with the target older Docker daemon.
- Real-Redis acceptance requires all three signals: container startup evidence, target test count > 0, and skipped count = 0.
- Every implementation receives Standards + Spec independent review before its focused commit.
- GitHub remote writes remain subject to the session GitHub Write Gate and require an explicit review package and approval.
