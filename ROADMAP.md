# Roadmap

Author: Viquar Khan.

**0.1.0 framing:** a faithful core control plane. What ships is listed under **Done in 0.1.0**.
Everything else from the design docs that is not that list lives in **future milestones below** —
not as unlabeled “almost done” in 0.1.0. Fail-closed stubs (CIMD/SPIFFE refuse-start, etc.) stay
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
- Thin HTTP adapters for additional engines (see [docs/adapters.md](docs/adapters.md)) — functional,
  not flagship depth
- Docs, examples, dist packaging

## Future roadmap

All design differences not listed above are deferred here.

### Milestone 0.2 — ops honesty, HA, outbound identity

1. **Per-caller outbound identity (Phase B)** — secret-store or RFC 8693 token exchange behind
   `CredentialResolver` (beyond pass-through Authorization headers).
2. **HA shared state (ADR 12)** — Redis (or equivalent) for nonces, rate limits, and token budgets;
   document single-replica limits until then.
3. **OpenTelemetry GenAI SDK bridge** — export `GenAiSpanObserver` `gen_ai.*` attributes through
   the OTel SDK (replace or augment structured logs).
4. **OAuth hardening** — multi-issuer / resource-indicator (broader RFC 8707 surface) as needed for
   real IdPs.
5. **Audit seal / rotate** — durable hash-chain seal and rotation story beyond `MCP_GW_AUDIT_FILE`.
6. **CI supply chain** — maven-enforcer, CVE scan, Cosign/SLSA; optionally promote CycloneDX
   SBOM from `-Prelease` into the always-on `package` phase; Apache RAT for license headers.
7. **Spark Connect (optional)** — native client behind a feature flag; keep HTTP SQL path.
8. **Kafka mutate depth** — selected Admin mutate paths beyond the current surface.
9. **Egress true IP pin (optional)** — stronger post-resolve pinning where the JDK allows it.

### Bootstrap / IP clearance (pre-vote, not a 0.1 blocker)

- **Apache-2.0 file headers** — apply a plain author-named Apache-2.0 header to every `*.java`
  (and practical `pom.xml` files) before donation; switch to the standard ASF header at
  incubation. Until then LICENSE/NOTICE cover the tree; RAT is not yet enforced.
- **SBOM default path** — either keep wording as “release profile (`-Prelease`)” or move
  `cyclonedx-maven-plugin` out of the release profile so plain `mvn package` emits `bom.xml`.

### Milestone 0.3 — differentiated proofs, auth modes, routing

1. **VRP cryptographic proof** — Merkle/Ed25519 (or agreed) source-vs-sink proof + offline verifier;
   keep dry-run / HMAC receipt.
2. **CIMD request auth** — wire request authentication when the SEP path is clear; until then keep
   refuse-start.
3. **SPIFFE / SPIRE** — workload API attestation for mTLS peers; until then keep refuse-start.
4. **RetrievalRouter + taxonomy** — per-request intent pruning + embeddings with a relevance floor
   (boot-time `MCP_GW_TOOL_INTENT` stays as the 0.1 bridge).
5. **Prompt-injection ML path** — optional PromptGuard-class model behind a flag (regex inbound +
   outbound remains the default tripwire).
6. **MCP Tasks** — long-running job protocol when the MCP extension is stable (sync-only until then).
7. **Semantic cache** — embedding similarity **or** rename the API/docs to exact-match cache.
8. **Full Cedar runtime (optional)** — replace cedar-lite / HTTP delegate with a real Cedar engine
   when packaging and license fit are clear.
9. **Broader referential DLP** — more PII shapes beyond email `PERSON_N`.

### Post-0.3 / deferred by design

- **A2A (ADR 13)** — addendum deferral; not a 0.1 defect.
- **Thin-adapter flagship depth** — deepen the ~29 HTTP adapters for engines operators actually run.
- **Iceberg engine-side maintenance** — live snapshot/file procedures when catalogs expose them
  over REST (dry-run only in 0.1).

## Design continuity

Filling a deferred item replaces the fail-closed or thinner implementation behind the same config
keys and SPI seams; it does not redesign the gateway.
