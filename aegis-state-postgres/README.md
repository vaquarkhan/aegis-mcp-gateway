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
# aegis-state-postgres

Shared PostgreSQL state provider (`state.type: postgres`) for multi-replica approvals, receipts, and
rate/budget windows. Required when the Helm chart sets `replicaCount > 1` (`sharedState: true`).

## What it does

Governance state is what some controls must remember between requests. The engine reaches it only
through the `StateStore` interface in `aegis-engine`: `ApprovalService` keeps pending requests,
decisions and spent approval tokens; `VrpService` keeps spent dry-run receipts; `RateLimiter` keeps the
per-minute rate windows and the hourly budget.

With `memory` or `derby-local`, each gateway process has its own copy. Once two or more processes serve
the same users, a token spent on replica A could be replayed on replica B, a request raised on A never
reaches an approver whose call lands on B, and a per-minute limit becomes a limit per replica. This
provider keeps that state in one PostgreSQL database used by every replica, and uses PostgreSQL
transactions and unique keys so exactly one replica wins each single-use marker. Approval tokens and
receipts are HMAC-signed and self-contained (`SignedTokens`), so replicas must share the approval signing
key; the store only records that a token was spent. The full background is in
[What it means and why you need it](../docs/POSTGRES-STATE.md#what-it-means-and-why-you-need-it).

## How it works end to end

1. **Selection.** `GatewayFactory` (aegis-config) reads `state.type` (default `memory`) and asks
   `ServiceLoader` for a `StateStoreFactory` whose `type()` matches. This module registers
   `PostgresStateStoreFactory` in
   `src/main/resources/META-INF/services/io.github.vaquarkhan.aegis.config.spi.StateStoreFactory`;
   its `type()` is `postgres`. An unknown type stops startup.
2. **Settings.** `PostgresStateStoreFactory` reads these keys, rejects any other (`settings.finish()`)
   and reads credential values from the gateway environment at startup, never from YAML:

   | Key | Default | Checked in code |
   | --- | --- | --- |
   | `jdbcUrl` | required | must start with `jdbc:postgresql://` |
   | `usernameEnv` | required | name of an environment variable that must be set and not blank |
   | `passwordEnv` | required | same as `usernameEnv` |
   | `namespace` | required | `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`: 1 to 128 characters, starting with a letter or digit |
   | `connectTimeoutSeconds` | 10 | 1 to 120; passed to pgJDBC as `connectTimeout` |
3. **Startup.** `PostgresStateStore` loads `org.postgresql.Driver`, then in one transaction runs
   `CREATE TABLE IF NOT EXISTS` for both tables and `CREATE INDEX IF NOT EXISTS aegis_state_expiry_idx`
   on `aegis_state (namespace, expires_at_ms)`. This DDL runs on every start, so the database must be
   reachable; the role needs `CREATE` the first time. The class Javadoc says that once the tables exist
   the role needs only `SELECT`, `INSERT`, `UPDATE` and `DELETE`; no test here starts with such a role.

   ```text
   aegis_state  (namespace, state_key, state_value TEXT, expires_at_ms BIGINT)  PK (namespace, state_key)
   aegis_window (namespace, window_key, window_start_ms, used_value)            PK (namespace, window_key)
   namespace VARCHAR(128); keys VARCHAR(2048); times and counters BIGINT (epoch ms)
   ```
4. **Transactions.** There is no connection pool, local cache or JVM-wide lock. Each operation opens a
   new JDBC connection (`ApplicationName=aegis-state-postgres`) with auto-commit off, runs, commits and
   closes it; on `SQLException` or `RuntimeException` it rolls back. Every statement is parameterized
   and filtered by `namespace`. Expiry is epoch milliseconds; `NULL` means no expiry.
5. **Operations.**
   - `putIfAbsent`: `INSERT ... ON CONFLICT (namespace, state_key) DO UPDATE ... WHERE
     existing.expires_at_ms IS NOT NULL AND existing.expires_at_ms <= now RETURNING state_key`. A row
     comes back only if the key was absent or expired, so one caller on one replica gets `true`.
   - `put`: unconditional `INSERT ... ON CONFLICT ... DO UPDATE`.
   - `get`: `SELECT ... FOR UPDATE`; an expired row is deleted and treated as absent.
   - `take`: `DELETE ... RETURNING`; only the caller whose delete removed the row gets the value, and
     only if it was live.
   - `scan`: deletes every expired row in the namespace, then `SELECT ... LIKE` with `\`, `%` and `_`
     escaped, `ORDER BY state_key LIMIT n`. A limit of 0 or less returns an empty map without a query.
   - `tryAcquire`: negative cost or capacity, `cost > capacity`, or a zero, negative or overflowing
     window returns false without a query. The window start is `floorDiv(now, width) * width`. One
     `INSERT ... SELECT ... ON CONFLICT ... DO UPDATE ... WHERE ... RETURNING used_value` starts a new
     window, or adds the cost when it still fits, or returns no row and changes nothing.
6. **Expiry.** "Now" is each gateway's own `BuildContext` clock, not the database clock. Expired state
   rows are removed when touched and by every `scan`; `aegis_window` rows are reused, never deleted.
7. **Two gateway processes.** Gateways share state only when they use the same database and the same
   `namespace`. `close()` only marks the store closed; the rows stay for other or restarted replicas.

```yaml
state:
  type: postgres
  settings:
    jdbcUrl: jdbc:postgresql://postgres.internal.example:5432/aegis?sslmode=verify-full
    usernameEnv: AEGIS_STATE_DB_USER
    passwordEnv: AEGIS_STATE_DB_PASSWORD
    namespace: production-gateway
```

```text
replica A                               PostgreSQL (one namespace)             replica B
call needs approval -> put approval-req:<id>, approval-open:<...>
                                        approver lists pending <- scan approval-req:
                                        one decision wins      <- putIfAbsent approval-decided:<id>
approved call       -> putIfAbsent approval-used:<nonce> = true, call runs once
replay              -> approval-used:<nonce> present -> denied (same on either replica)
rate:tool:<caller>:<tool> -> one aegis_window row counts calls from A and B
```

## Failure behaviour

- **Bad settings:** a wrong URL prefix, an invalid namespace, a timeout outside 1 to 120, an unset
  credential variable ("names X which is not set") or an unknown key is a `ConfigException` at load.
- **Database unreachable at startup** (or the role cannot run the startup DDL): table creation fails in
  the constructor, `GatewayFactory` reports "could not open state provider 'postgres'" and the gateway
  does not start.
- **Database down or failing during a request:** the transaction rolls back and the store throws
  `IllegalStateException("PostgreSQL governance state operation failed")`. A connection attempt waits up
  to `connectTimeoutSeconds`. The provider does not retry and sets no socket or statement timeout itself.
- **The engine fails closed.** `GovernanceEngine.call` turns a `RuntimeException` from any control into
  a `POLICY_DENIED` denial ("internal governance error"); the admin approval API (`AdminApi` in
  aegis-mcp) answers HTTP 500. A call is never allowed because state was unavailable.
- **No distributed transaction.** Each `StateStore` call commits on its own. An outage between engine
  steps may burn a one-time approval or receipt; see
  [Schema and transaction model](../docs/POSTGRES-STATE.md#schema-and-transaction-model).
- **Closed store:** any call after `close()` throws `IllegalStateException("PostgreSQL StateStore is closed")`.

## Limits

- **Circuit breakers are per replica.** `CircuitBreakers` (aegis-engine) keeps its state in memory.
- **TOFU catalog pins are not shared.** They use `catalog.pinFile` (`GatewayFactory`, `CatalogIntegrity`),
  not `StateStore`. Use reviewed `REQUIRE_PINS` for shared deployments.
- **Namespace is the boundary.** Two unrelated gateways on the same namespace merge their approvals and
  counters; different namespaces in one database are isolated.
- **Clocks.** Expiry and window boundaries use each replica's clock, so replicas need synchronized time.
- **Connections and schema.** One new connection per operation (a governed call makes several); the
  schema uses only `CREATE ... IF NOT EXISTS`, with no migration or version table.
- **TLS** is whatever `jdbcUrl` says; the provider does not require `sslmode=verify-full`.

## Tests

The default build runs no test here: Surefire's default pattern skips `*IT.java` and `pom.xml` sets
coverage gates of 0.00. The `postgres-integration` profile adds `**/*IT.java`, gates 0.78 line, 0.58 branch.

`PostgresStateStoreIT` needs `AEGIS_POSTGRES_URL`, `AEGIS_POSTGRES_USER` and `AEGIS_POSTGRES_PASSWORD`
(otherwise skipped, never passed); each test uses an `it-<UUID>` namespace and deletes only its rows.

- `twoIndependentStoresShareSingleUseNonceAndRateWindow`: 24 threads over two store instances; one
  nonce winner, exactly 10 admitted at capacity 10, `take` single-use across stores, next window fresh.
- `persistsAcrossConnectionsExpiresAndDoesNotChargeRejectedCost`: state survives reopening, expired
  entries disappear, a refused cost is not charged.
- `scanEscapesPrefixesAndInvalidRateRequestsDoNotTouchRealState`: `%` and `_` in a prefix are literal,
  scan limits hold, invalid rate requests create no counter.
- `factoryUsesEnvironmentNamesRatherThanCredentialValues`: credentials come from environment names.

`TwoGatewayProcessesPostgresIT` (aegis-gateway) starts two gateway JVMs on one namespace: an approval
requested on A is decided on B, the approved destructive call runs once, the replayed token and receipt
are refused on A, and one rate window covers both. Commands are in
[Real integration validation](../docs/POSTGRES-STATE.md#real-integration-validation).

## Developer instructions

```powershell
mvn -B -pl aegis-state-postgres -am test
# Opt-in real PostgreSQL (see docs/evidence/POSTGRES-*.md for env vars)
mvn -B -pl aegis-state-postgres -am test -Ppostgres-integration
```

1. Approvals and rate windows must be one namespace across gateway JVMs.
2. Credentials come from environment / secret providers; never hard-code DSNs in tests committed with
   secrets.
3. The Helm chart refuses `replicaCount > 1` unless `sharedState: true`. It does not inspect
   `config.state`, so set the flag only when the config uses this store.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-state-postgres)
- [PostgreSQL shared state](../docs/POSTGRES-STATE.md)
- [Configuration](../docs/CONFIGURATION.md#state)
- [Helm chart](../charts/aegis/README.md)
- [PostgreSQL evidence](../docs/evidence/POSTGRES-RDS-VALIDATION.md)
- [AGENTS.md](../AGENTS.md)
