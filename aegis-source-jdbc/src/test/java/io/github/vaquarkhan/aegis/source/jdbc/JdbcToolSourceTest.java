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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The JDBC source against a <b>real SQL engine</b>: embedded Apache Derby, in memory.
 *
 * <p>There is deliberately <b>no test double in this module</b>. Everything below runs through a real
 * JDBC driver against a real database: real {@code DatabaseMetaData}, real {@code SQLState} codes, real
 * primary keys, real type names. A hand-written {@code java.sql} test double can only ever agree with whoever
 * wrote it, and that is exactly how this project shipped endpoints that do not exist. The audit of that
 * is in {@code docs/contributing/DEFECT-LOG.md}.
 *
 * <p>Derby is not one of the engines Aegis governs. It is here because it is an Apache-licensed SQL
 * engine that runs in-process with no server and no container, so "test against something real" costs
 * nothing. Hive, Impala, Doris, Trino and Avatica behave differently in places, and those differences
 * are what the Testcontainers profile is for; what this proves is that the source works against a
 * driver nobody here wrote.
 */
class JdbcToolSourceTest {

    static final String URL = "jdbc:derby:memory:aegis-jdbc-test;create=true";

    static final JdbcSettings READ_ONLY = new JdbcSettings(URL, null, null, null, 3, 32, 15, false);
    static final JdbcSettings WRITABLE = new JdbcSettings(URL, null, null, null, 3, 32, 15, true);

    JdbcToolSource source(JdbcSettings s) {
        return new JdbcToolSource("sales", s, () -> DriverManager.getConnection(URL));
    }

    /**
     * Derby 10.17 ships as a JPMS module and its driver is not auto-discovered on a plain classpath, so
     * it is loaded explicitly. This is the same thing an operator does with {@code driverClass} in the
     * configuration, and it is why that setting exists.
     */
    @org.junit.jupiter.api.BeforeAll
    static void loadRealDriver() throws Exception {
        // Read out of derby-10.17.1.0.jar's own META-INF/services/java.sql.Driver, not guessed: the old
        // org.apache.derby.jdbc.EmbeddedDriver no longer exists in this line. Finding that out is the
        // kind of thing testing against a real driver tells you and a test double never would.
        Class.forName("org.apache.derby.iapi.jdbc.AutoloadedDriver");
    }

