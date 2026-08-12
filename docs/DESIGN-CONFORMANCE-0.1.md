# Design conformance matrix (0.1.0)

Author: Viquar Khan.

Honest mapping of DESIGN / LLD claims to the **shipped** 0.1.0 reference implementation.
Anything not **Done** here is deferred to the [future roadmap](../ROADMAP.md) — not sold as
implemented in this release.

Status legend:

| Status | Meaning |
| --- | --- |
| **Done** | Implemented and usable in 0.1.0 |
| **Roadmap** | Not 0.1.0 scope; see [ROADMAP.md](../ROADMAP.md) milestone |

---

## Governance and security

| Design item | Status | 0.1.0 reality |
| --- | --- | --- |
| 10-step interceptor chain + deny codes | **Done** | First denial wins; mutators + observers wired |
| Read-only default + write unlock + approval HMAC | **Done** | Unlock requires secret; single-use nonces in-process |
| Builtin PDP (deny globs) | **Done** | `MCP_GW_PDP=builtin` |
| Cedar PDP (cedar-lite / HTTP delegate) | **Done** | Deny-file or OPA-shaped HTTP; not a full Cedar runtime → **0.3** |
| OPA PDP (live HTTP) | **Done** | Fail-closed POST to OPA data URL |
| Tokenfile / shared bearer auth | **Done** | SHA-256 token file + constant-time compare |
| OAuth resource server + JWKS | **Done** | RSA/EC verify, iss/aud/exp; multi-issuer → **0.2** |
| CIMD auth mode | **Roadmap** | Refuse-start until **0.3** |
| SPIFFE mTLS | **Roadmap** | Refuse-start until **0.3** |
| Pass-through outbound credentials | **Done** | Flink/Kafka/Spark/Iceberg + bootstrap fallback; vault/RFC 8693 → **0.2** |
| Egress allow list + metadata deny | **Done** | Arg check + encodings + resolve deny + `Redirect.NEVER` |
| Rate limit + circuit breaker + timeout | **Done** | In-process; shared store → **0.2** HA |
| Prompt-injection (regex in + out) | **Done** | ML path → **0.3** |
| Output DLP (secrets + `PERSON_N` email) | **Done** | More PII shapes → **0.3** |
| Tool catalog integrity + fail-closed | **Done** | Durable pins; `MCP_GW_CATALOG_FAIL_CLOSED` |
| VRP dry-run + optional HMAC | **Done** | Merkle/Ed25519 proof → **0.3** |
| Hash-chained audit + optional file | **Done** | Seal/rotate → **0.2** |

---

## Intelligence and routing

| Design item | Status | 0.1.0 reality |
| --- | --- | --- |
| TaxonomyRouter (lookup) | **Done** | Richer placement → **0.3** |
| RetrievalRouter (boot `MCP_GW_TOOL_INTENT`) | **Done** | Per-request + embeddings → **0.3** |
| SemanticCache (exact-match TTL) | **Done** | True semantic or rename → **0.3** |

---

## Protocol and scale

| Design item | Status | 0.1.0 reality |
| --- | --- | --- |
| stdio + streamable HTTP | **Done** | MCP endpoint + ops probes |
| HA externalized state (ADR 12) | **Roadmap** | **0.2** Redis (or equiv.) |
| MCP Tasks | **Roadmap** | **0.3** |
| A2A (ADR 13) | **Roadmap** | Post-0.3 |

---

## Observability and supply chain

| Design item | Status | 0.1.0 reality |
| --- | --- | --- |
| Trace id (MDC) + Prometheus `/metrics` | **Done** | Hand-rolled counters |
| GenAI-shaped span logs | **Done** | OTel SDK bridge → **0.2** |
| `/readyz` catalog + optional URL ping | **Done** | — |
| CycloneDX SBOM plugin | **Done** | Produced by the release build (`-Prelease`); CI enforce + CVE → **0.2** |
| maven-enforcer / Cosign / SLSA / CI | **Roadmap** | **0.2** |

---

## Adapters

| Design item | Status | 0.1.0 reality |
| --- | --- | --- |
| Flink (REST + SQL Gateway) | **Done** | Reference depth |
| Kafka (Admin + SR + DLQ) | **Done** | Selected mutate depth → **0.2** |
| Iceberg REST reads/mutates | **Done** | Engine maintenance live → post-0.3 |
| Spark History + Livy (+ HTTP SQL) | **Done** | Spark Connect → **0.2** |
| Thin HTTP adapters | **Done** | Flagship depth → post-0.3 on demand |

---

All **Roadmap** rows and every “→ milestone” note are owned by [ROADMAP.md](../ROADMAP.md).
