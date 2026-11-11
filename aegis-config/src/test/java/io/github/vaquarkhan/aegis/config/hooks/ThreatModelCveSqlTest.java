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
/*
 * Threat-model reproduction suite (SQL read-only hook). Replays the attack behind public SQL-injection
 * CVEs in MCP database servers against SqlReadOnlyHook. Verdict lines: THREATMODEL|id|refs|class|verdict|detail
 */
package io.github.vaquarkhan.aegis.config.hooks;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ThreatModelCveSqlTest {

  static void verdict(String id, String refs, String cls, String verdict, String detail) {
    System.out.println("THREATMODEL|" + id + "|" + refs + "|" + cls + "|" + verdict + "|" + detail);
  }

  private static String problem(String sql) {
    return SqlReadOnlyHook.firstProblem(sql, 8192);
  }

  @Test
  void tm04_stackedAndDmlStatements_areDenied() {
    List<String> attacks =
        List.of(
            "SELECT * FROM users WHERE id = 1; DROP TABLE users",
            "SELECT 1; DELETE FROM audit_log",
            "DELETE FROM customers",
            "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x",
            "SELECT * FROM t INTO OUTFILE '/tmp/x'",
            "SELECT * FROM t FOR UPDATE",
            "EXPLAIN ANALYZE DELETE FROM t",
            "INSERT INTO t VALUES (1)",
            "SELECT 1 /* unterminated",
            "SELECT 'unterminated",
            "CALL dangerous_proc()",
            "SET search_path = evil; SELECT 1",
            "COPY t FROM '/etc/passwd'",
            "SELECT 1\n; DROP TABLE t -- hidden on second line",
            "SELECT * FROM t WHERE a = 'x'; DELETE FROM t; -- ' ");
    for (String sql : attacks) {
      assertNotNull(problem(sql), "should be denied: " + sql);
    }
    assertNull(problem("SELECT name, email FROM customers WHERE region = 'EMEA' -- quarterly"));
    assertNull(
        problem(
            "WITH recent AS (SELECT * FROM orders WHERE ts > now() - interval '1 day') SELECT count(*) FROM recent"));
    assertNull(
        problem("SELECT name FROM customers WHERE note = 'call pg_sleep later'"),
        "function names inside a literal are data");
    verdict(
        "TM-04",
        "CVE-2025-66335 (Apache Doris MCP SQLi); CVE-2026-49257 (Apache Pinot MCP, CVSS 10.0); CVE-2026-9680 (Alibaba RDS MCP); Anthropic SQLite server; Supabase/Cursor 2025-07",
        "SQL injection / stacked statements through an MCP database tool",
        "BLOCKED",
        "stacked statements, DML, CTE-DML, INTO, FOR UPDATE, EXPLAIN ANALYZE DML, unterminated quote/comment all denied; benign SELECT/WITH allowed");
  }

  @Test
  void tm04b_sideEffectFunctions_areNowDenied() {
    // BEFORE the fix these passed the keyword guard (the javadoc deferred to a read-only DB role).
    List<String> sideEffects =
        List.of(
            "SELECT pg_terminate_backend(4242)",
            "SELECT lo_import('/etc/passwd')",
            "SELECT setval('orders_id_seq', 1)",
            "SELECT dblink_exec('dbname=prod', 'DELETE FROM customers')",
            "SELECT pg_sleep(30)",
            "SELECT nextval('seq')",
            "SELECT pg_read_file('/etc/passwd')",
            "SELECT load_file('/etc/passwd')",
            "SELECT sleep(10)",
            "SELECT 1 FROM openrowset('SQLOLEDB', 'x', 'y')",
            "SELECT utl_http.request('http://attacker.example') FROM dual",
            "SELECT set_config('search_path', 'evil', false)");
    for (String sql : sideEffects) {
      assertNotNull(problem(sql), "should be denied: " + sql);
    }
    // Token match is on whole words: identifiers that merely contain a function name are not
    // affected.
    assertNull(problem("SELECT customer_id, sleep_minutes FROM sleep_log"));
    verdict(
        "TM-04b",
        "same CVE class as TM-04 (side-effecting SELECT)",
        "write / file read / signal / remote SQL via SELECT-callable functions",
        "FIXED",
        "SIDE_EFFECT_FUNCTIONS deny list (PostgreSQL, MySQL, SQL Server, Oracle, SQLite, Hive) applied to every token; read-only DB role remains the primary control");
  }

  @Test
  void open06_dollarQuotesAndNestedCommentsAreParsedLikeTheDatabase() {
    // Dollar-quoted literals: a ';' or a keyword inside is data, not SQL.
    assertNull(problem("SELECT $$a; b$$ AS note FROM t"));
    assertNull(problem("SELECT $q$it's; DELETE$q$ FROM t"));
    assertNull(
        problem("SELECT $1, $2 FROM t WHERE id = $3"),
        "positional parameters are not dollar quotes");
    assertNotNull(problem("SELECT $$unterminated"), "an unterminated dollar quote fails closed");
    // Nested block comments end where PostgreSQL ends them, not at the first '*/'.
    assertNull(problem("SELECT 1 /* outer /* inner */ still comment */ FROM t"));
    assertNotNull(
        problem("SELECT 1 /* outer /* inner */ unterminated"), "unbalanced nesting fails closed");
    // And a write hidden after a nested comment is still seen, because the database would run it
    // too.
    assertNotNull(problem("SELECT 1 /* a /* b */ c */; DELETE FROM t"));
    verdict(
        "OPEN-06",
        "review finding (SqlReadOnlyHook tokenizer)",
        "dollar-quoted strings and nested block comments",
        "FIXED",
        "stripCommentsAndQuoted handles $tag$...$tag$ literals and nested /* */ depth; unterminated forms fail closed");
  }

  // ------------------------------------------------------------------ CLOSE-02 unknown functions

  @Test
  void close02_unknownFunction_passesDenyListButIsRefusedByAllowList() {
    // The deny list cannot know a user-defined or vendor function. This is the gap the third pass
    // closes.
    String udf = "SELECT write_to_s3(name, 'bucket') FROM customers";
    String vendor = "SELECT pg_catalog.pg_read_server_files_v2('/etc/passwd')";
    assertNull(problem(udf), "baseline: an unknown UDF passes the deny list");
    assertNull(problem(vendor), "baseline: an unknown vendor function passes the deny list");

    java.util.Set<String> allowed =
        new java.util.HashSet<>(SqlReadOnlyHook.DEFAULT_ALLOWED_FUNCTIONS);
    allowed.add("MY_SAFE_FN");
    assertNotNull(
        SqlReadOnlyHook.firstProblem(udf, 8192, allowed), "DENY_UNLISTED refuses the UDF");
    assertNotNull(
        SqlReadOnlyHook.firstProblem(vendor, 8192, allowed),
        "qualified name resolves to its last segment");
    String why = SqlReadOnlyHook.firstProblem(udf, 8192, allowed);
    org.junit.jupiter.api.Assertions.assertTrue(
        why.contains("WRITE_TO_S3") && why.contains("DENY_UNLISTED"), why);

    // Ordinary analytics still pass: aggregates, casts, dates, window functions, IN (...) and WITH
    // ... AS (...).
    List<String> benign =
        List.of(
            "SELECT count(*), max(amount), lower(region) FROM orders WHERE id IN (1, 2, 3) GROUP BY region",
            "WITH recent AS (SELECT * FROM orders WHERE ts > now() - interval '1 day') SELECT cast(sum(amount) as decimal) FROM recent",
            "SELECT row_number() OVER (PARTITION BY region ORDER BY ts) FROM orders",
            "SELECT my_safe_fn(x) FROM t",
            "SELECT date_trunc('day', ts), coalesce(note, '') FROM t WHERE EXISTS (SELECT 1 FROM u)");
    for (String sql : benign) {
      assertNull(SqlReadOnlyHook.firstProblem(sql, 8192, allowed), "should pass: " + sql);
    }
    // The allow list cannot re-admit a known side-effecting function.
    SqlReadOnlyHook hook =
        new SqlReadOnlyHook(
            "sql-readonly", java.util.Set.of("sql"), 8192, java.util.Set.of("PG_SLEEP", "COUNT"));
    org.junit.jupiter.api.Assertions.assertTrue(hook.deniesUnlistedFunctions());
    io.github.vaquarkhan.aegis.engine.model.Action action =
        new io.github.vaquarkhan.aegis.engine.model.Action(
            "query_sql",
            io.github.vaquarkhan.aegis.engine.model.ToolClass.READ,
            "src",
            List.of(),
            "sha256:ab");
    io.github.vaquarkhan.aegis.engine.spi.GovernanceHook.Context ctx =
        new io.github.vaquarkhan.aegis.engine.spi.GovernanceHook.Context(
            "req_1",
            "4bf92f3577b34da6a3ce929d0e0e4736",
            io.github.vaquarkhan.aegis.engine.model.CallerIdentity.of(
                "a", java.util.Set.of("analysts"), "idp", "jwt"),
            io.github.vaquarkhan.aegis.engine.spi.GovernanceHook.Operation.TOOL_CALL,
            "query_sql",
            null,
            "src",
            action,
            java.util.Map.of("sql", "SELECT pg_sleep(30)"),
            false);
    org.junit.jupiter.api.Assertions.assertFalse(
        hook.before(ctx).allowed(), "PG_SLEEP stays denied even when listed");
    verdict(
        "CLOSE-02",
        "CVE-2025-66335 / CVE-2026-49257 class (side effects through a READ SQL tool); THREAT-MODEL 4.1 PARTIAL row",
        "SELECT that calls an unknown or user-defined function",
        "FIXED",
        "functions: DENY_UNLISTED refuses every call not on DEFAULT_ALLOWED_FUNCTIONS + allowFunctions; deny list still applies");
  }
}
