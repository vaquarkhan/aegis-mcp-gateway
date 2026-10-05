<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# End-to-end claims validation (2026-10-05)

Working tree on branch `aegis-2.0-feature-brach-for-review` (uncommitted import plus fixes). This record
supplements [CLAIMS-MATRIX.md](CLAIMS-MATRIX.md) and [VALIDATION.md](../VALIDATION.md).

## Environment

| Part | Version |
| --- | --- |
| OS | Windows 11 x64 |
| JDK | 21.0.11 (build and gateway); Keycloak dev server |
| Maven | 3.9.x |
| Docker | Postgres 17.6 (`aegis-pg`), HashiCorp Vault 1.17.6 dev (`aegis-vault`, KV v2) |
| Knox | 3.0.0 at `C:\knox\knox-3.0.0` |
| Helm / kubeconform | 4.3.0 / 0.8.0 (local install under `C:\tools\aegis-claim-tools`) |
| Keycloak | 26.8.0 (`keycloak_e2e.py`, all checks passed) |

## Results summary

| Area | Command / artifact | Outcome |
| --- | --- | --- |
| Default reactor + opt-in ITs | Prior `mvn -Prelease verify`, `-Ppostgres-integration`, `-Pvault-integration` | 0 failures in retained surefire reports |
| Real engines (subset) | `ZooKeeperFrontDoorsTest`, `KafkaFrontDoorsTest`, `IcebergFrontDoorsTest`, `RealFlinkTest`, `RealSparkTest`, `OpenApiDraftIcebergTest`, `A2aAgentFrontDoorsTest`, `RealSparkConnectServerTest` | All executed tests passed after plugin.classpath fix |
| Knox bundle | `aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar` SHA-256 `0726C4CE71D23856F8FE9A58BE2C80E7FC7E4AE740ED70E8ECC8CBFB2617B780` | Matches updated H10 row |
| Hooks | `test_hooks.py`, `e2e_gateway.py` | 12/12 and 7/7 checks passed |
| Helm H11 | lint, kubeconform 1.33, deny templates, `charts/aegis/ci/start_rendered.py` | PASS |
| Token exchange S21 | `keycloak_e2e.py` | all checks passed |
| Docker gateway | Image `aegis-gateway:2.0.0-SNAPSHOT`, `/readyz` smoke | PASS |

## Boundaries (unchanged)

- **S14:** `PostgresStateStoreIT` on this date used loopback PostgreSQL **17.6**, not Amazon RDS **18.3**
  ([POSTGRES-RDS-VALIDATION.md](POSTGRES-RDS-VALIDATION.md) remains the RDS record).
- **S18:** Vault integration used **HashiCorp Vault 1.17.6** dev on loopback; API matches OpenBao KV v2
  ([SECRETS-OPENBAO.md](SECRETS-OPENBAO.md) documents OpenBao 2.7.1).
- **H03:** Knox cold-install transcript is still verified-historical.
- **RealIcebergRestCatalogTest:** skipped without `AEGIS_ICEBERG_REST_URL`; `IcebergFrontDoorsTest` covers the catalog.

## Fixes applied with this validation

- `BoundedHttp` 3xx handling ([DEFECT-LOG.md](../DEFECT-LOG.md) D30).
- `run-example.ps1` / `run-example.sh` build Knox typed-source `plugin.classpath` (D31).
- `.gitignore` excludes local Helm render YAML from RAT.
- Documentation: H10 bundle hash, coverage side-by-side table, this file.
