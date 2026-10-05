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
package io.github.vaquarkhan.aegis.sparkconnect;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.spark.SparkThrowable;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalog.Database;
import org.apache.spark.sql.catalog.Table;
import org.apache.spark.sql.types.StructField;

/**
 * Apache Spark through Spark Connect: the gateway is a Spark Connect CLIENT (the official JVM
 * client, gRPC), and Spark runs in its own server process. Nothing of Spark executes inside the
 * gateway, so there is no classpath coupling between the two.
 *
 * <p>Capability only, like every source: classes, resources, groups and dry-run modes come from the
 * operator's {@code tools:} section. Suggested classification
 * ({@code adopters/configurations/spark-connect/spark-connect.yaml}):
 * <ul>
 *   <li>READ by construction (they build DataFrame plans that cannot write):
 *       {@code spark_list_databases}, {@code spark_list_tables}, {@code spark_describe_table},
 *       {@code spark_preview_table}, {@code spark_explain_sql} (EXPLAIN analyses, never executes)</li>
 *   <li>DESTRUCTIVE: {@code spark_drop_table} (dry run TOOL = {@code spark_describe_table}) and
 *       {@code spark_sql} (arbitrary SQL can write, so it is DESTRUCTIVE whatever the text looks
 *       like; dry run TOOL = {@code spark_explain_sql})</li>
 * </ul>
 * Identifiers are validated and back-quoted, so a table argument can never become SQL. Row output
 * is bounded by {@code maxRows}; the engine then bounds bytes and redacts.
 */
public final class SparkConnectToolSource implements ToolSource {

    private static final System.Logger LOG = System.getLogger(SparkConnectToolSource.class.getName());
    private static final String PART = "[A-Za-z_][A-Za-z0-9_]{0,127}";
    /** {@code table}, {@code db.table} or {@code catalog.db.table}. */
    static final Pattern TABLE = Pattern.compile(PART + "(\\." + PART + "){0,2}");
    /** {@code db} or {@code catalog.db}. */
    static final Pattern DATABASE = Pattern.compile(PART + "(\\." + PART + ")?");
    private static final int MAX_SQL_CHARS = 20_000;

    private final String id;
    private final SparkConnectSettings settings;
    private final ExecutorService pool;
    private org.apache.spark.sql.connect.SparkSession session;

