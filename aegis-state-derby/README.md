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
# aegis-state-derby

Durable local state store (`state.type: derby-local`) for single-process approvals, nonces, and rate
windows. Suitable for one gateway replica; not for shared multi-pod approvals.

## What it does

Some Aegis controls have to remember things between requests. The engine calls this governance state
and reaches it only through the `StateStore` interface in `aegis-engine` (`engine/spi/StateStore.java`):

| Caller (aegis-engine) | What it keeps | Operations used |
| --- | --- | --- |
| `ApprovalService` | pending requests (`approval-req:`), the open-request index (`approval-open:`), decisions (`approval-decided:`), spent approval tokens (`approval-used:`), at most 100 new requests per caller per hour (`approval-rate:`) | `put`, `get`, `putIfAbsent`, `take`, `scan`, `tryAcquire` |
| `VrpService` | spent dry-run receipts (`vrp-used:`) | `get`, `putIfAbsent` |
| `RateLimiter` | per-caller and per-caller-per-tool minute windows (`rate:caller:`, `rate:tool:`), the hourly budget (`budget:`) | `tryAcquire` |

The default `memory` store loses all of this when the process stops. After a restart, a spent approval
token or receipt that has not yet expired would no longer be marked as used, pending requests would
disappear from the approver's queue, and rate and budget counters would start again from zero.
`derby-local` writes the same state to an embedded Apache Derby database on local disk, so it survives
a restart of the gateway. No database server has to be installed.

## How it works end to end

1. **Selection.** `GatewayFactory` (aegis-config) reads the `state` section after every other section.
   `state.type` defaults to `memory`; any other value is matched against `StateStoreFactory.type()` of
   the providers found by `ServiceLoader`. This module registers `DerbyStateStoreFactory` in
   `src/main/resources/META-INF/services/io.github.vaquarkhan.aegis.config.spi.StateStoreFactory`, and
   its `type()` is `derby-local`. An unknown type stops startup. If an embedding host passes its own
   store through `BuildContext`, the `state` section is accepted but not used.
2. **Settings.** The only key is `state.settings.path` (required, no default). `DerbyStateStoreFactory`
   resolves it against the configuration directory and normalizes it; a path that leaves that
   directory is refused with "must stay under the configuration directory". Any other key under
   `state.settings` fails startup, because `root.finish()` rejects unknown keys in every child node.
   `state.maxEntries` is read but only the `memory` store uses it.
3. **Open.** `DerbyStateStore` creates the parent directories, points Derby's JVM-wide error log
   (`derby.stream.error.file`) at `<path>.derby.log` beside the database unless that property is
   already set, and opens `jdbc:derby:<path>;create=true` with auto-commit off. It keeps this one
   connection for the life of the gateway, and Derby's embedded database lock belongs to this JVM.
4. **Schema.** Two tables are created. On a restart Derby answers SQLState `X0Y32` ("already exists"),
   which is ignored:
   - `AEGIS_STATE (STATE_KEY VARCHAR(2048) PRIMARY KEY, STATE_VALUE VARCHAR(32672) NOT NULL, EXPIRES_AT BIGINT)`.
     Expiry is epoch milliseconds; `NULL` means the entry does not expire.
   - `AEGIS_WINDOW (WINDOW_KEY VARCHAR(2048) PRIMARY KEY, WINDOW_START BIGINT NOT NULL, USED_VALUE BIGINT NOT NULL)`.
5. **Operations.** Every public method is `synchronized` and runs as one transaction on the shared
   connection: it reads the row with `SELECT ... FOR UPDATE`, writes, then commits. Any `SQLException`
   rolls the transaction back. All SQL uses prepared statements with parameters.
   - `putIfAbsent`: a live row means return false. An expired row is deleted, then the new row is
     inserted and the call returns true.
   - `put`: insert, or `UPDATE` the value and expiry of the existing row.
   - `get`: return a live value. An expired row is deleted and treated as absent.
   - `take`: delete the row and return its value only if it was still live.
   - `scan`: `LIKE` on the key with `\`, `%` and `_` escaped, ordered by key, stopping at `limit`.
     Expired rows met on the way are deleted. A limit of 0 or less returns an empty map.
   - `tryAcquire`: negative cost or capacity, or a zero or negative window, returns false without
     touching the database. The window start is `now` rounded down to a multiple of the window width,
     and a row from an older window counts as 0 used. If `cost > capacity - used` it returns false and
     writes nothing; otherwise it inserts or updates the row.
6. **Expiry.** Time comes from the `BuildContext` clock; `tryAcquire` uses the `now` its caller passes.
   There is no background sweeper. Expired `AEGIS_STATE` rows are removed only when `putIfAbsent`,
   `get`, `take` or `scan` touches them. `AEGIS_WINDOW` rows are never deleted; a key's row is reused
   for its next window.
7. **Shutdown.** `Gateway.close()` calls `DerbyStateStore.close()`, which closes the connection and
   then shuts the database down with `;shutdown=true`. Derby reports a clean shutdown with SQLState
   `08006`; any other result is thrown, so a lock that may still be held is reported.
8. **Restart.** The next start on the same path reopens the same files, skips table creation and sees
   every row that has not expired: pending approvals, spent approval and receipt markers, and the
   current rate and budget windows. Spent approval markers are kept until the token expiry plus five
   minutes (`ApprovalService.consume`, `VrpService.consume`).

```yaml
state:
  type: derby-local
  settings:
    path: state/aegis   # resolved against the configuration directory
