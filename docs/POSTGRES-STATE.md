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
# PostgreSQL shared governance state

`aegis-state-postgres` is the shared-state provider for Aegis deployments with more than one
replica. It is selected with `state.type: postgres`. It uses PostgreSQL transactions and database
constraints for the state operations that cannot be safely split by gateway replica:

- approval-request and approval-token single-use markers;
- destructive dry-run receipt single-use markers;
- pending approval records and deduplication indexes;
- per-caller/tool rate windows and budget counters.

It is a real PostgreSQL JDBC provider. It does not use a cache, mock database, filesystem lock, or
JVM-wide synchronization to provide cross-replica correctness.

## Why PostgreSQL, not Derby

`derby-local` remains the no-server durable option for **one** Aegis process. Derby embedded locking
prevents a second writable process from sharing it. It is deliberately not an active-active store.

PostgreSQL provides the required multi-process primitives: unique primary keys, `INSERT ... ON
CONFLICT`, row locks, transactions and `DELETE ... RETURNING`. Aegis uses those primitives so one
replica wins a nonce/take race and a rejected rate acquisition does not consume capacity.

## Configuration

The provider is bundled in the own-host Aegis JAR and in the Knox bundle. Credentials are referenced
by environment-variable name; do not put their values in YAML.

```yaml
state:
  type: postgres
  settings:
    jdbcUrl: jdbc:postgresql://postgres.internal.example:5432/aegis?sslmode=verify-full
    usernameEnv: AEGIS_STATE_DB_USER
    passwordEnv: AEGIS_STATE_DB_PASSWORD
    namespace: production-gateway
    connectTimeoutSeconds: 10
```

Settings:

| Setting | Required | Meaning |
| --- | --- | --- |
| `jdbcUrl` | yes | PostgreSQL JDBC URL. Use TLS certificate verification in production (`sslmode=verify-full`). |
| `usernameEnv` | yes | Environment-variable name holding the database role name. |
| `passwordEnv` | yes | Environment-variable name holding the role password. |
| `namespace` | yes | 1 to 128 letters, digits, `.`, `_`, or `-`. Gateways share state only when database **and** namespace match. |
| `connectTimeoutSeconds` | no | 1 to 120 seconds; default 10. |

The database role must be configured outside Aegis. Use a dedicated database or schema and a
least-privilege role. The provider creates its two idempotent tables on first use, so the migration
role needs `CREATE` initially; a production deployment may pre-create the tables and grant the runtime
role only `SELECT`, `INSERT`, `UPDATE`, and `DELETE` on them.

## Schema and transaction model

The provider creates two namespaced tables:

```text
aegis_state(namespace, state_key, state_value, expires_at_ms)
aegis_window(namespace, window_key, window_start_ms, used_value)
```

Both have `(namespace, key)` primary keys. The provider opens one short transaction per `StateStore`
operation:

- **put-if-absent:** conditional `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE expired`, returning a
  row only to the winner;
- **take:** `DELETE ... RETURNING`, so only one caller receives a live nonce;
- **rate/budget:** conditional upsert that advances a fixed window or adds cost only when capacity
  remains; rejected requests do not update `used_value`;
- **scan:** removes expired rows for the namespace then returns sorted prefix matches.

The API does not turn independent engine steps into one distributed transaction. For example, the
engine consumes an approval/receipt before backend execution and writes audit intent before execution.
A database outage therefore fails closed and may burn a one-time artifact; retrying it would weaken
single-use semantics.

## Own-host deployment

The stock `aegis-gateway-...-all.jar` carries `aegis-state-postgres`, pgJDBC and its `ServiceLoader`
registration. Set `AEGIS_STATE_DB_USER` and `AEGIS_STATE_DB_PASSWORD` in the gateway process
environment, then run the usual own-host command.

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config aegis.yaml
```

## Apache Knox deployment

The `aegis-knox3-extension` shaded bundle includes the PostgreSQL provider and pgJDBC without
including any Apache Knox implementation classes. Set the same environment variables on the Knox
gateway process. The topology continues to authenticate/authorize at the edge; PostgreSQL changes only
the shared Aegis governance state behind that front door.

## What PostgreSQL does not solve

- `CircuitBreakers` intentionally remain per replica: each gateway protects its own current backend view.
- TOFU catalog pins currently use `catalog.pinFile`, not `StateStore`. Use reviewed `REQUIRE_PINS` for
  shared deployments until catalog-pin storage is deliberately made shared.
- PostgreSQL cannot substitute for network segmentation, a read-only backend credential, TLS, or the
  Aegis approval/egress/policy controls.

## Real integration validation

`PostgresStateStoreIT` uses two independent provider instances against one real PostgreSQL service. It
checks exactly-one nonce winner, globally single-use `take`, cross-instance fixed-window capacity,
rejected-cost non-charging, expiry/reopen persistence, and factory credential resolution from environment
names. It runs only in the integration profile:

```bash
export AEGIS_POSTGRES_URL='jdbc:postgresql://127.0.0.1:55432/postgres?sslmode=disable'
export AEGIS_POSTGRES_USER='aegis_state'
export AEGIS_POSTGRES_PASSWORD='local-development-password'
mvn -B -Ppostgres-integration -pl aegis-state-postgres -am test
```

`sslmode=disable` is only for an isolated loopback development server. It is not a production example.
If the variables are absent, the integration test is skipped and must never be reported as passed.

`TwoGatewayProcessesPostgresIT` in `aegis-gateway` goes one level up: it starts two gateway JVMs with
`Main --config`, both pointing at one namespace, and drives them over HTTP. An approval requested on one
replica is decided on the other, the approved delete runs once, the replay on the first replica is
refused, and one rate window covers both. Run it with the same variables:

```bash
mvn -B -Ppostgres-integration -pl aegis-gateway -am verify \
  -Dtest=TwoGatewayProcessesPostgresIT -Dsurefire.failIfNoSpecifiedTests=false
```

Retained runs: [RDS PostgreSQL 18.3, provider level](evidence/POSTGRES-RDS-VALIDATION.md) and
[PostgreSQL 17.6, two gateway processes](evidence/POSTGRES-TWO-GATEWAYS.md).
