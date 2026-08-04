
# Aegis MCP Governance Gateway - Proposal: Design and Reference Implementation

Author: Vaquar Khan
Status: draft for 0.1.0
License: Apache License, Version 2.0

This proposal describes what Aegis is, the design behind it, and the honest state of the
reference implementation that already exists. It is meant to be read alongside the end to
end design document. It has no em dashes.

## 1. Summary

Aegis is a vendor neutral, secure by default Model Context Protocol (MCP) governance
gateway. It puts one governed, auditable control plane in front of many Apache data and
streaming engines so an AI agent never talks to Flink, Kafka, Spark, or Iceberg directly.
Every action passes one fail closed chain: read only by default, per caller identity and
scope, a policy decision, approval for destructive actions, egress and request forgery
defense, rate limiting, circuit breaking, prompt injection screening, output bounding and
redaction, tool catalog integrity, and a tamper evident audit trail. Engines plug in
through a narrow service provider interface and contribute no policy.

There is already a working reference implementation. This proposal states plainly what is
built, what is a fail closed stub, and what is deferred, because a reviewer should see the
maturity line clearly.

## 2. The problem

An MCP server that can reach an engine control plane is a privileged client. Shipping one
safely means shipping an access control product. Today teams wire one MCP server per
engine, each re-implementing that security model inconsistently or skipping it and staying
read only. Every new engine multiplies the review surface, and most such servers are
single maintainer projects with no shared audit and no common policy. There is no neutral
standard for how an agent operates Apache data systems safely.

## 3. How the design solves it

One governed gateway process sits in front of every engine. Governance is written once,
tested once, and applied uniformly through one ordered, deny by default chain. The value is
three things at once:

- Security. Fail closed identity, per caller scope, approval tokens for destructive
  actions, egress and server side request forgery defense, output redaction, and a hash
  chained audit. First denial wins and returns a stable code.
- Easy to maintain. Adding an engine is a small adapter, not a new security model. There is
  one chain to review and harden, so the security review surface stays flat as engines are
  added.
- Neutral home. Under Apache governance this becomes a shared, vendor neutral standard for
  agent access to Apache systems rather than another single vendor server.

The full architecture, interceptor chain, decision records, and low level design are in the
end to end design document. The interceptor chain in brief:

| Step | Control | Deny code |
| --- | --- | --- |
| 1 | Exposure | NOT_EXPOSED |
| 2 | Read only and scope | READONLY_CALLER, SCOPE_DENIED |
| 3 | Policy decision point | POLICY_DENIED |
| 4 | Approval token | APPROVAL_REQUIRED |
| 5 | Egress and SSRF | EGRESS_DENIED |
| 6 | Rate limiter | RATE_LIMITED |
| 7 | Circuit breaker | BREAKER_OPEN |
| 8 | Prompt injection | PROMPT_INJECTION |
| 9 | Integrity gate | VRP_FAILED |
| 10 | Execute with timeout | INVALID_INPUT, TIMEOUT, BACKEND_ERROR |

## 4. Reference implementation: what exists today

The reference implementation is a Java multi module Maven project that builds green and is
tested.

- 36 Maven modules: a gateway core, four in depth engine adapters (Flink, Kafka, Spark,
  Iceberg), a set of thinner adapters for the wider Apache data platform, and a shaded
  runnable distribution.
- Builds with JDK 21, runs on JDK 17 or newer, Maven 3.9 or newer.
- 300 unit and integration tests across 55 test suites, all passing, zero failures and zero
  errors on a clean build.

### 4.1 Implemented and working

- The governance chain with ordered steps and first denial wins semantics.
- Identity on the HTTP transport: bearer token, a hashed multi caller token registry with
  per caller scope, and an OAuth 2.1 resource server mode with JWKS validation. Constant
  time comparison for secrets. Fail closed startup validation.
- Approval tokens: HMAC-SHA256 signed, TTL bounded, single use, tool and scope bound, with
  replay rejection.
- Egress and server side request forgery defense: an allow list plus an unconditional deny
  of cloud metadata and link local, loopback, multicast, and reserved ranges, enforced
  against resolved addresses and numeric IP encodings, with redirects disabled on outbound
  clients.
- Read only SQL guard, identifier validation against path injection, output bounding, and
  redaction of secrets before results reach the model.
- Tamper evident hash chained audit, Prometheus metrics, and a builtin policy decision
  point.
