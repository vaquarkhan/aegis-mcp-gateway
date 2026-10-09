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
package io.github.vaquarkhan.aegis.config.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The read-only statement guard. This exists because a tool that forwards free text to a SQL endpoint
 * cannot be classified from its configuration, so without this guard a {@code READ} tool called
 * {@code run_sql_readonly} would carry no control at all.
 */
class SqlReadOnlyHookTest {

    static String problem(String sql) {
        return SqlReadOnlyHook.firstProblem(sql, 8192);
    }

    @Test
    void readOnlyStatementsPass() {
        for (String ok : new String[] {
                "select 1",
                "SELECT * FROM orders WHERE id = 7",
                "  select\n  count(*)\n  from sales.orders  ",
                "with recent as (select * from orders) select * from recent",
                "show tables",
                "explain select * from orders",
                "describe orders",
                "desc orders",
                "values (1), (2)",
                "table orders",
                "select * from orders order by created desc",
                "select * from orders limit 10 offset 20",          // OFFSET must not match SET
                "select 'drop table orders' as note",               // a keyword inside a literal is text
                "select * from \"update\"",                          // a keyword as a quoted identifier
                "select * from `delete`",
                "select 1 -- drop table orders",                     // line comment
                "select 1 /* insert into x */",                      // block comment
                "select 1;",                                         // a single trailing semicolon is fine
                "select 'it''s fine'",                               // doubled quote escape (portable)
                "select * from t where s = 'a''b'"}) {               // standard-SQL doubled-quote escape
            assertNull(problem(ok), ok);
        }
    }

    @Test
    void dialectSpecificCommentOrEscapeCannotHideASecondStatement() {
        // These pass only if the stripper assumes MySQL semantics (# comment, backslash escape). On a
        // PostgreSQL-dialect source they are two statements, the second a write, so they must be refused.
        for (String bad : new String[] {
                "select 1 # 1; drop table t",                        // # is an operator, not a comment
                "select 1 #comment\n; drop table t",
                "select 'a\\' ; drop table t; select 'b' --'",       // backslash does not escape the quote
                "select * from t where s = 'a\\'; delete from t"}) {
            assertNotNull(problem(bad), "dialect bypass should be refused: " + bad);
        }
    }

    @Test
    void statementsThatWriteOrChangeSessionStateAreRefused() {
        for (String bad : new String[] {
                "drop table orders",
                "DROP TABLE orders",
                "delete from orders",
                "update orders set total = 0",
                "insert into orders values (1)",
                "truncate table orders",
                "alter table orders add column x int",
                "create table t as select 1",
                "merge into orders using s on true",
                "grant select on orders to bob",
                "call my_procedure()",
                "set time zone 'UTC'",
                "commit",
                "begin",
                "vacuum",
                "analyze table orders",
                "refresh table orders",
                "msck repair table orders",
                "use other_db",
                "select * into outfile '/tmp/x' from orders",        // INTO
                "insert overwrite table t select 1",
                "select 1; drop table orders",                        // second statement
                "select 1;;drop table orders",
                "select 1 /* unterminated",                           // fail closed, do not guess
                "select 'unterminated",
                "",
                "   ",
                "1 + 1",                                              // no leading keyword
                "with x as (select 1) insert into t select * from x"}) {
            assertNotNull(problem(bad), "should have been refused: " + bad);
        }
    }

    @Test
    void lengthIsBounded() {
        assertNull(SqlReadOnlyHook.firstProblem("select 1", 8));
        assertTrue(SqlReadOnlyHook.firstProblem("select 1234", 8).contains("longer than"));
    }

    @Test
    void theHookDeniesWithInvalidInputAndRefusesToBeAnEmptyGuard() {
        SqlReadOnlyHook h = new SqlReadOnlyHook("sql", Set.of("sql"), 8192);
        assertEquals("sql", h.name());
        assertEquals(Set.of("sql"), h.arguments());

        assertTrue(h.before(context(Map.of("sql", "select 1"))).allowed());

        Decision d = h.before(context(Map.of("sql", "drop table orders")));
        assertEquals(DenyCodes.INVALID_INPUT, d.code());
        assertEquals(DenyCodes.STEP_INJECTION, d.step());
        assertTrue(d.reason().contains("DROP"), d.reason());

        // A non-string statement is refused rather than stringified.
        assertTrue(h.before(context(Map.of("sql", 7L))).denied());

        // Bound to a call that carries none of its arguments, the guard would pass everything. That is a
        // misconfiguration and it denies instead, because a guard that checks nothing reads like protection.
        Decision none = h.before(context(Map.of("table", "orders")));
        assertTrue(none.denied());
        assertTrue(none.reason().contains("is present on this call"), none.reason());
    }

    @Test
    void constructorRejectsAGuardThatWouldCheckNothing() {
        assertThrows(IllegalArgumentException.class, () -> new SqlReadOnlyHook("x", Set.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new SqlReadOnlyHook("x", null, 0));
        assertThrows(IllegalArgumentException.class, () -> new SqlReadOnlyHook(" ", Set.of("sql"), 0));
    }

    @Test
    void quotedTextAndCommentsAreNeutralisedNotDeleted() {
        // The placeholder keeps tokens apart: "select'drop'" must not become "selectdrop".
        assertTrue(SqlReadOnlyHook.tokens(SqlReadOnlyHook.stripCommentsAndQuoted("select'x'from t"))
                .containsAll(List.of("SELECT", "FROM", "T")));
        assertTrue(SqlReadOnlyHook.stripCommentsAndQuoted("select 1 -- drop\nfrom t").contains("from t"));
        assertThrows(IllegalArgumentException.class, () -> SqlReadOnlyHook.stripCommentsAndQuoted("select /* x"));
        assertThrows(IllegalArgumentException.class, () -> SqlReadOnlyHook.stripCommentsAndQuoted("select 'x"));
    }

    private static GovernanceHook.Context context(Map<String, Object> args) {
        Action a = new Action("run_sql_readonly", ToolClass.READ, "hive", List.of(), "sha256:ab");
        return new GovernanceHook.Context("req_1", "4bf92f3577b34da6a3ce929d0e0e4736",
                CallerIdentity.of("ana", Set.of("hive-users"), "idp", "jwt"),
                GovernanceHook.Operation.TOOL_CALL, "hive_run_sql_readonly", null, "hive", a, args, false);
    }
}
