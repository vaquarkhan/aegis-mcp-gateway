# Roadmap

Author: Viquar Khan.

Architecture and governance in 0.1.0 match the DESIGN and LLD. Remaining gaps below are
**intentional stubs** (documented, fail-closed) or deferred depth on backends. They are not a
different design: the SPI, interceptor order, deny codes, write unlock, and adapter taxonomy stay
stable while these pieces grow into the slots already reserved for them.

## Done in 0.1.0

- Governed multi-adapter MCP gateway core (10-step chain, deny taxonomy, audit, egress, DLP)
- Flink reference adapter (REST + SQL Gateway)
- Kafka AdminClient + Schema Registry + DLQ inspect
- Spark History + Livy (+ optional SQL HTTP)
- Iceberg REST catalog reads/mutates + VRP dry-run receipts
- OAuth resource-server admission when JWKS is configured (`JwksJwtValidator`)
- HTTP scaffolds for additional Apache data-platform engines (see [docs/adapters.md](docs/adapters.md))
- Docs, examples, dist packaging

## Intentional stubs (documented, fail-closed)

These hooks exist so operators and reviewers see the target shape without a silent allow path.
Until each item ships for real, behavior stays closed or deny-all.

| Item | 0.1.0 posture | Reason it is stubbed / deferred |
| --- | --- | --- |
| OAuth JWKS completeness | Admit only when `MCP_GW_OAUTH_JWKS_URL` is set; without JWKS every request is 401 | Basic verify is in; multi-issuer, aggressive key rotation edge cases, and RFC 8707 resource-indicator hardening remain so we do not claim a full OAuth product surface yet |
| CIMD | `authMode=cimd` refuses to start HTTP; `CimdVerifier` does not admit clients | Stateless client metadata (SEP/CIMD lineage) is still drafting; admitting on an unverified `client_id` URL would widen the trust boundary |
| SPIFFE / SPIRE | `authMode=spiffe` refuses to start; `SpiffeMtls` denies peers | Workload API / SVID attestation needs a pinned SPIRE integration; a half-wired mTLS path would look like production identity when it is not |
| Live Cedar PDP | `CedarPdp` deny-all | No Cedar runtime is bundled; shipping an unevaluated "allow" would violate fail-closed authz |
| Live OPA PDP | `OpaPdp` deny-all | Same reason: remote OPA evaluation, timeouts, and decision caching are not wired; builtin PDP is the supported path |
| Kafka mutate depth | AdminClient wired for common ops; KIP-specific DLQ disposition and some broker-only ops need a live cluster / future KIPs | Avoid inventing KIP APIs; keep gated tools honest about what the pin supports |
| Spark mutate / SQL depth | History + Livy live; `run_sql_readonly` needs `SPARK_SQL_HTTP_URL`; no native Spark Connect client | Connect/Thrift clients are version-coupled; HTTP facade keeps the adapter independent until Connect is an explicit optional dep |
| Iceberg mutate depth | REST create/alter/drop/commit live; `expire_snapshots` / `remove_orphan_files` / `rewrite_data_files` fail closed unless dry-run | Those ops are engine-side procedures, not portable REST; dry-run + VRP preserve the governance story without fake success |
| OpenTelemetry spans | Not shipped; Prometheus `/metrics` + hash-chained audit remain | Spans need a stable exporter story; metrics and audit already cover the 0.1 ops contract |
| Redis (or shared) HA state | Rate limiter, approval nonces, and audit are in-process | Multi-replica correctness needs a shared store; claiming HA without it would understate per-replica limits |

## Future work (beyond stubs)

- Deepen selected HTTP scaffolds to native clients (Pulsar Admin, Airflow auth, Arrow Flight)
- Protocol interceptor discovery when SEP-2624 (or successor) is accepted
- `Mcp-Method` / `Mcp-Name` enforcement profile for WAFs and intermediaries
- Optional semantic-cache / retrieval routing enhancements without widening authorized tool sets

## Design continuity

If a capability is incomplete, Aegis **denies or refuses to start** rather than pretending it works.
Filling a stub replaces the fail-closed implementation behind the same config keys and SPI seams;
it does not redesign the gateway.
