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
# PostgreSQL shared-state validation

## Scope and classification

On 2026-10-08, `PostgresStateStoreIT` and `TwoGatewayProcessesPostgresIT` were reported run against a
short-lived, dedicated managed PostgreSQL test instance. The engine version was not retained,
so this record does not name one. The first suite opened independent
`PostgresStateStore` objects. The second started two gateway JVMs against the same database. No
container, mock driver, or in-memory database was used.

The result is historical `recorded-unattested` evidence. No immutable raw Maven/Surefire artifact,
commit/tree manifest, or content-addressed run bundle was retained. The temporary managed PostgreSQL instance was
deleted after the run, so this document must not be promoted to `verified-current` without a new,
candidate-bound run.

## Commands

The state-store module command was:

```bash
export AEGIS_POSTGRES_URL='jdbc:postgresql://<endpoint>:5432/<database>?sslmode=require'
export AEGIS_POSTGRES_USER='<user>'
export AEGIS_POSTGRES_PASSWORD='<password>'
mvn -B -Ppostgres-integration -pl aegis-state-postgres -am verify
```

That command produced the reported `PostgresStateStoreIT` result only. The gateway integration test
was selected separately from the gateway module:

```bash
export AEGIS_POSTGRES_URL='jdbc:postgresql://<endpoint>:5432/<database>?sslmode=require'
export AEGIS_POSTGRES_USER='<user>'
export AEGIS_POSTGRES_PASSWORD='<password>'
mvn -B -Ppostgres-integration -pl aegis-gateway -am install \
  -Dtest=TwoGatewayProcessesPostgresIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.skip=true
```

The placeholders are intentional. This evidence does not retain or disclose an AWS account, endpoint,
secret, public IP, or resource identifier.

## Reported result

`PostgresStateStoreIT` reported **5/5** integration tests passed, and
`TwoGatewayProcessesPostgresIT` reported **1/1** passed, with zero failures and zero skips. These are
reported outcomes, not candidate attestation. The run covered:

1. Two independent stores admitted one nonce winner and no more than the shared rate-window capacity.
2. Values persisted across connections; expired values disappeared; a rejected cost did not consume
   rate capacity.
3. Escaped prefix scanning, bounded scans, and invalid rate requests behaved correctly against
   PostgreSQL.
4. The factory resolved credential environment-variable names rather than placing credential values
   in configuration.
5. The signed serial high-water mark advanced from 9 to 10 and then refused serial 9 across independent
   PostgreSQL connections.
6. Two independent gateway JVMs shared approval and receipt single-use state and rate windows.

## Test environment

A dedicated, network-restricted test instance holding only generated test tables and namespace rows
was created for this run and deleted after it. It held no production or customer data. The connection
required PostgreSQL TLS through `sslmode=require`.

The run establishes only the reported state-store and two-process behavior. A production deployment
should use encrypted storage, private network placement, least-privilege roles, managed credential
handling, a backup and recovery policy, and a repeat of these tests against the release candidate.

## Evidence boundary

This record does not establish PostgreSQL replication, failover, backup restoration, multi-region
behavior, catalog input sharing, circuit-breaker sharing, or operational security of a production database deployment. Relevant source and tests have changed since the run, so current behavior requires a new
candidate-bound execution and retained evidence bundle.
