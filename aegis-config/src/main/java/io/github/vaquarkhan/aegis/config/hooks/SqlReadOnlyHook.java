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

import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses a statement argument that is not a single read-only SQL statement.
 *
 * <p><b>Why this exists.</b> A tool that forwards free text to a SQL endpoint cannot be classified by
 * reading its configuration: {@code POST /sql} with {@code select 1} is a read, and the same tool with
 * {@code drop table orders} is not. Without a guard, a tool named {@code run_sql_readonly} classified
 * {@code READ} needs no write group, no approval and no dry-run receipt, so the one control that
 * matters for it does not exist. Aegis 0.1 had a {@code ReadOnlyGuard} SPI for exactly this; this hook
 * is its replacement, and it is the reason those tools may stay {@code READ}.
 *
 * <p><b>What it actually checks.</b> Comments and quoted text are removed first, so a keyword hidden
 * in a literal or a comment cannot pass and a keyword inside a quoted identifier cannot trip it. To
 * stay safe across dialects the stripper uses only portable rules: {@code --} and {@code /* *}{@code /}
 * comments (never {@code #}, which is an operator in some dialects), and standard-SQL string literals
 * where a quote is escaped only by doubling it (never by a backslash, which does not escape under
 * {@code standard_conforming_strings}). A statement that relies on backslash escaping or {@code #}
 * comments is refused rather than reinterpreted, so a second statement cannot hide behind a
 * dialect difference. Then:
 * <ul>
 *   <li>exactly one statement (a second non-empty statement after a {@code ;} is refused);</li>
 *   <li>the first keyword is one of {@code SELECT}, {@code WITH}, {@code SHOW}, {@code EXPLAIN},
 *       {@code DESCRIBE}, {@code DESC}, {@code VALUES}, {@code TABLE};</li>
 *   <li>no token anywhere is a writing or session-changing keyword ({@code INSERT}, {@code UPDATE},
 *       {@code DELETE}, {@code DROP}, {@code CREATE}, {@code ALTER}, {@code TRUNCATE}, {@code MERGE},
 *       {@code GRANT}, {@code CALL}, {@code SET}, {@code COMMIT}, {@code INTO}, and the rest of
 *       {@link #WRITING_KEYWORDS});</li>
 *   <li>the statement is not longer than the configured bound, and unterminated quotes or comments are
 *       refused rather than guessed at.</li>
 * </ul>
 *
 * <p><b>What it does not do.</b> This is a keyword and shape guard, not a SQL parser,
 * and it is deliberately dialect-agnostic. A statement that passes is <b>not proven</b> read-only:
 * {@code SELECT my_function()} passes, and in most engines a user-defined function can write. So:
 * <ul>
 *   <li>the real control is a <b>read-only credential</b> on the source. Aegis resolves backend
 *       credentials per source, so give a SQL source a role that cannot write and the engine enforces
 *       what this hook can only indicate;</li>
 *   <li>an operator who wants certainty rather than indication classifies the tool {@code MUTATE},
 *       which means every query needs an approval.</li>
 * </ul>
 * Those two options are the real controls; this hook narrows the gap between them.
 */
public final class SqlReadOnlyHook implements GovernanceHook {

    /** A statement may start with one of these. */
    static final Set<String> READING_FIRST_KEYWORDS =
            Set.of("SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "VALUES", "TABLE");

    /** No token anywhere may be one of these. */
    static final Set<String> WRITING_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "TRUNCATE", "MERGE", "REPLACE",
            "UPSERT", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "SET", "RESET", "COPY", "LOAD",
            "UNLOAD", "IMPORT", "EXPORT", "RENAME", "COMMENT", "LOCK", "UNLOCK", "COMMIT", "ROLLBACK",
            "BEGIN", "START", "SAVEPOINT", "ATTACH", "DETACH", "PRAGMA", "VACUUM", "ANALYZE", "OPTIMIZE",
            "REFRESH", "INVALIDATE", "MSCK", "KILL", "SHUTDOWN", "INTO", "OVERWRITE", "PREPARE",
            "DEALLOCATE", "DECLARE", "FETCH", "CLOSE", "USE");

    private final String name;
    private final Set<String> arguments;
    private final int maxChars;

    public SqlReadOnlyHook(String name, Set<String> arguments, int maxChars) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a sql-readonly hook needs a name");
        }
        if (arguments == null || arguments.isEmpty()) {
            throw new IllegalArgumentException("a sql-readonly hook needs at least one argument name to check");
        }
        this.name = name;
        this.arguments = Set.copyOf(arguments);
        this.maxChars = maxChars <= 0 ? 8192 : maxChars;
    }

    @Override
    public String name() {
        return name;
    }

    public Set<String> arguments() {
        return arguments;
    }

    @Override
    public Decision before(Context context) {
        boolean sawOne = false;
        for (String arg : arguments) {
            Object v = context.arguments().get(arg);
            if (v == null) {
                continue;
            }
            sawOne = true;
            if (!(v instanceof String s)) {
                return deny("argument '" + arg + "' must be a string containing one SQL statement");
            }
            String why = firstProblem(s, maxChars);
            if (why != null) {
                return deny("argument '" + arg + "' is not a read-only statement: " + why);
            }
        }
        if (!sawOne) {
            // The hook is bound to a tool that carries none of the arguments it was told to check, so it
            // would silently pass everything. That is a misconfiguration, and a guard that does nothing
            // is worse than no guard because it reads like protection.
            return deny("none of the checked arguments " + arguments + " is present on this call");
        }
        return Decision.allow();
    }

    private Decision deny(String reason) {
        return Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_INJECTION, reason);
    }

    /**
     * Classifies one statement.
     *
     * <p>Public because it is the project's single SQL read-only classifier and the JDBC source
     * ({@code aegis-source-jdbc}) applies the same rule inside its own executor, as defence in depth
     * for deployments that forget to bind this hook. One implementation, two callers; a second copy
     * would drift.
     *
     * @return null when the statement looks read-only, else the reason it does not
     */
    public static String firstProblem(String sql, int maxChars) {
        String why = singleStatementProblem(sql, maxChars);
        if (why != null) {
            return why;
        }
        List<String> tokens = tokens(onlyStatement(stripCommentsAndQuoted(sql)));
        if (tokens.isEmpty()) {
            return "the statement has no SQL keyword in it";
        }
        if (!READING_FIRST_KEYWORDS.contains(tokens.get(0))) {
            return "it starts with '" + tokens.get(0) + "', and a read-only statement must start with one of "
                    + new java.util.TreeSet<>(READING_FIRST_KEYWORDS);
        }
        for (String t : tokens) {
            if (WRITING_KEYWORDS.contains(t)) {
                return "it contains the keyword '" + t + "'";
            }
        }
        return null;
    }

    /**
     * Checks only the shape: one non-empty statement within the bound, with no unterminated quote or
     * comment. A {@code ;} inside a literal, a quoted identifier or a comment does not count, and one
     * trailing {@code ;} is allowed. Used on its own by tools that are meant to write (so the read-only
     * keyword rules do not apply) but must still run exactly one statement per approved call.
     *
     * @return null when the text is exactly one statement, else the reason it is not
     */
    public static String singleStatementProblem(String sql, int maxChars) {
        if (sql == null || sql.isBlank()) {
            return "the statement is empty";
        }
        if (sql.length() > maxChars) {
            return "the statement is longer than the configured limit of " + maxChars + " characters";
        }
        String bare;
        try {
            bare = stripCommentsAndQuoted(sql);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        int count = statements(bare).size();
        if (count == 0) {
            return "the statement has no SQL in it";
        }
        if (count > 1) {
            return "more than one statement was supplied";
        }
        return null;
    }

    private static List<String> statements(String bare) {
        List<String> statements = new java.util.ArrayList<>();
        for (String part : bare.split(";", -1)) {
            if (!part.isBlank()) {
                statements.add(part);
            }
        }
        return statements;
    }

    private static String onlyStatement(String bare) {
        return statements(bare).get(0);
    }

    /**
     * Removes comments and replaces quoted text with a harmless placeholder, so keyword matching sees
     * only SQL structure. An unterminated quote or comment is an error rather than a guess.
     */
    static String stripCommentsAndQuoted(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("it contains an unterminated block comment");
                }
                i = end + 2;
                out.append(' ');
            } else if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == quote) {
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2; // doubled quote is an escaped quote
                            continue;
                        }
                        i++;
                        closed = true;
                        break;
                    }
                    i++;
                }
                if (!closed) {
                    throw new IllegalArgumentException("it contains an unterminated quoted value");
                }
                // A literal or quoted identifier becomes a placeholder: its contents must not be read as SQL.
                out.append(quote == '\'' ? " 'x' " : " qx ");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Upper-case word tokens, in order. */
    static List<String> tokens(String sql) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder w = new StringBuilder();
        for (int i = 0; i <= sql.length(); i++) {
            char c = i < sql.length() ? sql.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c) || c == '_') {
                w.append(c);
            } else if (w.length() > 0) {
                out.add(w.toString().toUpperCase(Locale.ROOT));
                w.setLength(0);
            }
        }
        return out;
    }
}