    public SparkConnectToolSource(String id, SparkConnectSettings settings) {
        this.id = id;
        this.settings = settings;
        this.pool = new ThreadPoolExecutor(settings.threads(), settings.threads(), 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(settings.threads() * 16), r -> {
                    Thread t = new Thread(r, "aegis-spark-connect-" + id);
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public String id() {
        return id;
    }

    public SparkConnectSettings settings() {
        return settings;
    }

    /** Lazily connects; the client reconnects by itself after transient failures. */
    private synchronized org.apache.spark.sql.connect.SparkSession session() {
        if (session == null) {
            session = org.apache.spark.sql.connect.SparkSession.builder().remote(settings.connectionString()).create();
        }
        return session;
    }

    private static final String TABLE_ARG = "{\"table\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":400,"
            + "\"description\":\"table, db.table or catalog.db.table\"}}";
    private static final String SQL_ARG = "{\"query\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":" + MAX_SQL_CHARS + "}}";

    @Override
    public List<SourcedTool> discover() {
        Executor ex = new Executor();
        return List.of(
                tool("spark_list_databases", "List Spark databases (namespaces) in the current catalog.",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", ex),
                tool("spark_list_tables", "List tables and views of a Spark database.",
                        "{\"type\":\"object\",\"properties\":{\"database\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":260}},"
                                + "\"additionalProperties\":false}", ex),
                tool("spark_describe_table", "Describe a table's columns (name, type, nullable).",
                        "{\"type\":\"object\",\"properties\":" + TABLE_ARG + ",\"required\":[\"table\"]}", ex),
                tool("spark_preview_table", "Return the first rows of a table as JSON objects.",
                        "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":400},"
                                + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":" + settings.maxRows() + "}},"
                                + "\"required\":[\"table\"],\"additionalProperties\":false}", ex),
                tool("spark_explain_sql", "Show Spark's plan for a SQL statement without executing it.",
                        "{\"type\":\"object\",\"properties\":" + SQL_ARG + ",\"required\":[\"query\"],\"additionalProperties\":false}", ex),
                tool("spark_sql", "Run one SQL statement and return up to maxRows rows. May change data.",
                        "{\"type\":\"object\",\"properties\":" + SQL_ARG + ",\"required\":[\"query\"],\"additionalProperties\":false}", ex),
                tool("spark_drop_table", "Drop a table or view.",
                        "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":400},"
                                + "\"ifExists\":{\"type\":\"boolean\"}},\"required\":[\"table\"],\"additionalProperties\":false}", ex));
    }

    private static SourcedTool tool(String name, String description, String schema, ToolExecutor ex) {
        return new SourcedTool(new ToolDescriptor(name, null, description, Json.parseObject(schema), null, null), ex);
    }

    /** Back-quotes each validated part: the result is always an identifier, never SQL. */
    static String quote(String name, Pattern shape, String what) {
        if (name == null || !shape.matcher(name).matches()) {
            throw new RejectedArgument(what + " must match " + shape.pattern());
        }
        StringBuilder sb = new StringBuilder();
        for (String p : name.split("\\.")) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append('`').append(p).append('`');
        }
        return sb.toString();
    }

    /** A caller argument Spark would never be asked about. */
    static final class RejectedArgument extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RejectedArgument(String message) {
            super(message);
        }
    }

    /** One executor that dispatches by upstream tool name (so dry-run mode TOOL can reuse it). */
    private final class Executor implements ToolExecutor {

        @Override
        public List<URI> egressTargets(Map<String, Object> backendArguments) {
            return List.of(settings.egressUri());
        }

        @Override
        public boolean dispatchesByUpstreamName() {
            return true;
        }

        @Override
        public ToolResult execute(ExecutionContext ctx) throws ExecutionException {
            Map<String, Object> a = ctx.arguments();
            String tag = "aegis-" + UUID.randomUUID();
            Future<ToolResult> f;
            try {
                f = pool.submit(() -> {
                    org.apache.spark.sql.connect.SparkSession s = session();
                    s.addTag(tag);
                    try {
                        return run(s, ctx.upstreamToolName(), a);
                    } finally {
                        s.removeTag(tag);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                throw new ExecutionException("Spark Connect source is saturated", false, e);
            }
            try {
                return f.get(ctx.timeoutMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                interrupt(tag);
                throw new ExecutionException("Spark Connect operation timed out", true, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupt(tag);
                throw new ExecutionException("interrupted", false, e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                if (c instanceof RejectedArgument) {
                    return ToolResult.text("Aegis rejected the argument: " + c.getMessage(), true);
                }
                if (c instanceof SparkThrowable st) {
                    // Spark analysed and refused the request (unknown table, bad SQL, ...): a caller
                    // error with Spark's stable error class, not a backend outage.
                    String cls = st.getCondition() == null ? "" : st.getCondition();
                    return ToolResult.text("Spark rejected the request: " + cls + " " + firstLine(c.getMessage()), true);
                }
                throw new ExecutionException("Spark Connect call failed: " + c.getClass().getSimpleName(), false, c);
            }
        }

        private void interrupt(String tag) {
            try {
                session().interruptTag(tag);
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "could not interrupt Spark Connect operation " + tag + ": " + ex);
            }
        }
    }

    /** Scala methods declare {@code AnalysisException}; it is a {@link SparkThrowable}, handled by the caller. */
    private ToolResult run(org.apache.spark.sql.connect.SparkSession s, String tool, Map<String, Object> a) throws Exception {
        switch (tool) {
            case "spark_list_databases": {
                List<String> names = new ArrayList<>();
                for (Database d : s.catalog().listDatabases().collectAsList()) {
                    names.add(d.name());
                }
                return ToolResult.structured(String.join(",", names), Map.of("databases", names));
            }
            case "spark_list_tables": {
                Object db = a.get("database");
                Dataset<Table> ds = db == null ? s.catalog().listTables()
                        : s.catalog().listTables(unquotedChecked((String) db, DATABASE, "database"));
                List<Object> tables = new ArrayList<>();
                for (Table t : ds.collectAsList()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", t.name());
                    m.put("namespace", t.namespace() == null ? List.of() : List.of(t.namespace()));
                    m.put("tableType", String.valueOf(t.tableType()));
                    m.put("temporary", t.isTemporary());
                    tables.add(m);
                }
                return ToolResult.structured(tables.size() + " tables", Map.of("tables", tables));
            }
            case "spark_describe_table": {
                String q = quote((String) a.get("table"), TABLE, "table");
                List<Object> cols = new ArrayList<>();
                for (StructField f : s.table(q).schema().fields()) {
                    cols.add(Map.of("name", f.name(), "type", f.dataType().simpleString(), "nullable", f.nullable()));
                }
                return ToolResult.structured(cols.size() + " columns", Map.of("table", a.get("table"), "columns", cols));
            }
            case "spark_preview_table": {
                String q = quote((String) a.get("table"), TABLE, "table");
                Long lim = JsonValues.integral(a.get("limit"));
                int n = lim == null ? Math.min(20, settings.maxRows()) : (int) Math.min(lim, settings.maxRows());
                return rows(s.table(q).limit(n));
            }
            case "spark_explain_sql": {
                List<Row> plan = s.sql("EXPLAIN " + sqlText(a)).collectAsList();
                String text = plan.isEmpty() ? "" : String.valueOf(plan.get(0).get(0));
                return ToolResult.structured(text, Map.of("plan", text));
            }
            case "spark_sql":
                return rows(s.sql(sqlText(a)).limit(settings.maxRows()));
            case "spark_drop_table": {
                String q = quote((String) a.get("table"), TABLE, "table");
                s.sql("DROP TABLE " + (Boolean.TRUE.equals(a.get("ifExists")) ? "IF EXISTS " : "") + q).collectAsList();
                return ToolResult.text("Dropped " + a.get("table"), false);
            }
            default:
                throw new RejectedArgument("unknown Spark Connect tool " + tool);
        }
    }

    /** Validated for shape; listTables takes an unquoted name, so only the strict pattern is accepted. */
    private static String unquotedChecked(String name, Pattern shape, String what) {
        quote(name, shape, what);
        return name;
    }

    private static String sqlText(Map<String, Object> a) {
        Object q = a.get("query");
        if (!(q instanceof String s) || s.isBlank() || s.length() > MAX_SQL_CHARS) {
            throw new RejectedArgument("query must be a non-empty SQL statement of at most " + MAX_SQL_CHARS + " characters");
        }
        return s;
    }

    private static ToolResult rows(Dataset<Row> df) {
        List<Object> rows = new ArrayList<>();
        for (String json : df.toJSON().collectAsList()) {
            rows.add(Json.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    io.github.vaquarkhan.aegis.engine.json.JsonLimits.DEFAULT));
        }
        return ToolResult.structured(rows.size() + " rows", Map.of("rows", rows));
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 500 ? l.substring(0, 500) : l;
    }

    @Override
    public synchronized void close() {
        pool.shutdownNow();
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "closing Spark Connect session failed: " + e);
            }
            session = null;
        }
    }
}
