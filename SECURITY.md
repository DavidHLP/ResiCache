# Security Policy

## Supported versions

ResiCache is **pre-1.0 (0.0.x)**. Only the latest release line receives
security fixes; there are no backports for older `0.0.x` versions. Once `1.0`
ships, a supported-versions table will appear here.

| Version | Supported |
|---------|-----------|
| `0.0.x` (latest) | ✅ Security fixes |
| `< latest 0.0.x` | ❌ Upgrade required |

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
  for custom cached types, otherwise deserialization throws. See
  [README → Serialization](README.md#serialization-safety).
- **Redisson config file path**
  (`resi-cache.redis.redisson-config-path`) is read via `Config.fromYAML` and
  **must only come from trusted ops/deploy sources** (application.yml,
  environment variables, config center) — never from end-user input, since it
  triggers arbitrary local-file reads.
- **Polymorphic Jackson typing is off by default**
  (`resi-cache.serializer.polymorphic-typing-enabled=false`). Enable it only if
  you understand the Jackson polymorphic-deserialization attack surface.
