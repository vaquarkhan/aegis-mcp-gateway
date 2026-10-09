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
# aegis-source-jdbc

Typed JDBC tool source (`type: jdbc`). Exposes governed metadata and SQL tools; the operator supplies
the JDBC driver. Classification and approval still come from configuration, not from this module.

## What it does

The module turns one JDBC database into a small set of MCP tools: server information, catalog, schema
and table listings, a table description, one read-only query tool and, only when the operator turns
writes on, one statement tool. It uses `java.sql` only and ships no driver. Metadata tools pass the
caller's names to `DatabaseMetaData` methods as parameters. Every result is capped by row count and by
the length of each value. Whether a call may run at all is decided by the engine, not here.

## How it works end to end

1. **Discovery.** `src/main/resources/META-INF/services/io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory`
   registers `JdbcToolSourceFactory`, whose `type()` returns `jdbc`. For a source with `type: jdbc`,
   `GatewayFactory` finds the factory through `ServiceLoader` and calls `create(id, settings, BuildContext)`.
2. **Settings.** The factory reads the keys below. `GatewayFactory` calls `ConfigNode.finish()`, so any
   other key under `settings:` fails startup.

   | Key | Default | Rule |
   | --- | --- | --- |
   | `url` | required | Must start with `jdbc:`; the rejected value is not echoed. |
   | `driverClass` | none | Loaded with `Class.forName` at startup; a missing class fails startup. Without it, JDBC 4 discovery applies. |
   | `userEnv`, `passwordEnv` | none | Names of environment variables, looked up through `BuildContext.env()`. A named variable that is unset or empty fails startup. |
   | `maxRows` | `200` | 1 to 10000. |
   | `maxCellChars` | `4096` | 16 to 1000000. |
   | `loginTimeoutSeconds` | `15` | 1 to 600; passed to `DriverManager.setLoginTimeout`. |
   | `allowWrites` | `false` | `true` also offers `jdbc_execute_statement`. |

3. **Tools.** `JdbcToolSource.discover()` returns six tools and adds `jdbc_execute_statement` only when
   `allowWrites` is true. Every input schema sets `additionalProperties: false`; names are limited to
   256 characters and `sql` to 8192. Class, resources and dry-run mode come from the source's `tools:`
   section in the operator's configuration.
4. **Engine first.** The engine runs its controls and calls `ToolExecutor.execute` only when they pass.
   `egressTargets` returns an empty list: the driver opens its own socket, and the target is the
   configured `url`, not an argument.
5. **Connection.** Each call opens a new connection with `DriverManager.getConnection(url, properties)`.
   `user` and `password` travel as connection properties, never in the URL. The connection is opened
   before the tool logic below runs and is closed by try-with-resources when the call ends.
6. **Arguments, without string concatenation.**
   - `jdbc_list_tables` passes `catalog`, `schema` and `namePattern` (default `%`) to
     `getTables(..., {"TABLE", "VIEW"})`. `jdbc_describe_table` passes `catalog`, `schema` and `table` to
     `getColumns` and `getPrimaryKeys`.
   - `jdbc_run_query` first calls `SqlReadOnlyHook.firstProblem(sql, 8192)`, the classifier the
     `sql-readonly` hook uses. It strips comments and quoted text, then requires exactly one statement,
     a leading `SELECT`, `WITH`, `SHOW`, `EXPLAIN`, `DESCRIBE`, `DESC`, `VALUES` or `TABLE`, and no writing
     keyword anywhere. A refusal is a tool error and no SQL is sent. Otherwise it calls
     `Connection.setReadOnly(true)` and `Statement.executeQuery(sql)`.
   - `jdbc_execute_statement` calls `SqlReadOnlyHook.singleStatementProblem(sql, 8192)`, so one call runs
     one statement: a `;` inside a literal or comment is data, and a second statement is refused even
     with a trailing `;`. Then it calls `Statement.executeUpdate(sql)`.
   - The `sql` value is the whole statement the caller sent; the source never adds other arguments to it.
7. **Statement limits.** `setMaxRows(maxRows + 1)`, so truncation can be detected, and `setQueryTimeout`
   in seconds from `ExecutionContext.timeoutMillis()` (at least 1). A driver that does not support
   either is still bounded by the reader loop and by the gateway's own timeout.
8. **Results.** List tools and `jdbc_run_query` return structured JSON
   `{<key>: [...], "rowCount": n, "truncated": bool}` with key `catalogs`, `schemas`, `tables` or `rows`,
   plus a `note` when reading stopped at `maxRows`. Booleans and integer types stay JSON values; any
   other value becomes a string cut at `maxCellChars` with `...(truncated)` appended.
   `jdbc_describe_table` returns `table`, `columns` (name, type, size, nullable; at most `maxRows`) and
   `primaryKey`. `jdbc_server_info` returns the sanitised `JdbcSettings.safeUrl()`, product and driver
   names and versions, and `readOnlyConnection`. `jdbc_execute_statement` returns `updateCount`.

Minimal source, adapted from
[adopters/configurations/adapters/calcite/calcite.yaml](../adopters/configurations/adapters/calcite/calcite.yaml)
(run against a real Avatica server by `CalciteFrontDoorsTest`):

