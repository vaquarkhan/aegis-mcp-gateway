<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# `secrets.source: vault` against a real OpenBao server

## Scope and classification

On 2026-10-03 the working tree reportedly ran `VaultKvSecretProviderIT`
(`aegis-config/src/test/java/.../secrets/VaultKvSecretProviderIT.java`) against a real OpenBao 2.7.1
server. OpenBao serves the same KV version 2 HTTP API as HashiCorp Vault; the provider uses only that
API (`GET /v1/<mount>/data/<path>` with `X-Vault-Token`).

The run is `recorded-unattested`. This document retains the command and reported observations, but no
immutable raw server transcript, Surefire output, commit/tree manifest, or content-addressed result
bundle. The binary hash authenticates the recorded archive only, not the test outcome.

## Server

| Item | Value |
| --- | --- |
| Binary | `openbao_2.7.1_windows_amd64.zip` from the OpenBao GitHub release v2.7.1 |
| SHA-256 | `e3b3335918a78cc730bb07014c4191c80fe5eb376f3f1a708185acf8bc0109ac`, equal to the line in the release's `checksums.txt` |
| Mode | `bao server -dev` on `127.0.0.1:18200`, random root token kept outside the repository |
| KV mount | `secret/`, `options.version: 2` (read from `GET /v1/sys/mounts`) |

`-dev` keeps data in memory and starts unsealed. It is a test server, not a deployment example.

## Command

```powershell
$env:AEGIS_VAULT_ADDR = "http://127.0.0.1:18200"
$env:AEGIS_VAULT_TOKEN = "<dev root token>"
C:\tools\apache-maven-3.9.11\bin\mvn.cmd -B -Pvault-integration -pl aegis-config surefire:test `
  "-Dtest=VaultKvSecretProviderIT" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Reported result: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`. Without the two variables the
test reports skipped, never passed.

## What the run reportedly showed

The admin token was used only to set up and clean up. The gateway received a token minted for one ACL
policy, `read` on `secret/data/<random path>`.

| Check | Reported observation |
| --- | --- |
| Gateway built with `tokenFile` (limited token) | `REST_TOKEN` and `AEGIS_SIGNING_KEY` resolved from the secret; an absent name resolved to nothing |
| Gateway built with `tokenEnv` | same values; the token came from the process environment, not from the provider |
| Limited token, another path | gateway did not start: `HTTP 403` |
| Readable token, absent path | gateway did not start: `HTTP 404` |
| Unknown token | gateway did not start: `HTTP 403`, and the message did not contain the token |
| After the run | `LIST secret/metadata` had no keys; ACL policies were `default,root` only |

## Supplemental recorded run (2026-10-05)

The same `VaultKvSecretProviderIT` suite was reported passed against **HashiCorp Vault 1.17.6** in
Docker dev mode on `127.0.0.1:18200` (`hashicorp/vault:1.17.6`, KV v2 mount `secret/`). This result is
also `recorded-unattested`; no immutable raw transcript was retained. It records behavior against the
Vault-branded server and does not replace the separately dated OpenBao result.

## Evidence boundary

The records cover the KV v2 read path, ACL refusal, and token handling over loopback HTTP. TLS to the
server (`caFile`) was exercised only for refusal of an invalid bundle in `SecretProvidersTest`, not
against a TLS listener. Vault namespaces (`X-Vault-Namespace`), leases, renewals, and secret rotation
without restart were not exercised. Current behavior requires a new candidate-bound run with retained
raw artifacts.
