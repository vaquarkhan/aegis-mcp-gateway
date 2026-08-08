# Roadmap

Author: Viquar Khan.

**0.1.0 framing:** a faithful core control plane. What ships is listed under **Done in 0.1.0**.
Everything else from the design docs that is not that list lives in **future milestones below** -
not as unlabeled "almost done" in 0.1.0. Fail-closed stubs (CIMD/SPIFFE refuse-start, etc.) stay
refuse-start until their milestone lands.

SPI, interceptor order, deny codes, write unlock, and adapter taxonomy stay stable across
milestones. Incomplete capabilities **deny or refuse to start** rather than pretend to work.

Conformance snapshot of what 0.1.0 actually implements:
[docs/DESIGN-CONFORMANCE-0.1.md](docs/DESIGN-CONFORMANCE-0.1.md).

## Done in 0.1.0

- Governed multi-adapter MCP gateway core (10-step chain, deny taxonomy, audit, egress, DLP)
- Connect-time egress resolve + deny for metadata/link-local + `Redirect.NEVER` on outbound HTTP
- Durable tool digest pins when `MCP_GW_TOOLS_CATALOG` supplies `schemaDigest`
- Optional catalog fail-closed startup (`MCP_GW_CATALOG_FAIL_CLOSED` / `catalog.failClosed`)
- Optional durable audit file (`MCP_GW_AUDIT_FILE`) + `/audit/verify`
- Auth-mode fail-closed: `oauth` requires JWKS; bearer alone cannot pretend to be OAuth
- Live OPA PDP HTTP evaluation (fail-closed) + cedar-lite deny file / HTTP delegate
- Pass-through per-caller outbound credentials on Flink/Kafka/Spark/Iceberg (+ bootstrap fallback)
- Referential email DLP (`PERSON_N`) + outbound prompt-injection scan
- VRP dry-run receipts with optional HMAC (`vrp1.`) when signing secret is set
- Retrieval prune via `MCP_GW_TOOL_INTENT` at boot; `/readyz` catalog + optional `MCP_GW_READY_URL`
- GenAI-shaped span logs (`GenAiSpanObserver`) without bundling an OTel SDK
- Flink reference adapter (REST + SQL Gateway)
- Kafka AdminClient + Schema Registry + DLQ inspect
- Spark History + Livy (+ optional SQL HTTP)
- Iceberg REST catalog reads/mutates + VRP dry-run receipts
- OAuth resource-server admission when JWKS is configured (`JwksJwtValidator`)
- Thin HTTP adapters for additional engines (see [docs/adapters.md](docs/adapters.md)) - functional,
  not flagship depth
- Docs, examples, dist packaging

## Future roadmap

All design differences not listed above are deferred here.

### Milestone 0.2 - ops honesty, HA, outbound identity

1. **Per-caller outbound identity (Phase B)** - secret-store or RFC 8693 token exchange behind
   `CredentialResolver` (beyond pass-through Authorization headers).
2. **HA shared state (ADR 12)** - Redis (or equivalent) for nonces, rate limits, and token budgets;
   document single-replica limits until then.
3. **OpenTelemetry GenAI SDK bridge** - export `GenAiSpanObserver` `gen_ai.*` attributes through
   the OTel SDK (replace or augment structured logs).
4. **OAuth hardening** - multi-issuer / resource-indicator (broader RFC 8707 surface) as needed for
   real IdPs.
5. **Audit seal / rotate** - durable hash-chain seal and rotation story beyond `MCP_GW_AUDIT_FILE`.
6. **CI supply chain** - maven-enforcer, CVE scan, Cosign/SLSA; optionally promote CycloneDX
   SBOM from `-Prelease` into the always-on `package` phase; Apache RAT for license headers.
7. **Spark Connect (optional)** - native client behind a feature flag; keep HTTP SQL path.
8. **Kafka mutate depth** - selected Admin mutate paths beyond the current surface.
9. **Egress true IP pin (optional)** - stronger post-resolve pinning where the JDK allows it.

### Bootstrap / IP clearance (pre-vote, not a 0.1 blocker)

- **Apache-2.0 file headers** - apply a plain author-named Apache-2.0 header to every `*.java`
  (and practical `pom.xml` files) before donation; switch to the standard ASF header at
  incubation. Until then LICENSE/NOTICE cover the tree; RAT is not yet enforced.
- **SBOM default path** - either keep wording as "release profile (`-Prelease`)" or move
  `cyclonedx-maven-plugin` out of the release profile so plain `mvn package` emits `bom.xml`.

### Milestone 0.3 - differentiated proofs, auth modes, routing

1. **VRP cryptographic proof** - Merkle/Ed25519 (or agreed) source-vs-sink proof + offline verifier;
   keep dry-run / HMAC receipt.
2. **CIMD request auth** - wire request authentication when the SEP path is clear; until then keep
   refuse-start.
3. **SPIFFE / SPIRE** - workload API attestation for mTLS peers; until then keep refuse-start.
4. **RetrievalRouter + taxonomy** - per-request intent pruning + embeddings with a relevance floor
   (boot-time `MCP_GW_TOOL_INTENT` stays as the 0.1 bridge).
