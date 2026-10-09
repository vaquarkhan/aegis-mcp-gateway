<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# End-to-end claims validation (2026-10-05)

This is a historical, `recorded-unattested` day record from branch
`aegis-2.0-feature-brach-for-review` (uncommitted import plus fixes). It supplements
[CLAIMS-MATRIX.md](CLAIMS-MATRIX.md) and [VALIDATION.md](../VALIDATION.md), but it is not immutable
proof for the current working tree. No commit/tree manifest or immutable raw run bundle was retained.

## Environment

| Part | Version |
| --- | --- |
| OS | Windows 11 x64 |
| General build and gateway processes | JDK 21.0.11 |
| Token-exchange Aegis process | JDK 17, as recorded in [TOKEN-EXCHANGE-KEYCLOAK.md](TOKEN-EXCHANGE-KEYCLOAK.md) |
| Keycloak process | JDK 21.0.12, as recorded in [TOKEN-EXCHANGE-KEYCLOAK.md](TOKEN-EXCHANGE-KEYCLOAK.md) |
| Maven | 3.9.x |
| Docker | Postgres 17.6 (`aegis-pg`), HashiCorp Vault 1.17.6 dev (`aegis-vault`, KV v2) |
| Knox | 3.0.0 at `C:\knox\knox-3.0.0` |
| Helm / kubeconform | 4.3.0 / 0.8.0 (local install under `C:\tools\aegis-claim-tools`) |
| Keycloak | 26.8.0 (`keycloak_e2e.py`, all checks reported passed) |

## Results summary

Every outcome in this table is `recorded-unattested`. Commands are exact only where this record
retained them; otherwise the missing invocation is stated rather than reconstructed as a prior command.

| Area | Command / artifact retained in this record | Historical outcome |
| --- | --- | --- |
| Default reactor + opt-in ITs | Exact Maven invocations were not retained. The record named the `release`, `postgres-integration`, and `vault-integration` profiles. | Zero failures were reported in then-present Surefire reports. |
| Real engines (subset) | The record names `ZooKeeperFrontDoorsTest`, `KafkaFrontDoorsTest`, `IcebergFrontDoorsTest`, `RealFlinkTest`, `RealSparkTest`, `OpenApiDraftIcebergTest`, `A2aAgentFrontDoorsTest`, and `RealSparkConnectServerTest`; the exact selecting command was not retained. | All executed tests were reported passed after the `plugin.classpath` fix. |
| Knox bundle | `aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar` SHA-256 `0726C4CE71D23856F8FE9A58BE2C80E7FC7E4AE740ED70E8ECC8CBFB2617B780` | This was a 2026-10-05 artifact, separate from the 2026-10-04 bundle recorded in [RELEASE-ASSURANCE.md](RELEASE-ASSURANCE.md). |
| Hooks | The record names `test_hooks.py` and `e2e_gateway.py`; exact invocations were not retained. | 12/12 and 7/7 checks were reported passed. |
| Helm H11 | The record names lint, kubeconform 1.33, deny templates, and `charts/aegis/ci/start_rendered.py`; exact invocations were not retained. | PASS was reported. |
| Token exchange S21 | See the exact script command and evidence boundary in [TOKEN-EXCHANGE-KEYCLOAK.md](TOKEN-EXCHANGE-KEYCLOAK.md). | All checks were reported passed. |
| Docker gateway | Image `aegis-gateway:2.0.0-SNAPSHOT`, `/readyz` smoke; exact build and smoke commands were not retained. | PASS was reported. |

The different Knox bundle hashes in this file and [RELEASE-ASSURANCE.md](RELEASE-ASSURANCE.md) identify
separate dated build artifacts. They must not be treated as hashes of one artifact.

## Historical boundaries

- **S14:** `PostgresStateStoreIT` on this date used loopback PostgreSQL **17.6**, not a later
  temporary managed PostgreSQL run whose engine version was not retained.
- **S18:** Vault integration used **HashiCorp Vault 1.17.6** dev on loopback; API behavior was compared
  with the separately recorded OpenBao KV v2 run in [SECRETS-OPENBAO.md](SECRETS-OPENBAO.md).
- **H03:** The incomplete Knox cold-install evidence predates this record and is
  `recorded-unattested`; see [knox-3.0.0-cold-install/README.md](knox-3.0.0-cold-install/README.md).
- **RealIcebergRestCatalogTest:** was reported skipped without `AEGIS_ICEBERG_REST_URL`;
  `IcebergFrontDoorsTest` covered the catalog in that historical run.
- Source and test changes after 2026-10-05 mean these outcomes cannot establish current behavior.

## Fixes recorded with this validation

- `BoundedHttp` 3xx handling ([DEFECT-LOG.md](../DEFECT-LOG.md) D30).
- `run-example.ps1` / `run-example.sh` build Knox typed-source `plugin.classpath` (D31).
- `.gitignore` excludes local Helm render YAML from RAT.
- Documentation recorded the later H10 bundle hash, coverage table, and this day record.
