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
# PostgreSQL shared-state RDS validation

## Scope

On 2026-10-03, `PostgresStateStoreIT` ran against a dedicated Amazon RDS for
PostgreSQL 18.3 test instance. The test opened two independent
`PostgresStateStore` objects against the same database and executed real
PostgreSQL transactions. No container, mock driver, or in-memory database was
used.

## Test command

```bash
export AEGIS_POSTGRES_URL='jdbc:postgresql://<endpoint>:5432/<database>?sslmode=require'
export AEGIS_POSTGRES_USER='<user>'
export AEGIS_POSTGRES_PASSWORD='<password>'
mvn -B -Ppostgres-integration -pl aegis-state-postgres -am verify
```

Result: **BUILD SUCCESS**. The PostgreSQL module ran four integration tests
with zero failures and zero skips:

1. Two independent stores admitted one nonce winner and no more than the
   shared rate-window capacity.
2. Values persisted across connections; expired values disappeared; a rejected
   cost did not consume rate capacity.
3. Escaped prefix scanning, bounded scans, and invalid rate requests behaved
   correctly against PostgreSQL.
4. The factory resolved credential environment-variable names rather than
   placing credential values in configuration.

## Test environment

A dedicated, network-restricted test instance, holding only the `aegis_state`
and `aegis_window` test tables and generated namespace rows, was created for
this run and deleted after it. It held no production or customer data.

This run establishes state-store behavior only. A production deployment should
use encrypted storage, private network placement, least-privilege roles,
managed credential handling, a backup and recovery policy, and a repeat of this
test against the release candidate.

## Evidence boundary

This is current working-tree source behavior, not a release-candidate or
active-active deployment certification. It does not establish PostgreSQL
replication, failover, backup restoration, multi-region behavior, catalog-pin
sharing, circuit-breaker sharing, or operational security of a production RDS
deployment.