    @BeforeEach
    void createRealSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.executeUpdate("create table orders (id bigint not null primary key, "
                    + "customer varchar(200), note varchar(2000))");
            st.executeUpdate("insert into orders values "
                    + "(1, 'acme', 'first'), (2, 'globex', 'second'), (3, 'initech', 'third'), (4, 'umbrella', 'fourth')");
        }
    }

    @AfterEach
    void dropRealSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.executeUpdate("drop table orders");
        }
    }

    static ToolResult call(ToolSource src, String tool, Map<String, Object> args) throws Exception {
        for (ToolSource.SourcedTool t : src.discover()) {
            if (t.descriptor().name().equals(tool)) {
                return t.executor().execute(new ExecutionContext("req-1", tool, args,
                        CallerIdentity.of("ana", Set.of("analysts"), "idp", "jwt"), Map.of(), 30_000, false));
            }
        }
        throw new AssertionError("no such tool: " + tool);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(ToolResult r) {
        Object structured = r.body().get("structuredContent");
        return structured instanceof Map ? (Map<String, Object>) structured : Map.of();
    }

    static String text(ToolResult r) {
        Object content = r.body().get("content");
        if (content instanceof List<?> parts && !parts.isEmpty() && parts.get(0) instanceof Map<?, ?> first) {
            return String.valueOf(first.get("text"));
        }
        return "";
    }

    /** Asks the database directly, outside the source, so a claim about real state is checkable. */
    static long countOrders() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from orders")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    // ------------------------------------------------------------------ surface

    @Test
    void factoryResolvesNamedCredentialsFromBuildContextRatherThanGlobalEnvironment() throws Exception {
        JdbcToolSourceFactory factory = new JdbcToolSourceFactory();
        Map<String, String> secrets = Map.of("DB_USER", "context-user", "DB_PASSWORD", "context-password");
        ConfigNode settings = new ConfigNode("source.settings", Map.of(
                "url", URL, "userEnv", "DB_USER", "passwordEnv", "DB_PASSWORD"));
        ToolSource source = factory.create("sales", settings, BuildContext.defaults(Path.of(".")).withEnv(secrets::get));
        assertTrue(source.discover().stream().anyMatch(t -> t.descriptor().name().equals("jdbc_server_info")));

        ConfigNode missing = new ConfigNode("source.settings", Map.of("url", URL, "userEnv", "MISSING"));
        ConfigException e = assertThrows(ConfigException.class,
                () -> factory.create("sales", missing, BuildContext.defaults(Path.of(".")).withEnv(secrets::get)));
        assertTrue(e.getMessage().contains("MISSING"), e.getMessage());
    }

    // ------------------------------------------------------------------ surface

    @Test
    void theToolSurfaceIsBuiltFromJavaSqlAndWritesAreOptIn() throws Exception {
        List<String> readOnlyNames = new ArrayList<>();
        for (ToolSource.SourcedTool t : source(READ_ONLY).discover()) {
            readOnlyNames.add(t.descriptor().name());
        }
        assertEquals(List.of("jdbc_server_info", "jdbc_list_catalogs", "jdbc_list_schemas",
                "jdbc_list_tables", "jdbc_describe_table", "jdbc_run_query"), readOnlyNames,
                "a source with allowWrites false must not offer a write tool at all");

        List<String> writableNames = new ArrayList<>();
        for (ToolSource.SourcedTool t : source(WRITABLE).discover()) {
            writableNames.add(t.descriptor().name());
        }
        assertTrue(writableNames.contains("jdbc_execute_statement"));
        assertEquals(readOnlyNames.size() + 1, writableNames.size());
    }

    @Test
    void serverInfoReportsWhatTheRealDriverSays() throws Exception {
        ToolResult r = call(source(READ_ONLY), "jdbc_server_info", Map.of());
        assertFalse(r.isError(), text(r));
        Map<String, Object> b = body(r);
        assertEquals("Apache Derby", b.get("databaseProduct"),
                "this value comes from the real driver, not from anything written here");
        assertNotNull(b.get("databaseVersion"));
        assertTrue(String.valueOf(b.get("driverName")).contains("Derby"), String.valueOf(b.get("driverName")));
        assertEquals(URL, b.get("url"));
    }

    // ------------------------------------------------------------------ metadata, from the real engine

    @Test
    void listTablesFindsTheRealTable() throws Exception {
        ToolResult r = call(source(READ_ONLY), "jdbc_list_tables", Map.of("schema", "APP"));
        assertFalse(r.isError(), text(r));
        assertTrue(text(r).contains("ORDERS"), text(r));
    }

    @Test
    void listSchemasAndCatalogsRunAgainstTheRealEngine() throws Exception {
        assertFalse(call(source(READ_ONLY), "jdbc_list_schemas", Map.of()).isError());
        // Derby reports no catalogs. An empty result is a real answer, not a failure.
        ToolResult r = call(source(READ_ONLY), "jdbc_list_catalogs", Map.of());
        assertFalse(r.isError(), text(r));
    }

    @Test
    void describeTableReturnsRealColumnsTypesAndPrimaryKey() throws Exception {
        ToolResult r = call(source(READ_ONLY), "jdbc_describe_table", Map.of("schema", "APP", "table", "ORDERS"));
        assertFalse(r.isError(), text(r));
        Map<String, Object> b = body(r);
        List<?> columns = (List<?>) b.get("columns");
        assertEquals(3, columns.size(), "the real table has three columns: " + columns);
        Map<?, ?> id = (Map<?, ?>) columns.get(0);
        assertEquals("ID", id.get("name"));
        assertEquals("BIGINT", id.get("type"), "the type name comes from Derby, not from a test double");
        assertEquals(Boolean.FALSE, id.get("nullable"), "the real column is declared not null");
        assertEquals(List.of("ID"), b.get("primaryKey"), "Derby really does report the primary key");
    }

    // ------------------------------------------------------------------ bounds

    @Test
    void rowsAreCappedAndTheCapIsReported() throws Exception {
        assertEquals(4L, countOrders(), "four real rows exist");
        ToolResult r = call(source(READ_ONLY), "jdbc_run_query", Map.of("sql", "select id from orders"));
        assertFalse(r.isError(), text(r));
        Map<String, Object> b = body(r);
        assertEquals(3L, ((Number) b.get("rowCount")).longValue(), "maxRows is 3, so three rows come back");
        assertEquals(Boolean.TRUE, b.get("truncated"));
        assertNotNull(b.get("note"), "a truncated result must say so rather than look complete");
    }

    @Test
    void oneWideValueCannotFloodTheContext() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement st = c.createStatement()) {
            st.executeUpdate("update orders set note = '" + "x".repeat(500) + "' where id = 1");
        }
        ToolResult r = call(source(READ_ONLY), "jdbc_run_query",
                Map.of("sql", "select note from orders where id = 1"));
        assertFalse(r.isError(), text(r));
        String value = String.valueOf(((Map<?, ?>) ((List<?>) body(r).get("rows")).get(0)).get("NOTE"));
        assertTrue(value.endsWith("...(truncated)"), value);
        assertTrue(value.length() < 100, "maxCellChars is 32 here, got " + value.length());
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void runQueryRefusesAnythingThatIsNotOneReadStatementAndTheDataSurvives() throws Exception {
        for (String bad : List.of("drop table orders", "select 1; drop table orders",
                "insert into orders values (99, 'x', 'y')", "update orders set customer = 'x'",
                "delete from orders")) {
            ToolResult r = call(source(READ_ONLY), "jdbc_run_query", Map.of("sql", bad));
            assertTrue(r.isError(), bad + " should have been refused");
            assertTrue(text(r).contains("read-only"), text(r));
        }
        // The real proof: the real table is untouched and still has its four real rows.
        assertEquals(4L, countOrders(), "a refused statement must never reach the database");
    }

    @Test
    void aSqlErrorIsACallerErrorAndDoesNotTripTheCircuitBreaker() throws Exception {
        ToolResult r = call(source(READ_ONLY), "jdbc_run_query", Map.of("sql", "select * from no_such_table"));
        assertTrue(r.isError());
        // Derby's own SQLState for an unknown table. Not a value this test invented.
        assertTrue(text(r).contains("42X05"), text(r));
    }

    @Test
    void aConnectionFailureIsABackendFailureSoTheBreakerSeesIt() {
        // A real DriverManager refusal, SQLState 08001: no driver can handle this URL. This is the shape
        // an unreachable or misconfigured database takes, and it must reach the circuit breaker rather
        // than being returned to the model as if the caller had asked a bad question.
        JdbcSettings unreachable =
                new JdbcSettings("jdbc:aegis-no-such-driver://127.0.0.1:1/nope", null, null, null, 3, 32, 5, false);
        JdbcToolSource src = new JdbcToolSource("sales", unreachable,
                () -> DriverManager.getConnection(unreachable.url()));
        ToolExecutor.ExecutionException e = assertThrows(ToolExecutor.ExecutionException.class,
                () -> call(src, "jdbc_server_info", Map.of()));
        assertTrue(e.getMessage().contains("08"), "expected an 08* connection SQLState, got: " + e.getMessage());
    }

    // ------------------------------------------------------------------ writes

    @Test
    void executeStatementReallyChangesTheDatabase() throws Exception {
        ToolResult r = call(source(WRITABLE), "jdbc_execute_statement",
                Map.of("sql", "delete from orders where id = 1"));
        assertFalse(r.isError(), text(r));
        assertEquals(1L, ((Number) body(r).get("updateCount")).longValue());
        assertEquals(3L, countOrders(), "the row is really gone");
    }

    @Test
    void executeStatementRefusesMoreThanOneStatementAndChangesNothing() throws Exception {
        ToolResult r = call(source(WRITABLE), "jdbc_execute_statement",
                Map.of("sql", "delete from orders; drop table orders"));
        assertTrue(r.isError());
        assertEquals(4L, countOrders(), "nothing was executed");
        // A trailing ';' must not let a second statement through.
        ToolResult trailing = call(source(WRITABLE), "jdbc_execute_statement",
                Map.of("sql", "delete from orders; drop table orders;"));
        assertTrue(trailing.isError(), text(trailing));
        assertTrue(text(trailing).contains("more than one statement"), text(trailing));
        assertEquals(4L, countOrders(), "nothing was executed");
        // A ';' inside a literal is data, so this single statement runs (Derby rejects a trailing ';').
        ToolResult literal = call(source(WRITABLE), "jdbc_execute_statement",
                Map.of("sql", "delete from orders where note = 'a;b' or id = 1"));
        assertFalse(literal.isError(), text(literal));
        assertEquals(3L, countOrders(), "the one statement really ran");
    }

    // ------------------------------------------------------------------ settings

    @Test
    void theUrlIsSanitisedBeforeItIsEverReported() {
        JdbcSettings s = new JdbcSettings("jdbc:example://user:pw@db:5432/sales?tls=true",
                null, null, null, 10, 100, 5, false);
        assertEquals("jdbc:example://db:5432/sales", s.safeUrl());
        assertFalse(s.safeUrl().contains("pw"));
    }

    @Test
    void theUrlSanitiserStripsCredentialsInEveryCommonJdbcShape() {
        // SQL Server and Hive put the secret in a semicolon property, not a userinfo authority.
        JdbcSettings sqlServer = new JdbcSettings(
                "jdbc:sqlserver://h:1433;databaseName=sales;user=svc;password=S3cret!",
                null, null, null, 10, 100, 5, false);
        assertFalse(sqlServer.safeUrl().contains("S3cret"), sqlServer.safeUrl());
        assertFalse(sqlServer.safeUrl().toLowerCase(java.util.Locale.ROOT).contains("svc"), sqlServer.safeUrl());
        assertTrue(sqlServer.safeUrl().contains("databaseName=sales"), sqlServer.safeUrl());

        JdbcSettings hive = new JdbcSettings(
                "jdbc:hive2://h:10000/db;user=svc;password=hunter2",
                null, null, null, 10, 100, 5, false);
        assertFalse(hive.safeUrl().contains("hunter2"), hive.safeUrl());

        // Oracle thin basic form carries the credential before an @.
        JdbcSettings oracle = new JdbcSettings(
                "jdbc:oracle:thin:scott/tiger@//h:1521/orcl",
                null, null, null, 10, 100, 5, false);
        assertFalse(oracle.safeUrl().contains("tiger"), oracle.safeUrl());
    }

    @Test
    void settingsRefuseValuesThatWouldDisableTheBounds() {
        IllegalArgumentException notJdbc = assertThrows(IllegalArgumentException.class,
                () -> new JdbcSettings("postgresql://svc:s3cret-pw@db/sales", null, null, null, 10, 100, 5, false));
        assertFalse(notJdbc.getMessage().contains("s3cret-pw"), "a rejected URL is never echoed: " + notJdbc.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcSettings("jdbc:x://h/d", null, null, null, 0, 100, 5, false));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcSettings("jdbc:x://h/d", null, null, null, 100_000, 100, 5, false));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcSettings("postgres://h/d", null, null, null, 10, 100, 5, false),
                "a url that is not a JDBC url must be refused at startup");
    }
}
