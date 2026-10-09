/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.source.jdbc;

import io.github.vaquarkhan.aegis.config.hooks.SqlReadOnlyHook;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A governed tool source over any JDBC driver, using {@code java.sql} only.
 *
 * <p><b>Why this exists.</b> Several Apache projects have no REST control plane at all, and their real
 * control surface is SQL: Hive through HiveServer2, Impala, Doris, Druid, Avatica and Phoenix, Trino,
 * Spark Thrift. Writing a declarative HTTP config for those means inventing an API, which is how this
 * project shipped endpoints that do not exist (see {@code docs/DEFECT-LOG.md}). A JDBC source is the
 * accurate alternative, and it is structurally safer for a reason worth stating: every metadata read here
 * is a <b>typed call on the driver's own API</b>, so the compiler checks it. A path in a YAML file is
 * just text and nothing checks it at all.
 *
 * <p><b>It ships no driver.</b> The operator puts their engine's driver on the classpath. That is how
 * JDBC is designed to work, it means Aegis never redistributes somebody else's driver, and it means the
 * driver version is the operator's choice rather than ours.
 *
 * <p><b>Three governance facts to be clear about.</b>
 * <ul>
 *   <li><b>Egress control does not apply.</b> A JDBC driver opens its own socket, so
 *       {@link ToolExecutor#egressTargets} reports nothing and step 5 has nothing to check. The
 *       connection target is whatever the operator put in {@code url}, which is configuration rather
 *       than an argument, so an agent cannot redirect it. Network reachability is the deployment's job.</li>
 *   <li><b>Read-only is indicated, not proved.</b> {@code jdbc_run_query} refuses anything that is not a
 *       single read statement, using the same classifier as the {@code sql-readonly} hook, and it sets
 *       {@link Connection#setReadOnly}. Neither is a guarantee: {@code SELECT my_udf()} passes the
 *       classifier and a user-defined function can write in most engines, and {@code setReadOnly} is a
 *       hint the driver may ignore. <b>The real control is a read-only credential on the source.</b></li>
 *   <li><b>Results are bounded.</b> Rows are capped by {@code maxRows} and each rendered value by
 *       {@code maxCellChars}, so one wide table cannot fill the model's context or the audit journal.</li>
 * </ul>
 *
 * <p>Capability only, like every other source: each tool's class, resources and dry-run mode come from
 * the operator's {@code tools:} section. This class never decides whether a call is allowed.
 */
public final class JdbcToolSource implements ToolSource {

    /** Opens a connection. A seam so tests can drive the source without a real driver. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private static final String NO_ARGS = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    private static final String OPTIONAL_PLACE =
            "\"catalog\":{\"type\":\"string\",\"maxLength\":256},\"schema\":{\"type\":\"string\",\"maxLength\":256}";

    private final String id;
    private final JdbcSettings settings;
    private final Connections connections;

    public JdbcToolSource(String id, JdbcSettings settings, Connections connections) {
        this.id = id;
        this.settings = settings;
        this.connections = connections;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<SourcedTool> discover() {
        List<SourcedTool> out = new ArrayList<>();
        out.add(tool("jdbc_server_info",
                "Database product and driver version reported by the JDBC driver, and the sanitised URL.",
                NO_ARGS, (c, a, t) -> serverInfo(c)));
        out.add(tool("jdbc_list_catalogs", "Catalogs visible to the gateway's database credential.",
                NO_ARGS, (c, a, t) -> rows(c.getMetaData().getCatalogs(), "catalogs")));
        out.add(tool("jdbc_list_schemas", "Schemas visible to the gateway's database credential.",
                NO_ARGS, (c, a, t) -> rows(c.getMetaData().getSchemas(), "schemas")));
        out.add(tool("jdbc_list_tables", "Tables and views in one catalog and schema.",
                "{\"type\":\"object\",\"properties\":{" + OPTIONAL_PLACE
                        + ",\"namePattern\":{\"type\":\"string\",\"maxLength\":256}},\"additionalProperties\":false}",
                (c, a, t) -> listTables(c, a)));
        out.add(tool("jdbc_describe_table", "Columns, types and primary key of one table.",
                "{\"type\":\"object\",\"properties\":{" + OPTIONAL_PLACE
                        + ",\"table\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256}},"
                        + "\"required\":[\"table\"],\"additionalProperties\":false}",
                (c, a, t) -> describeTable(c, a)));
        out.add(tool("jdbc_run_query",
                "Run ONE read-only SQL statement and return up to maxRows rows.",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":8192}},"
                        + "\"required\":[\"sql\"],\"additionalProperties\":false}",
                (c, a, t) -> runQuery(c, a, t)));
        if (settings.allowWrites()) {
            // Not offered at all unless the operator asked for it: a source that cannot write is a
            // stronger control than a write tool nobody classified.
            out.add(tool("jdbc_execute_statement",
                    "Execute ONE statement that changes data or schema. Not reversible by this tool.",
                    "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":8192}},"
                            + "\"required\":[\"sql\"],\"additionalProperties\":false}",
                    (c, a, t) -> executeStatement(c, a, t)));
        }
        return out;
    }

    @FunctionalInterface
    private interface Op {
        ToolResult run(Connection connection, Map<String, Object> args, long timeoutMillis) throws Exception;
    }

    private SourcedTool tool(String name, String description, String schema, Op op) {
        ToolDescriptor d = new ToolDescriptor(name, null, description, Json.parseObject(schema), null, null);
        return new SourcedTool(d, new ToolExecutor() {
            @Override
            public List<URI> egressTargets(Map<String, Object> backendArguments) {
                return List.of(); // JDBC is not HTTP; the target is configuration, not an argument
            }

            @Override
            public ToolResult execute(ExecutionContext ctx) throws ToolExecutor.ExecutionException {
                try (Connection c = connections.open()) {
                    return op.run(c, ctx.arguments(), ctx.timeoutMillis());
                } catch (SQLException e) {
                    // A SQL error is the caller's problem (bad identifier, no permission), not a backend
                    // outage, so it must not count against the circuit breaker. SQLState 08* is the
                    // exception: those are connection failures and the breaker should see them.
                    String state = e.getSQLState() == null ? "" : e.getSQLState();
                    if (state.startsWith("08") || state.startsWith("57")) {
                        throw new ToolExecutor.ExecutionException(
                                "database connection failed (SQLState " + state + ")", false, e);
                    }
                    return ToolResult.text("The database rejected the request: SQLState " + state
                            + ": " + firstLine(e.getMessage()), true);
                } catch (ToolExecutor.ExecutionException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ToolExecutor.ExecutionException(
                            "JDBC call failed: " + e.getClass().getSimpleName(), false, e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ tools

    private ToolResult serverInfo(Connection c) throws SQLException {
        DatabaseMetaData m = c.getMetaData();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", settings.safeUrl());
        out.put("databaseProduct", m.getDatabaseProductName());
        out.put("databaseVersion", m.getDatabaseProductVersion());
        out.put("driverName", m.getDriverName());
        out.put("driverVersion", m.getDriverVersion());
        out.put("readOnlyConnection", m.isReadOnly());
        return ToolResult.structured(Json.write(out), out);
    }

    private ToolResult listTables(Connection c, Map<String, Object> a) throws SQLException {
        String catalog = str(a.get("catalog"));
        String schema = str(a.get("schema"));
        String pattern = a.get("namePattern") == null ? "%" : str(a.get("namePattern"));
        try (ResultSet rs = c.getMetaData().getTables(catalog, schema, pattern, new String[] {"TABLE", "VIEW"})) {
            return rows(rs, "tables");
        }
    }

    private ToolResult describeTable(Connection c, Map<String, Object> a) throws SQLException {
        String catalog = str(a.get("catalog"));
        String schema = str(a.get("schema"));
        String table = str(a.get("table"));
        List<Object> columns = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getColumns(catalog, schema, table, "%")) {
            while (rs.next() && columns.size() < settings.maxRows()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", rs.getString("COLUMN_NAME"));
                col.put("type", rs.getString("TYPE_NAME"));
                col.put("size", (long) rs.getInt("COLUMN_SIZE"));
                col.put("nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                columns.add(col);
            }
        }
        List<Object> primaryKey = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                primaryKey.add(rs.getString("COLUMN_NAME"));
            }
        } catch (SQLException e) {
            // Several engines (Hive, Impala, Druid) have no primary keys and throw rather than return
            // an empty set. An absent concept is not an error worth failing the whole describe over.
            primaryKey.clear();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        out.put("columns", columns);
        out.put("primaryKey", primaryKey);
        return ToolResult.structured(Json.write(out), out);
    }

    private ToolResult runQuery(Connection c, Map<String, Object> a, long timeoutMillis) throws SQLException {
        String sql = str(a.get("sql"));
        // Defence in depth. The sql-readonly hook should also be bound, but a source that can be
        // deployed without it must not depend on it: the same classifier runs here too.
        String problem = SqlReadOnlyHook.firstProblem(sql, 8192);
        if (problem != null) {
            return ToolResult.text("Refused: jdbc_run_query takes one read-only statement, and " + problem, true);
        }
        trySetReadOnly(c);
        try (Statement st = c.createStatement()) {
            applyLimits(st, timeoutMillis);
            try (ResultSet rs = st.executeQuery(sql)) {
                return rows(rs, "rows");
            }
        }
    }

    private ToolResult executeStatement(Connection c, Map<String, Object> a, long timeoutMillis) throws SQLException {
        String sql = str(a.get("sql"));
        // One approval covers one statement. The shape check ignores ';' inside literals and comments
        // and refuses "a; b;" as well as "a; b", so a second statement cannot ride on the first.
        String problem = SqlReadOnlyHook.singleStatementProblem(sql, 8192);
        if (problem != null) {
            return ToolResult.text("Refused: jdbc_execute_statement takes exactly one statement, and " + problem, true);
        }
        try (Statement st = c.createStatement()) {
            applyLimits(st, timeoutMillis);
            int affected = st.executeUpdate(sql);
            Map<String, Object> out = Map.of("updateCount", (long) affected);
            return ToolResult.structured(Json.write(out), out);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Renders a result set as bounded rows. Never returns more than {@code maxRows}. */
    private ToolResult rows(ResultSet rs, String key) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int columnCount = md.getColumnCount();
        List<String> names = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            names.add(md.getColumnLabel(i));
        }
        List<Object> out = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (out.size() >= settings.maxRows()) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(names.get(i - 1), cell(rs, i));
            }
            out.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(key, out);
        body.put("rowCount", (long) out.size());
        body.put("truncated", truncated);
        if (truncated) {
            body.put("note", "stopped at maxRows=" + settings.maxRows() + "; narrow the query rather than raising it");
        }
        return ToolResult.structured(Json.write(body), body);
    }

    /**
     * One value, as a JSON-safe scalar. Everything that is not a number or a boolean becomes a bounded
     * string: a driver can return arrays, structs, blobs and vendor objects, and none of those belong in
     * a model's context unrendered.
     */
    private Object cell(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
            return ((Number) v).longValue();
        }
        String s = String.valueOf(v);
        return s.length() <= settings.maxCellChars() ? s
                : s.substring(0, settings.maxCellChars()) + "...(truncated)";
    }

    private void applyLimits(Statement st, long timeoutMillis) {
        try {
            // maxRows + 1, not maxRows. A driver that honours setMaxRows (Derby does) would otherwise
            // hand back exactly maxRows rows with no way to tell a full page from a truncated one, and
            // the result would be reported as complete when it was not. Asking for one extra row makes
            // truncation detectable: if it arrives, there was more. A driver that ignores setMaxRows is
            // still bounded by the reader loop in rows().
            st.setMaxRows(settings.maxRows() + 1);
        } catch (SQLException | UnsupportedOperationException e) {
            // A driver that cannot cap rows still gets capped by the reader loop.
        }
        try {
            int seconds = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeoutMillis / 1000));
            st.setQueryTimeout(seconds);
        } catch (SQLException | UnsupportedOperationException e) {
            // Hive and some others do not implement it; the gateway's own timeout still applies.
        }
    }

    private void trySetReadOnly(Connection c) {
        try {
            c.setReadOnly(true);
        } catch (SQLException | UnsupportedOperationException e) {
            // A hint, not a control. Documented in the class javadoc.
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        String one = nl < 0 ? s : s.substring(0, nl);
        return one.length() > 400 ? one.substring(0, 400) + "..." : one;
    }
}
