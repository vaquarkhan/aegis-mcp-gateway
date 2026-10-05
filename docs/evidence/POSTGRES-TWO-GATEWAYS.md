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
# Two gateway processes sharing one PostgreSQL namespace

## Scope

On 2026-10-03 the working tree ran `TwoGatewayProcessesPostgresIT`
(`aegis-gateway/src/test/java/.../TwoGatewayProcessesPostgresIT.java`) against
a real PostgreSQL 17.6 server on the validating workstation.

The test starts two own-host gateways as two separate JVMs with
`io.github.vaquarkhan.aegis.gateway.Main --config replica-<x>.json`, the same
way an operator runs two replicas. Both configurations name the same gateway,
signing key and `state.type: postgres` namespace. Each replica keeps its own
audit journal. Nothing is shared in memory: the only path between the two
processes is the PostgreSQL database.

The backend is a local JDK `HttpServer` that counts reads and deletes. It
stands for a product API; the subject under test is the shared governance
state, so the counter is the ground truth for "did the call reach the backend".

## Server

| Item | Value |
| --- | --- |
| Server | PostgreSQL 17.6, EDB Windows x64 binaries zip (`select version()`: `PostgreSQL 17.6 on x86_64-windows, compiled by msvc-19.44.35213, 64-bit`) |
| Zip SHA-256 | `D378882ABD001A186735ACD6F6BA716BCA6CCD192E800412D4FD15ED25376B3E` |
| Listen | `127.0.0.1:55432`, password authentication, database `aegis_state`, role `aegis` |
| Tables after the run | `aegis_state`, `aegis_window` (created by `aegis-state-postgres`) |
| Rows left in `it-two-replicas-*` namespaces | 0 (the test deletes its namespace afterwards) |

## Command

```powershell
$env:AEGIS_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:55432/aegis_state"
$env:AEGIS_POSTGRES_USER = "aegis"
$env:AEGIS_POSTGRES_PASSWORD = "<local password>"
C:\tools\apache-maven-3.9.11\bin\mvn.cmd -B -Ppostgres-integration -pl aegis-gateway -am install `
  "-Dtest=TwoGatewayProcessesPostgresIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Djacoco.skip=true"
```

Result: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
Without the three environment variables the test is reported as skipped,
never as passed.

## What the run shows

| Step | Replica | Observed |
| --- | --- | --- |
| Dry run of the `DESTRUCTIVE` `delete_item` | A | receipt and approval request issued; backend deletes = 0 |
| Approver approves the request | B | HTTP 200; B can only find the request through PostgreSQL |
| Operator collects the approval token | A | HTTP 200 with the token |
| Approved call with token and receipt | B | `ALLOWED`; backend deletes = 1 |
| Same token and receipt replayed | A | `APPROVAL_REQUIRED`, reason "already been used"; backend deletes still 1 |
| Four `list_items` calls alternating A, B, A, B with `perCallerPerToolPerMinute: 3` | A and B | `ALLOWED, ALLOWED, ALLOWED, RATE_LIMITED`; backend reads = 3 |
| `AuditChainVerifier` on both journals | A and B | both chains intact |

## Evidence boundary

This shows that approvals, single-use approval and receipt nonces and rate
windows are one shared state across two gateway processes on one PostgreSQL
server. It does not show PostgreSQL replication, failover, backup restore,
TLS to the database, or behavior under a network partition. Catalog pins and
circuit breakers stay per process, and each replica writes its own journal.
Re-run on the exact release candidate before a release claim.
