package io.github.vaquarkhan.aegis.core.governance;

import io.github.vaquarkhan.aegis.core.spi.ReadOnlyGuard;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reusable read-only SQL guard shared by every SQL speaking adapter.
 *
 * <p>The guard is an allow list of leading keywords plus a stacked statement rejection. Comments
 * are stripped first so a leading block or line comment cannot hide a mutating statement. {@code
 * WITH} statements are scanned for data-modifying keywords so writable CTEs cannot pass. Anything
 * the guard cannot prove read-only is rejected.
 *
 * @author Viquar Khan
 */
public final class SqlReadonlyGuard implements ReadOnlyGuard {

    private static final String[] ALLOWED = {"SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN"};
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern MUTATING = Pattern.compile(
            "(?i)(?<!\\w)(INSERT|UPDATE|DELETE|MERGE|UPSERT|DROP|CREATE|ALTER|TRUNCATE|GRANT|REVOKE|REPLACE|CALL)(?!\\w)");

    @Override
    public boolean isReadOnly(String statement) {
        if (statement == null) {
            return false;
        }
        String cleaned = BLOCK_COMMENT.matcher(statement).replaceAll(" ");
        cleaned = LINE_COMMENT.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            return false;
        }
        int lastSemi = cleaned.lastIndexOf(';');
        if (lastSemi >= 0) {
            if (cleaned.substring(0, lastSemi).indexOf(';') >= 0) {
                return false;
            }
            if (lastSemi == cleaned.length() - 1) {
                cleaned = cleaned.substring(0, lastSemi).trim();
            } else {
                return false;
            }
        }
        String upper = cleaned.toUpperCase(Locale.ROOT);
        for (String prefix : ALLOWED) {
            if (upper.equals(prefix)) {
                return true;
            }
            if (upper.startsWith(prefix + " ") || upper.startsWith(prefix + "\n")
                    || upper.startsWith(prefix + "\t") || upper.startsWith(prefix + "(")) {
                if ("WITH".equals(prefix) && MUTATING.matcher(cleaned).find()) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }
}
