# Security Policy

## Supported versions

ResiCache is **pre-1.0 (0.0.x)**. Security fixes target the maintained
Boot 4 / Java 21 source line on `main`; published historical Boot 3 artifacts
are not a maintained compatibility line. There are no backports. The build
version alone does not identify the artifact lineage; see
[`COMPATIBILITY.md`](COMPATIBILITY.md).

| Version | Supported |
|---------|-----------|
| Current Boot 4 / Java 21 source line | ✅ Best-effort security fixes |
| Historical Boot 3 artifacts / older source revisions | ❌ No maintained backport line |

## Reporting a vulnerability

**Please do NOT open a public GitHub issue for security vulnerabilities.**

Report privately via one of:

1. **GitHub Security Advisories** (preferred):
   [Report a vulnerability](https://github.com/davidhlp/ResiCache/security/advisories/new)
2. Email: see the maintainer's GitHub profile
   ([DavidHLP](https://github.com/davidhlp)) for contact.

Please include:

- A description of the issue and its impact.
- Steps to reproduce, or a proof-of-concept.
- Affected version(s).

## Response

This is a **Non-SLA, best-effort** project maintained by one person. There is no
guaranteed response time, but security reports are prioritized over feature
work. Expect an acknowledgment within a reasonable window; a fix and a public
advisory (with credit, if desired) will follow once the report is confirmed.

## Known security-relevant design choices

- **Deserialization is whitelisted.** `SecureJackson` restricts polymorphic
  deserialization to `resi-cache.serializer.allowed-package-prefixes`
  (default: `io.github.davidhlp`). You **must** add your own package prefixes
  for custom cached types; they are not derived automatically. Use dot-boundary
  entries such as `com.example.dto.*` and `io.github.davidhlp.*` when
  replacing the list. Whitelist violations remain fail-fast even with
  `fail-on-unknown-type=false`. See
  [configuration and serialization reference](docs/REFERENCE.md#serialization-and-compatibility).
- **Redisson config file path**
  (`resi-cache.redis.redisson-config-path`) is read via `Config.fromYAML` and
  **must only come from trusted ops/deploy sources** (application.yml,
  environment variables, config center) — never from end-user input, since it
  triggers arbitrary local-file reads.
- **Polymorphic Jackson typing is off by default**
  (`resi-cache.serializer.polymorphic-typing-enabled=false`). Enable it only if
  you understand the Jackson polymorphic-deserialization attack surface.
- **Legacy JDK migration is resource-bounded.** The decoder preserves the host
  input filter and class allowlist, with additional byte/depth/reference/array
  limits described in [`docs/REFERENCE.md`](docs/REFERENCE.md#serialization-and-compatibility).
- **Failure diagnostics redact raw keys at WARN/ERROR.** Full throwable stacks
  remain at DEBUG and may contain application data; control access to those logs.