- Two transports: stdio and authenticated Streamable HTTP with TLS, plus health, readiness,
  and metrics endpoints.
- Four in depth adapters (Flink REST and SQL gateway, Kafka AdminClient and Schema
  Registry, Spark History and Livy, Iceberg REST catalog) plus thinner HTTP adapters for
  many more Apache systems.

### 4.2 Fail closed stubs (deny rather than pretend)

These select a real object that denies, so denial is always the safe answer while the
implementation is completed:

- Cedar and OPA external policy engines deny every call; only the builtin PDP decides.
- CIMD authentication verifies a document but does not yet authenticate a request; that
  auth mode refuses to start.
- SPIFFE mTLS peer authentication denies all peers; that auth mode refuses to start.

### 4.3 Partial or deferred

- Per caller outbound credential propagation to the engine is specified and the SPI exists,
  but no adapter maps caller identity to a downstream credential yet, so outbound calls use
  a shared service identity. This is a priority item.
- The integrity gate ships as a dry run receipt (a hashed argument fingerprint with a TTL),
  not yet the fuller verifiable reconciliation proof in the design.
- Semantic tool selection uses simple term overlap and is not yet wired into the request
  path; taxonomy routing is a lookup today.
- OpenTelemetry spans, agent to agent flows, externalized high availability state, and
  asynchronous long running tasks are designed and on the roadmap, not implemented.

## 5. Design to code conformance

| Design element | Status in reference code |
| --- | --- |
| Interceptor chain, first denial wins, stable deny codes | Implemented |
| Read only by default, explicit write unlock | Implemented |
| Approval tokens, single use, scope and time bound | Implemented |
| Per caller identity and scope, OAuth RS, token file | Implemented |
| Egress and SSRF defense with resolved IP checks | Implemented |
| Rate limiter, circuit breaker, timeout, output redaction | Implemented |
| Tamper evident audit, Prometheus metrics | Implemented |
| Tool catalog integrity (digest drift detection) | Implemented (drops drifting tools with a warning) |
| Builtin PDP | Implemented |
| Cedar and OPA PDP | Fail closed stub |
| CIMD and SPIFFE auth | Fail closed stub |
| Per caller outbound credentials | Partial, SPI only |
| Verifiable reconciliation proof | Partial, dry run receipt |
| Semantic routing, OpenTelemetry, A2A, HA state, async tasks | Deferred |

About 35 design items are fully present; roughly 20 are stubbed, partial, or deferred, and
those are the roadmap.

## 6. Roadmap

| Phase | Focus |
| --- | --- |
| Controls to green | Complete Cedar and OPA engines, CIMD and SPIFFE identity, and per caller outbound identity |
| Depth and breadth | Deepen the thin adapters; grow per engine contributors |
| Hardening | OpenTelemetry spans, verifiable integrity proof, semantic routing wired into the path, externalized HA state, asynchronous tasks |
| Community | Diverse, multi organization contributor base and a cadence of releases |

## 7. Language and technology choices

Java on the JVM (17 or newer) for the core and reference adapters, because the engine
clients are JVM native, the validated Flink server is reusable, MCP Java performance is
strong, and the security primitives are reviewable. The wire contract and the adapter SPI
are language neutral, so non JVM adapters and optional Python sidecars for machine learning
controls can join behind the same contract. Runtime dependencies are Apache 2.0 or
compatible; no GPL or category X dependency in the core.

## 8. Why Apache

Agentic access to data systems is a growing need, and it is served today by fragmented,
single vendor, mostly read only servers with no shared governance. A neutral, community
owned gateway under Apache governance gives the ecosystem one honest standard for how
agents operate Apache systems safely. The core is deliberately small and the adapters are
independent, which makes per engine work a natural way for people from the Flink, Kafka,
Spark, and Iceberg communities to contribute.

## 9. Honest limitations

- The project is currently a single contributor effort; growing a diverse community is the
  main reason to bring it to a neutral home.
- Several differentiated controls (external PDP integration, per caller identity, the
  verifiable proof, semantic routing, and telemetry spans) are stubbed or deferred and are
  labeled as roadmap, not as done.
- The reference implementation is validated by its own build and tests; live validation is
  strongest for Flink, and the other engine integrations carry real world unknowns.

## 10. Related documents

- End to end design (requirements, high level design, low level design):
  END-TO-END-DESIGN-aegis-mcp-gateway.md
- Reference implementation source: the aegis-mcp-gateway repository.
