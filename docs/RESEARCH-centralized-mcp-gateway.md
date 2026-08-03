# Research note: centralized MCP gateway for the Apache data ecosystem

Author: Viquar Khan
Status: companion to DESIGN and LLD (Aegis MCP Gateway 0.1.0)
License: Apache License, Version 2.0

This note captures strategic architecture research that informs Aegis. It is not a second
source of truth. Where draft SEPs or KIPs are cited, they are labeled as draft or discussion.
Do not treat draft numbers as ratified MCP or Kafka APIs. No em dashes.

## 1. Why a parent gateway (the M x N problem)

Decentralized MCP (one agent to many independent engine servers) creates an M x N integration
surface: every client must discover tools, hold credentials, negotiate capabilities, and apply
policy against every backend. That yields:

- Localized, inconsistent security (each server reinvents allow lists and redaction).
- Context-window bloat (agents load overlapping, unscoped tool catalogs).
- Fragmented audit (no single deny taxonomy or hash-chained history).

A centralized parent MCP process with pluggable adapters collapses that to M + N: clients speak
to one endpoint; adapters plug into one governance chain. Aegis implements that pattern under
the Aegis codename until any ASF acceptance.

## 2. Proxy vs router vs gateway

Research and MCP gateway literature distinguish three mediation tiers. Aegis targets the third.

| Tier | Layer | What it does | Policy awareness | Aegis mapping |
| --- | --- | --- | --- | --- |
| Proxy | L4 | Forward bytes | None | Not the product goal |
| Router | L7 capability | Dispatch by tool name / taxonomy | Low | `TaxonomyRouter`, `ToolManifestAggregator` |
| Gateway | L7 policy | Identity, quotas, approval, redaction, audit | High | `InterceptorChain` + auth + FinOps + integrity |

A router alone solves discovery. A gateway is a trust boundary: it knows the principal, the tool
class (READ / MUTATE / DESTRUCTIVE), the token budget, and the compliance posture of the data
plane being touched.

## 3. Protocol dynamics (verified MCP direction)

Public MCP specification work (notably the 2026-07-28 line) moves the protocol toward a
**stateless request/response** core:

- Protocol-level sessions and sticky `Mcp-Session-Id` binding are removed or deprecated in favor
  of per-request metadata (see SEP-2567 sessionless MCP and related SEPs such as SEP-2575 for
  handshake removal; verify current status on the MCP site before coding to a number).
- Streamable HTTP remains the network surface. Revision notes remove the legacy GET event-stream
  endpoint in 2026-07-28. Aegis 0.1.0 exposes a single MCP endpoint at `/mcp` (plus ops
  `/healthz`, `/readyz`, `/metrics`). Do not assume a separate `/mcp/sse` path in new designs.
- Intermediary-friendly headers such as `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name`
  (SEP-2243 family) let gateways rate-limit and route without parsing JSON bodies. **Follow-up
  for Aegis:** optionally mirror or require these headers on Jetty for WAF and LB integration.
- Authorization hardens toward RFC 9207 issuer validation and Client ID Metadata Documents
  (CIMD; SEP-991 lineage). Aegis has `OAuthResourceFilter` and `CimdVerifier` stubs that fail
  closed until verification is complete.

Implication for deployment (already in LLD section 18): replicas can sit behind round-robin load
balancers without sticky sessions once process state (rate, nonce, audit) is externalized.

## 4. Interceptors (SEP-1763 / SEP-2624) and Aegis

The MCP Interceptors working group and proposals (motivation SEP-1763; active specification work
also tracked as SEP-2624 / experimental-ext-interceptors) introduce validators and mutators as
discoverable MCP primitives with a trust-boundary-aware execution model.

| Concept | Draft MCP interceptor idea | Aegis 0.1.0 |
| --- | --- | --- |
| Mutators | Transform payloads in priority order | `Mutator`, `ArgumentSanitizeMutator`; outbound `OutputControls` |
| Validators | Pass/fail with severity | Hard-coded steps 1-9 in `InterceptorChain.preflight`; `Decision` + `Severity` |
| Observers | Non-blocking telemetry | `Trace`, `Metrics`, `AuditLog` |
| Directionality | Sanitize inbound before validate; outbound validate then redact | Documented in LLD section 16 |
| Discovery | `interceptors/list`, `interceptor/invoke` | Not implemented (gateway-local chain only) |

Aegis deliberately ships a **gateway-local** interceptor chain so governance works before the
protocol extension ratifies. When SEP-2624 (or its successor) stabilizes, map Aegis steps onto
protocol interceptors without changing adapter SPI.

Important nuance from the research: draft text often says validators run in parallel. Aegis
keeps **ordered, first-denial-wins** validation because security properties (exposure before
backend, approval before mutate) depend on sequence. Parallel observation is fine; parallel
authorization is not.

## 5. Related control planes: MCP-Bastion vs Aegis