```yaml
hooks:
  - type: sql-readonly
    operations: [TOOL_CALL]
    mode: ENFORCE
    methods: [jdbc_run_query]
    settings:
      arguments: [sql]
      maxChars: 4000

sources:
  - id: calcite
    type: jdbc
    settings:
      url: jdbc:avatica:remote:url=http://avatica:8765
      driverClass: org.apache.calcite.avatica.remote.Driver
      userEnv: CALCITE_USER
      passwordEnv: CALCITE_PASSWORD
      maxRows: 200
      maxCellChars: 4096
      loginTimeoutSeconds: 15
      allowWrites: false
    tools:
      jdbc_server_info: {class: READ}
      jdbc_list_catalogs: {class: READ}
      jdbc_list_schemas: {class: READ}
      jdbc_list_tables:
        class: READ
        resources: [{type: "calcite:schema", pointer: /schema, required: false}]
      jdbc_describe_table:
        class: READ
        resources: [{type: "calcite:table", pointer: /table}]
      jdbc_run_query: {class: READ}
```

```text
MCP call -> engine controls -> allowed
  -> JdbcToolSource executor
  -> DriverManager.getConnection(url, {user, password})
       jdbc_run_query:         SqlReadOnlyHook.firstProblem           -> refused? tool error, no SQL sent
       jdbc_execute_statement: SqlReadOnlyHook.singleStatementProblem -> refused? tool error, no SQL sent
  -> DatabaseMetaData call | executeQuery | executeUpdate
       (setMaxRows(maxRows + 1), setQueryTimeout(call budget))
  -> rows(): at most maxRows rows, each value at most maxCellChars
  -> ToolResult (structured JSON) -> engine -> MCP client
```

## Tools

| Tool | Class | What it does |
| --- | --- | --- |
| `jdbc_server_info` | `JdbcToolSource` | Product, versions, driver, read-only flag and the sanitised URL. |
| `jdbc_list_catalogs` | `JdbcToolSource` | `DatabaseMetaData.getCatalogs()`, bounded. |
| `jdbc_list_schemas` | `JdbcToolSource` | `DatabaseMetaData.getSchemas()`, bounded. |
| `jdbc_list_tables` | `JdbcToolSource` | Tables and views for optional `catalog`, `schema`, `namePattern`. |
| `jdbc_describe_table` | `JdbcToolSource` | Columns and primary key of `table` (required). |
| `jdbc_run_query` | `JdbcToolSource` | One read-only statement in `sql`, checked by `SqlReadOnlyHook.firstProblem`. |
| `jdbc_execute_statement` | `JdbcToolSource` | One statement in `sql` via `executeUpdate`; offered only with `allowWrites: true`. |

## Failure behaviour

- A `SQLException` whose SQLState starts with `08` or `57` becomes an `ExecutionException`
  ("database connection failed"), which counts as an availability failure for the circuit breaker.
- Any other `SQLException` is returned as a tool error with the SQLState and the first line of the
  message (at most 400 characters). It does not count against the breaker.
- Any other exception becomes an `ExecutionException` naming only the exception class; it also counts
  as an availability failure.
- A statement refused by the classifier is a tool error starting with `Refused:`; the connection is
  opened, but no SQL is sent.
- If `getPrimaryKeys` throws (some engines have no primary keys), `primaryKey` is an empty list.
- If the driver rejects `setMaxRows`, `setQueryTimeout` or `setReadOnly`, the call continues without it.
- At startup, an invalid `url` or bound, a missing `driverClass` or an unset named variable is a
  `ConfigException` with the settings path. Error messages carry the variable name, never its value.

## Limits

- Read-only is a hint plus the guard. The classifier is a keyword and shape check, so
  `SELECT my_udf()` passes, and `setReadOnly` is a hint the driver may ignore. The real control is a
  read-only database credential.
- The egress allow list does not apply to this source; `egressTargets` is always empty.
- No native preview: the executors never read `ExecutionContext.dryRun()`.
- `sql` is capped at 8192 characters inside the source, whatever the hook's `maxChars` is.
- No JDBC driver is bundled.

## Tests

- `src/test/java/io/github/vaquarkhan/aegis/source/jdbc/JdbcToolSourceTest.java` runs against embedded
  Apache Derby in memory, with no test double. It covers named credentials from `BuildContext`, the
  tool list with and without `allowWrites`, real metadata and primary keys, row and cell caps, refusal
  of writes in `jdbc_run_query` with the data unchanged, SQLState `42X05` as a caller error, SQLState
  `08*` as a backend failure, single-statement writes, URL sanitising and settings bounds.
- `examples/aegis-validation/.../CalciteFrontDoorsTest.java` (profile `real-engines`) runs `calcite.yaml`
  against Apache Calcite behind Avatica through both front doors.

## Developer instructions

```powershell
mvn -B -pl aegis-source-jdbc -am test
```

1. Never build SQL by concatenating caller arguments; use the shared single-statement check in
   `SqlReadOnlyHook` and pass names as `DatabaseMetaData` parameters.
2. Statement-carrying READ tools need an honest credential and usually a `sql-readonly` hook.
3. Drivers are provided by the operator; do not shade proprietary drivers into the gateway.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-source-jdbc)
- [Configuration](../docs/CONFIGURATION.md)
- [AGENTS.md](../AGENTS.md)
