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

    /**
     * Functions that write, read the server's filesystem, signal other sessions, run remote SQL or stall
     * the server, yet are callable from a plain {@code SELECT}. A statement that names one of them is not
     * read-only whatever its first keyword says. The list is a second tripwire; the javadoc's point stands
     * that a read-only database role is the real control, because any user-defined function can write.
     */
    static final Set<String> SIDE_EFFECT_FUNCTIONS = Set.of(
            // PostgreSQL: sequences, large objects, server files, signals, config, remote SQL, sleep
            "NEXTVAL", "SETVAL", "LO_IMPORT", "LO_EXPORT", "LO_UNLINK", "LO_CREATE", "LO_CREAT", "LO_FROM_BYTEA", "LO_PUT",
            "PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_LS_DIR", "PG_STAT_FILE", "PG_FILE_WRITE", "PG_FILE_UNLINK", "PG_FILE_RENAME",
            "PG_TERMINATE_BACKEND", "PG_CANCEL_BACKEND", "PG_RELOAD_CONF", "PG_ROTATE_LOGFILE", "PG_SWITCH_WAL",
            "PG_CREATE_RESTORE_POINT", "PG_PROMOTE", "PG_START_BACKUP", "PG_STOP_BACKUP", "PG_LOGICAL_EMIT_MESSAGE",
            "PG_ADVISORY_LOCK", "PG_ADVISORY_XACT_LOCK", "PG_TRY_ADVISORY_LOCK", "PG_NOTIFY", "SET_CONFIG",
            "PG_SLEEP", "PG_SLEEP_FOR", "PG_SLEEP_UNTIL",
            "DBLINK", "DBLINK_EXEC", "DBLINK_CONNECT", "DBLINK_CONNECT_U", "DBLINK_SEND_QUERY", "DBLINK_OPEN", "DBLINK_FETCH",
            "QUERY_TO_XML", "QUERY_TO_XML_AND_XMLSCHEMA", "QUERY_TO_XMLSCHEMA", "XPATH_TABLE",
            // MySQL / MariaDB: files, UDF shells, timing
            "LOAD_FILE", "SYS_EXEC", "SYS_EVAL", "SYS_SET", "SLEEP", "BENCHMARK", "MASTER_POS_WAIT", "SOURCE_POS_WAIT", "GET_LOCK",
            // SQL Server: shell, dynamic SQL, linked servers, filesystem
            "XP_CMDSHELL", "SP_EXECUTESQL", "SP_OACREATE", "SP_OAMETHOD", "SP_ADDEXTENDEDPROC", "OPENROWSET", "OPENDATASOURCE",
            "OPENQUERY", "XP_DIRTREE", "XP_FILEEXIST", "XP_REGREAD", "XP_REGWRITE", "WAITFOR",
            // Oracle: network, filesystem, dynamic SQL, scheduler
            "UTL_HTTP", "UTL_FILE", "UTL_INADDR", "UTL_TCP", "UTL_SMTP", "DBMS_SQL", "DBMS_SCHEDULER", "DBMS_LOCK", "DBMS_PIPE",
            "DBMS_XMLGEN", "DBMS_LDAP", "DBMS_JAVA", "DBMS_OUTPUT", "DBMS_ADVISOR",
            // SQLite: filesystem and extension loading
            "READFILE", "WRITEFILE", "LOAD_EXTENSION",
            // Hive / Spark style reflection into arbitrary Java
            "REFLECT", "JAVA_METHOD");

    /**
     * Read-only built-ins that {@code functions: DENY_UNLISTED} allows without being named: aggregates,
     * string, numeric, date and window functions found in every SQL dialect. Nothing here writes, reads
     * the filesystem, opens a connection or sleeps. Operators extend the list with {@code allowFunctions}.
     */
    public static final Set<String> DEFAULT_ALLOWED_FUNCTIONS = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "COUNT_IF", "ANY_VALUE", "STRING_AGG", "GROUP_CONCAT", "ARRAY_AGG",
            "CAST", "TRY_CAST", "CONVERT", "COALESCE", "NULLIF", "IFNULL", "NVL", "IF", "IIF", "GREATEST", "LEAST",
            "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM", "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH", "SUBSTR", "SUBSTRING",
            "CONCAT", "CONCAT_WS", "REPLACE", "LEFT", "RIGHT", "LPAD", "RPAD", "REVERSE", "POSITION", "INSTR", "STRPOS",
            "SPLIT_PART", "REGEXP_REPLACE", "REGEXP_EXTRACT", "REGEXP_LIKE", "INITCAP", "FORMAT", "TO_CHAR", "TO_DATE",
            "TO_TIMESTAMP", "TO_NUMBER",
            "ROUND", "FLOOR", "CEIL", "CEILING", "ABS", "MOD", "POWER", "SQRT", "EXP", "LN", "LOG", "LOG10", "SIGN", "TRUNC",
            "TRUNCATE", "RANDOM", "RAND",
            "NOW", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIMESTAMP", "DATE", "TIME", "TIMESTAMP",
            "DATE_TRUNC", "DATE_PART", "DATE_ADD", "DATE_SUB", "DATEADD", "DATEDIFF", "DATE_DIFF", "EXTRACT", "YEAR", "MONTH",
            "DAY", "HOUR", "MINUTE", "SECOND", "DAYOFWEEK", "AGE", "MAKE_DATE", "FROM_UNIXTIME", "UNIX_TIMESTAMP",
            "ROW_NUMBER", "RANK", "DENSE_RANK", "PERCENT_RANK", "CUME_DIST", "NTILE", "LAG", "LEAD", "FIRST_VALUE",
            "LAST_VALUE", "NTH_VALUE",
            "MD5", "SHA1", "SHA2", "SHA256", "HEX", "UNHEX", "TO_HEX", "ENCODE", "DECODE", "LOWER_HEX",
            "JSON_EXTRACT", "JSON_VALUE", "JSON_QUERY", "JSON_EXTRACT_SCALAR", "GET_JSON_OBJECT", "JSONB_EXTRACT_PATH",
            "ARRAY_LENGTH", "CARDINALITY", "UNNEST", "ARRAY_CONTAINS", "ELEMENT_AT", "MAP_KEYS", "MAP_VALUES");

    /**
     * Words that precede a parenthesis without being a function call. Excluded from function detection
     * so {@code x IN (1, 2)} and {@code WITH t AS (SELECT ...)} do not read as calls to IN or AS.
     */
    static final Set<String> NOT_FUNCTIONS = Set.of(
            "IN", "EXISTS", "VALUES", "AND", "OR", "NOT", "ON", "OVER", "AS", "FROM", "WHERE", "JOIN", "SELECT", "HAVING",
            "THEN", "ELSE", "WHEN", "BETWEEN", "LIKE", "ILIKE", "ANY", "ALL", "SOME", "UNION", "INTERSECT", "EXCEPT",
            "LATERAL", "FILTER", "WITHIN", "GROUP", "PARTITION", "DISTINCT", "ROW", "TABLE", "WITH", "USING", "ARRAY",
            "INTERVAL", "PRECEDING", "FOLLOWING", "IS", "ESCAPE", "OFFSET", "LIMIT", "BY", "ORDER", "CASE", "END",
            "INNER", "OUTER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "TOP", "QUALIFY", "WINDOW", "ROWS", "RANGE",
            "EXPLAIN", "DESCRIBE", "SHOW", "DESC");

    private final String name;
    private final Set<String> arguments;
    private final int maxChars;
    /** Null in the default list mode; otherwise the only function names a statement may call. */
    private final Set<String> allowedFunctions;

    public SqlReadOnlyHook(String name, Set<String> arguments, int maxChars) {
        this(name, arguments, maxChars, null);
    }

    /**
     * @param allowedFunctions when non-null, {@code functions: DENY_UNLISTED}: every called function must be
     *                         in this set (the caller adds {@link #DEFAULT_ALLOWED_FUNCTIONS} if wanted).
     *                         When null, the default deny-list mode applies.
     */
    public SqlReadOnlyHook(String name, Set<String> arguments, int maxChars, Set<String> allowedFunctions) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a sql-readonly hook needs a name");
        }
        if (arguments == null || arguments.isEmpty()) {
            throw new IllegalArgumentException("a sql-readonly hook needs at least one argument name to check");
        }
        this.name = name;
        this.arguments = Set.copyOf(arguments);
        this.maxChars = maxChars <= 0 ? 8192 : maxChars;
        Set<String> upper = null;
        if (allowedFunctions != null) {
            upper = new java.util.HashSet<>();
            for (String f : allowedFunctions) {
                upper.add(f.toUpperCase(Locale.ROOT));
            }
            // The allow list cannot re-admit a known side-effecting function: the two controls stack.
            upper.removeAll(SIDE_EFFECT_FUNCTIONS);
            upper = Set.copyOf(upper);
        }
        this.allowedFunctions = upper;
    }

    /** True when every called function must be on the configured allow list. */
    public boolean deniesUnlistedFunctions() {
        return allowedFunctions != null;
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
            String why = firstProblem(s, maxChars, allowedFunctions);
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
        return firstProblem(sql, maxChars, null);
    }

    /**
     * As {@link #firstProblem(String, int)}, plus an allow list of callable functions. With a non-null
     * list, any {@code name(} in the statement whose name is not on it is refused, which closes the gap
     * the deny list leaves open: a user-defined or dialect-specific function nobody has heard of.
     */
    public static String firstProblem(String sql, int maxChars, Set<String> allowedFunctions) {
        String why = singleStatementProblem(sql, maxChars);
        if (why != null) {
            return why;
        }
        String bare = onlyStatement(stripCommentsAndQuoted(sql));
        List<String> tokens = tokens(bare);
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
            if (SIDE_EFFECT_FUNCTIONS.contains(t)) {
                return "it calls '" + t + "', which has side effects a read-only statement must not have";
            }
        }
        if (allowedFunctions != null) {
            for (String f : calledFunctions(bare)) {
                if (!allowedFunctions.contains(f)) {
                    return "it calls '" + f + "', which is not on the allowed function list (functions: DENY_UNLISTED)";
                }
            }
        }
        return null;
    }

    /**
     * Upper-case names of every {@code name(} call in a bare statement (comments and literals already
     * removed), in order, excluding SQL words that take a parenthesis without being a call. A qualified
     * name such as {@code pg_catalog.pg_sleep(} reports its last segment, which is what the lists hold.
     */
    static List<String> calledFunctions(String bare) {
        List<String> out = new java.util.ArrayList<>();
        int n = bare.length();
        int i = 0;
        while (i < n) {
            char c = bare.charAt(i);
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(bare.charAt(i)) || bare.charAt(i) == '_' || bare.charAt(i) == '.')) {
                    i++;
                }
                String word = bare.substring(start, i);
                int j = i;
                while (j < n && Character.isWhitespace(bare.charAt(j))) {
                    j++;
                }
                if (j < n && bare.charAt(j) == '(') {
                    int dot = word.lastIndexOf('.');
                    String fn = (dot >= 0 ? word.substring(dot + 1) : word).toUpperCase(Locale.ROOT);
                    if (!fn.isEmpty() && !NOT_FUNCTIONS.contains(fn)) {
                        out.add(fn);
                    }
                }
            } else {
                i++;
            }
        }
        return out;
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
     *
     * <p>Block comments are treated as NESTING, as PostgreSQL does: {@code /* a /* b *}{@code / c *}{@code /}
     * is one comment. A parser that ended at the first {@code *}{@code /} would read {@code c} as SQL that
     * the database never executes, and a parser that did not nest would hide SQL the database does execute
     * (the inverse mistake). Dollar-quoted strings ({@code $$...$$}, {@code $tag$...$tag$}) are literals,
     * so a {@code ;} or a keyword inside them is not a second statement or a write.
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
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (sql.charAt(i) == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                        depth++;
                        i += 2;
                    } else if (sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                if (depth > 0) {
                    throw new IllegalArgumentException("it contains an unterminated block comment");
                }
                out.append(' ');
            } else if (c == '$' && dollarTagEnd(sql, i) > 0) {
                int tagEnd = dollarTagEnd(sql, i);
                String tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                if (close < 0) {
                    throw new IllegalArgumentException("it contains an unterminated dollar-quoted value");
                }
                i = close + tag.length();
                out.append(" 'x' ");
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

    /**
     * If a dollar-quote opener ({@code $$} or {@code $tag$} with a tag of letters, digits and underscores
     * not starting with a digit) begins at {@code at}, returns the index just past it; else 0. A lone
     * {@code $1} positional parameter is not an opener.
     */
    private static int dollarTagEnd(String sql, int at) {
        int j = at + 1;
        int n = sql.length();
        if (j < n && Character.isDigit(sql.charAt(j))) {
            return 0;
        }
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
        }
        return j < n && sql.charAt(j) == '$' ? j + 1 : 0;
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
