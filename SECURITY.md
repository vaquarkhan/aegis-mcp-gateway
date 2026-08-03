# Security Policy

Author: Viquar Khan.

Aegis sits between a language model and production data infrastructure, so a defect here is a
defect in someone's blast radius. Please treat security reports accordingly.

## Supported versions

| Version | Supported |
| --- | --- |
| 0.1.x | Yes |
| older pre-releases | No |

## Reporting a vulnerability

Report privately. Do not open a public issue for a suspected vulnerability.

- Preferred: open a private security advisory at
  <https://github.com/vaquarkhan/aegis-mcp-gateway/security/advisories/new>.
- Include the affected version, configuration (with secrets removed), reproduction steps, and the
  impact you believe it has.

You can expect an acknowledgement within five business days and a status update at least every ten
business days until the report is resolved. Please give us 90 days before public disclosure, or
less if we agree a shorter timeline together.

## What counts as a vulnerability

The gateway makes a small number of promises. A report is in scope when it breaks one of them.

- A caller reaches a tool the policy chain should have denied.
- A `MUTATE` or `DESTRUCTIVE` tool runs without an unexpired, unused, scope-bound approval token.
- An unauthenticated request is admitted on the HTTP transport.
- A token, keystore password, approval secret or outbound credential appears in a log line, an
  error message, a metric label or a tool result.
- Tool output escapes the size bound or the redaction pass.
- An outbound call reaches a host the egress allow list should have refused, in particular a cloud
  instance metadata endpoint.
- A swapped or tampered tool manifest changes the registered tool surface without the catalog
  integrity check failing.

Out of scope: findings that depend on a configuration the gateway already refuses to start with,
denial of service through resource exhaustion on a deliberately unlimited setting, and reports
against refuse-start auth modes (CIMD / SPIFFE) documented on the [ROADMAP](ROADMAP.md).

## Known limitations in 0.1.0

These are documented gaps, not vulnerabilities. Fail-closed items and future work live on
[ROADMAP.md](ROADMAP.md).

- `MCP_GW_AUTH_MODE=cimd` and `MCP_GW_AUTH_MODE=spiffe` refuse to start an HTTP listener (→ 0.3).
- Approval nonces, rate limiter buckets and circuit breaker state are per process. Multi-replica
  weakens all three until HA shared state lands (→ 0.2). See [docs/operations.md](docs/operations.md).
- Pass-through outbound credentials do not yet perform vault / RFC 8693 exchange (→ 0.2).
- VRP HMAC receipts are not Merkle/Ed25519 source-vs-sink proofs (→ 0.3).

## Hardening checklist

- Terminate TLS at the gateway (`MCP_GW_HTTP_TLS_ENABLED=true`) or in front of it. Plaintext on a
  non-loopback bind address is logged as a warning at startup and should be treated as a finding.
- Prefer `MCP_GW_AUTH_TOKENS_FILE` or OAuth over a single shared `MCP_GW_HTTP_BEARER_TOKEN`.
- Keep `MCP_GW_WRITE_ENABLED=false` wherever writes are not required.
- Set `MCP_GW_EGRESS_ALLOW_HOSTS` explicitly rather than relying on the default deny list alone.
- Pin the tool catalog with `MCP_GW_TOOLS_CATALOG` so an adapter cannot change its own tool surface.
- Give each caller the narrowest job, jar and resource allow lists that still let it work. An empty
  allow list denies everything, which is the intended failure direction.

## Credit

Reporters are credited in [CHANGELOG.md](CHANGELOG.md) unless they ask not to be.