[MCP-Bastion](https://github.com/vaquarkhan/MCP-Bastion) is complementary, not a substitute:

| | MCP-Bastion | Aegis |
| --- | --- | --- |
| Shape | In-process middleware / optional proxy boundary | Parent MCP server with engine adapters |
| Config | `bastion.yaml` policy-as-code | `MCP_GW_*` env + `gateway.yaml` + per-adapter `tools.yaml` |
| Strength | Agent IAM, injection, PII, denial-of-wallet on any MCP server | Multi-engine SPI, taxonomy routing, Flink-proven write gates |
| Compose | Wrap a single engine MCP | Own the unified endpoint for Kafka/Flink/Spark/Iceberg |

Recommended enterprise compose: Bastion (or similar) for host-local agent IAM on non-Aegis
servers; Aegis as the governed data-plane gateway. Boundary mode (clients cannot reach backends
except through the gateway) matches Aegis HTTP deployment guidance.

## 6. Pluggable adapters and agent skills

Research on data-engineering agent skills (workflow presets for ingestion, streaming, lakehouse,
incident recovery) reinforces Aegis adapter design:

- Expose **formalized tools** with JSON Schema, not free-form shell.
- Keep **operating procedures** (readonly SQL guards, dry-run before destroy, approval for
  mutate) in the gateway or adapter contract, not in prompt text alone.
- Enable adapters via config (`MCP_GW_ADAPTERS`) without recompiling the parent.

Aegis taxonomy classes today: Flink `streaming`, Kafka `messaging`, Spark `batch`,
Iceberg `lakehouse`. Cross-adapter incident loops (Iceberg metadata -> Flink exceptions ->
Kafka schema -> approved stop/reset/commit) are the payoff narrative in LLD section 17.

## 7. Kafka adapter research (useful, carefully scoped)

Verified community direction (draft / discussion KIPs; not product APIs until accepted):

| Topic | Reference | Adapter implication |
| --- | --- | --- |
| Tiered storage cost attribution | KIP-1267 (cwiki) | Future READ tools for FinOps / rogue consumer detection |
| Share group DLQ circuit breaker | KIP-1316 (depends on DLQ KIPs) | Align agent remediation with pause-on-overflow semantics |
| Mandatory DLQ disposition headers | KIP-1317 | Enrich `inspect_dlq` to surface disposition metadata when present |

Aegis Kafka tools already include `describe_topic`, `query_schema_registry`, `inspect_dlq`,
and gated mutate/destructive ops backed by AdminClient and Schema Registry HTTP. Do not register
tools that claim KIP APIs until those KIPs ship in a Kafka release the adapter pins.

## 8. Flink adapter research

The Flink enterprise MCP pattern (resources for exceptions/metrics, tools for savepoint /
rescale / stop, SQL Gateway with readonly guard) is already the strongest Aegis adapter. Useful
reinforcements from research:

- Prefer **resources** for high-churn telemetry (`flink://...`) to reduce tool-call churn.
- Cross-engine remediation requires the parent gateway so one agent context spans Kafka lag and
  Flink watermarks without reconnecting.
- Keep outbound credentials mapped per caller (`CredentialResolver` / `OutboundAuth`), never
  forward inbound bearer tokens to the Flink REST API.

## 9. VRP / PVDM (author-owned, optional)

Physical-Verify-Durable-Metadata (PVDM) and validate-run-promote (VRP) appear in the research as
integrity gates for lakehouse commits. Aegis 0.1.0 implements a **dry-run receipt** VRP in
`VrpValidator` so Iceberg maintenance and `commit_transaction` can require a prior dry run.
Cryptographic offline `verify_vrp` with PASS/FAIL/UNVERIFIED and signed provenance receipts remain
optional author-owned extensions (LLD section 14).

## 10. Trust-boundary execution order (adopted)

Inbound (agent -> backend):

1. Mutate (sanitize) atomically.
2. Validate in fixed security order (Aegis steps 1-9).
3. Observe.
4. Execute on a bounded pool.

Outbound (backend -> agent):

1. Observe / inspect.
2. Bound then redact (so truncation cannot hide a partial secret).
3. Return.

Mutations are all-or-nothing at the chain level: Aegis applies mutators before preflight; a
denial short-circuits without backend side effects.

## 11. Gaps this research suggests for the roadmap

Prioritized follow-ups that strengthen ASF-facing review without inventing APIs:

1. Honor / emit `Mcp-Method` and `Mcp-Name` (and protocol version headers) on Streamable HTTP.
2. Complete CIMD verification (OAuth JWKS is in 0.1.0); keep fail-closed until then.
3. Externalize rate limiter, nonce store, and audit sink for true multi-replica HA.
4. Optional OpenTelemetry spans alongside Prometheus (LLD already diagrams the collector).
5. Disposition-aware `inspect_dlq` when KIP-1191/1316/1317 land.
6. Protocol-level interceptor discovery only after SEP-2624 (or successor) is accepted.
7. Per-adapter thread pools so one slow engine cannot saturate the shared queue.
8. Compose guide: Aegis gateway + Bastion middleware for mixed fleets.

## 12. Sources (non-exhaustive)

- MCP Streamable HTTP and 2026-07-28 blog / changelog (modelcontextprotocol.io)
- SEP-2567 sessionless MCP; SEP-2243-style method/name headers; CIMD / SEP-991 discussions
- MCP Interceptors WG; SEP-1763 motivation; SEP-2624 / experimental-ext-interceptors
- vaquarkhan/MCP-Bastion (middleware and gateway boundary docs)
- Apache Kafka cwiki: KIP-1267, KIP-1316, KIP-1317 (draft / discussion status)
- Aegis DESIGN and LLD in this repository

When in doubt, prefer the ratified MCP specification text and Apache project cwiki over
secondary research prose.
