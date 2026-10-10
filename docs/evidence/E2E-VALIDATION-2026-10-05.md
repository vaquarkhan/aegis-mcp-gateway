<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# End-to-end claims validation (2026-10-05)

This record is the 2026-10-05 end-to-end run. It supplements [CLAIMS-MATRIX.md](CLAIMS-MATRIX.md) and [VALIDATION.md](../VALIDATION.md). The release-candidate repeat is on the [roadmap](../ROADMAP.md#next-release-candidate-evidence).

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

| Area | What ran | Outcome |
| --- | --- | --- |
| Default reactor + opt-in ITs | `release`, `postgres-integration`, and `vault-integration` profiles | Zero failures were reported in the Surefire reports from that day. |
| Real engines (subset) | `ZooKeeperFrontDoorsTest`, `KafkaFrontDoorsTest`, `IcebergFrontDoorsTest`, `RealFlinkTest`, `RealSparkTest`, `OpenApiDraftIcebergTest`, `A2aAgentFrontDoorsTest`, and `RealSparkConnectServerTest` | The executed tests passed. |
| Knox bundle | `aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar` SHA-256 `0726C4CE71D23856F8FE9A58BE2C80E7FC7E4AE740ED70E8ECC8CBFB2617B780` | 2026-10-05 bundle, distinct from the 2026-10-04 bundle in [RELEASE-ASSURANCE.md](RELEASE-ASSURANCE.md). |
| Hooks | `test_hooks.py` and `e2e_gateway.py` | 12/12 and 7/7 checks passed. |
| Helm H11 | lint, kubeconform 1.33, deny templates, and `charts/aegis/ci/start_rendered.py` | PASS. |
| Token exchange S21 | See [TOKEN-EXCHANGE-KEYCLOAK.md](TOKEN-EXCHANGE-KEYCLOAK.md). | All checks passed. |
| Docker gateway | Image `aegis-gateway:2.0.0-SNAPSHOT`, `/readyz` smoke | PASS. |

The different Knox bundle hashes in this file and [RELEASE-ASSURANCE.md](RELEASE-ASSURANCE.md) identify
separate dated build artifacts. They must not be treated as hashes of one artifact.

## Notes from this run

- **S14:** `PostgresStateStoreIT` on this date used loopback PostgreSQL **17.6**. The later managed PostgreSQL run is in [POSTGRES-RDS-VALIDATION.md](POSTGRES-RDS-VALIDATION.md).
- **S18:** Vault integration used **HashiCorp Vault 1.17.6** dev on loopback. The OpenBao KV v2 run is in [SECRETS-OPENBAO.md](SECRETS-OPENBAO.md).
- **H03:** The Knox 3.0.0 cold-install transcript is in [knox-3.0.0-cold-install/README.md](knox-3.0.0-cold-install/README.md).
- **RealIcebergRestCatalogTest:** `IcebergFrontDoorsTest` covered the catalog in this run.
- The release-candidate repeat of this set is on the [roadmap](../ROADMAP.md#next-release-candidate-evidence).