```

```text
aegis.yaml (state.type: derby-local)
  -> GatewayFactory reads state after the other sections
  -> ServiceLoader finds DerbyStateStoreFactory (type() == "derby-local")
  -> path resolved and checked under the configuration directory
  -> DerbyStateStore opens jdbc:derby:<path>;create=true (Derby lock taken)
  -> CREATE TABLE AEGIS_STATE, AEGIS_WINDOW (X0Y32 ignored on restart)
tool call -> GovernanceEngine
  -> RateLimiter.tryAcquire, ApprovalService, VrpService
  -> synchronized DerbyStateStore method -> one transaction -> commit or rollback
Gateway.close() -> DerbyStateStore.close() -> ;shutdown=true, expects SQLState 08006
```

## Failure behaviour

- **Cannot open the database** (Derby not on the classpath, parent directory not creatable, or Derby
  refuses the connection): the constructor throws, `GatewayFactory` wraps it in a `ConfigException`
  ("could not open state provider 'derby-local'") and the gateway does not start.
- **Lock held by another process:** Derby's embedded lock stops a second JVM from opening the same
  database, so that process fails to start instead of creating a second, independent store.
  `DerbyStateStoreTest.aSecondProcessCannotOpenTheSameDatabaseWhileTheFirstHoldsIt` starts a real
  second JVM to prove it, and shows the same path opens again once the first process has closed it.
- **Bad path:** a missing `path` or one that escapes the configuration directory is a `ConfigException`
  at load time.
- **Error during an operation** (disk full, I/O error, a key longer than 2048 or a value longer than
  32672 characters): the transaction rolls back and the store throws
  `IllegalStateException("Derby state operation failed: <SQLState>")`. Nothing partial is committed.
- **The engine fails closed.** `GovernanceEngine.call` turns a `RuntimeException` from any control into
  a `POLICY_DENIED` denial ("internal governance error"), and the admin approval API (`AdminApi` in
  aegis-mcp) answers HTTP 500. A call is never allowed because state was unavailable.
- **Configuration error elsewhere:** if `root.finish()`, the audit journal or a later startup step
  fails, `GatewayFactory` closes the store again, so a bad configuration does not leave the lock held.
- **Unclean shutdown:** if Derby does not report `08006`, `close()` throws and `Gateway.close()`
  rethrows it as `IllegalStateException("could not close governance state store")`.

## Limits

- **One process only.** Do not point two gateways, or two pods sharing a volume, at the same path; the
  second should fail to start, and separate paths would give each process its own approvals and
  counters. For more than one replica use `aegis-state-postgres`. The Helm chart refuses
  `replicaCount > 1` unless `sharedState: true` (`charts/aegis/templates/_helpers.tpl`).
- **No entry bound.** `state.maxEntries` applies to `memory` only. Expired rows that are never touched
  again, and one `AEGIS_WINDOW` row per rate key, stay on disk.
- **Serialized access.** All state operations in the process share one synchronized connection.
- **Not stored here.** Circuit breakers (`CircuitBreakers`, in memory) and trust-on-first-use catalog
  pins (`catalog.pinFile`, read in `GatewayFactory`) do not go through `StateStore`.
- **Knox.** The Knox `knox-bundle` jar includes `postgres` only; add this jar and Apache Derby to Knox
  `ext/` to use `derby-local` there (`docs/CONFIGURATION.md`).
- **Derby version.** The root `pom.xml` pins `derby.version` 10.16.1.1, the line that runs on Java 17.

## Tests

`DerbyStateStoreTest` (this module) runs in the default build against a temporary directory:

- `factoryCreatesOnlyLocalPathsUnderTheConfigurationDirectory`: `type()` is `derby-local`, a relative
  path opens, and `../outside` is refused.
- `stateSurvivesRestartAndExpiredStateDoesNot`: a live entry is readable after close and reopen, an
  expired one is not, and `scan` returns only live entries.
- `nonceHasExactlyOneWinnerUnderConcurrency`: 16 threads race `putIfAbsent`; exactly one wins, and
  `take` returns the value once.
- `aSecondProcessCannotOpenTheSameDatabaseWhileTheFirstHoldsIt`: a second JVM (`DerbyLockProbe`) is
  refused while the first process holds the database, the first keeps writing, and after it closes
  the second JVM opens the same path.
- `rejectedRateCostIsNotChargedAndWindowRollsOver`: `put` replaces, a zero scan limit returns nothing,
  a negative cost is refused, a refused cost is not charged, and the next window starts fresh.

`GatewayHostTest.derbyLocalStateProviderIsDiscoveredByTheOwnServer` (aegis-gateway) builds the own-host
gateway with `derby-local`, serves a call, closes it, and builds a second gateway on the same path. The
module `pom.xml` sets coverage gates of 0.82 (line) and 0.66 (branch) and points Derby's home and log
under `target/derby` for tests.

## Developer instructions

```powershell
mvn -B -pl aegis-state-derby -am test
```

1. When the store is full, refuse new approvals rather than silently dropping older entries.
2. Shutdown must report a stale lock clearly; do not leave the database half-open.
3. For multi-replica deployments use `aegis-state-postgres` instead.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-state-derby)
- [Configuration](../docs/CONFIGURATION.md)
- [Why shared state matters](../docs/POSTGRES-STATE.md#what-it-means-and-why-you-need-it)
- [AGENTS.md](../AGENTS.md)