5. **Prompt-injection ML path** - optional PromptGuard-class model behind a flag (regex inbound +
   outbound remains the default tripwire).
6. **MCP Tasks** - long-running job protocol when the MCP extension is stable (sync-only until then).
7. **Semantic cache** - embedding similarity **or** rename the API/docs to exact-match cache.
8. **Full Cedar runtime (optional)** - replace cedar-lite / HTTP delegate with a real Cedar engine
   when packaging and license fit are clear.
9. **Broader referential DLP** - more PII shapes beyond email `PERSON_N`.

### Milestone 0.4 - Apache ecosystem moat (net-new)

Items below are **not** covered by 0.2 / 0.3 above. They are what make Aegis an Apache data-estate
governance gateway rather than a generic MCP proxy. Priority order reflects differentiation first.

**Moat pitch (when combined with existing VRP crypto on 0.3):** agent access to the Apache data
estate, enforced by existing Ranger policies, recorded as Atlas lineage, with cryptographic
source-to-sink proof. A generic gateway cannot copy that without rebuilding Apache data-governance
integration.

1. **Ranger-native policy enforcement** - flip the existing Ranger adapter from a tool *target*
   into a PDP backend: consult Apache Ranger policies so agent access honors the org's live
   data-access rules instead of a parallel policy set.
2. **Atlas lineage and provenance emission** - emit every governed agent action as lineage /
   provenance into Apache Atlas so "which agent touched this table, under what purpose, approved
   by whom" is a catalog query, not only a local hash chain.
3. **Engine-aware impact preview** - first-class blast-radius preview before destructive ops
   (Flink savepoint / job impact, Spark estimated cost/rows, Iceberg files/snapshots a maintenance
   call would delete). Generalize Iceberg `dry_run_maintenance` across deep adapters; a generic
   proxy cannot, because it does not understand the engine.

#### Governance primitives

4. **Dual-control / M-of-N approval** - for the sharpest tools (e.g. Flink `run_jar`, Kafka
   `delete_records`), require two humans or human-plus-policy beyond the single HMAC token.
5. **Purpose binding and classification-aware policy** - declared purpose per session; enforce
   purpose limitation and classification tiers (PII / PHI / PCI) on outputs beyond regex DLP.
6. **Break-glass access** - time-boxed elevated scope with mandatory post-hoc review, fully audited.
7. **Change-window and residency policies** - deny destructive ops outside maintenance windows;
   geo-fence which engines / data are reachable from where.
8. **Session-level and behavioral guardrails** - policy across a multi-step agent plan (e.g. cap
   destructive ops per plan) and anomaly-throttle abnormal tool sequences; per-call gates alone
   miss plan-level abuse.
9. **Model / agent identity attestation** - bind calls to which model and prompt version is
   acting; allow-list models so provenance answers *who and what*, not only which caller token
   (distinct from SPIFFE peer mTLS on 0.3).

#### Trust and compliance

10. **Policy simulation / shadow mode** - evaluate a candidate policy against recorded audit
    traffic before enforcing, so operators can dry-run governance changes.
11. **Compliance mappings and reports** - map audit events to NIST AI RMF, ISO 42001, and EU AI
    Act controls and emit auditor-ready reports (proposal alignment to durable output).
12. **Signed tool catalogs / adapter provenance** - extend durable digest pins to signed catalogs
    and verified adapter provenance (supply-chain story beyond CI Cosign/SLSA on 0.2).
13. **Human-in-the-loop approval console** - Slack or web workflow for the approval gate so
    destructive paths are usable by real ops teams.

#### Candidate adapters (control-plane risk / sensitive data)

Add only where an agent would operate a control plane that carries risk or touches sensitive data
(same principle that scoped out Tomcat / httpd / Maven). Not a rehash of the existing 4 deep +
29 thin catalog:

| Priority | Project | Why |
| --- | --- | --- |
| High | **Apache Kyuubi** | Multi-tenant Spark / Flink / Trino SQL gateway; privileged SQL surface agents hit directly |
| High | **Apache Polaris** | Iceberg REST catalog with its own access control; pairs with Iceberg depth + catalog governance |
| High | **Apache Gravitino** | Unified metadata / catalog lake; pairs with Ranger / Atlas direction |
| Medium | **Apache DolphinScheduler** | Orchestration control plane (Airflow-class risk) |
| Medium | **Apache SeaTunnel** | Data-integration control plane |
| Medium | **Apache InLong** | Data-integration / ingestion control plane |
| Medium | **Apache Zeppelin** | Notebook surface agents drive |
| Medium | **Apache StreamPark** | Flink / Spark app management surface |

### Post-0.3 / deferred by design

- **A2A (ADR 13)** - addendum deferral; not a 0.1 defect.
- **Thin-adapter flagship depth** - deepen the ~29 HTTP adapters for engines operators actually run.
- **Iceberg engine-side maintenance** - live snapshot/file procedures when catalogs expose them
  over REST (dry-run only in 0.1).

## Design continuity

Filling a deferred item replaces the fail-closed or thinner implementation behind the same config
keys and SPI seams; it does not redesign the gateway.
