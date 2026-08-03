package io.github.vaquarkhan.aegis.core.governance;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Outbound controls: size bounding followed by data loss prevention redaction.
 *
 * <p>Order matters. Bounding runs first so redaction always sees the exact byte range that will be
 * returned, and a secret cannot survive by straddling the truncation boundary. The bound is UTF-8
 * bytes to match {@code MCP_GW_MAX_BYTES}.
 *
 * @author Viquar Khan
 */
public final class OutputControls {

    public static final String REDACTION = "<redacted>";
    public static final String TRUNCATION_MARKER = "...<truncated>";

    private static final Pattern[] DLP = {
            Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|token)[\"']?\\s*[=:]\\s*[\"']?[^\"'\\s,}]+"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._-]+"),
            Pattern.compile("(?i)AKIA[0-9A-Z]{16}"),
            Pattern.compile("(?i)aws_secret_access_key[\"']?\\s*[=:]\\s*[\"']?[^\"'\\s,}]+")
    };

    private final int maxBytes;
    private final boolean dlpEnabled;

    public OutputControls(int maxBytes, boolean dlpEnabled) {
        this.maxBytes = maxBytes;
        this.dlpEnabled = dlpEnabled;
    }

    /** Truncates to the configured UTF-8 byte ceiling and appends a marker so truncation is visible. */
    public String bound(String input) {
        String s = input == null ? "" : input;
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= maxBytes) {
            return s;
        }
        int end = maxBytes;
        while (end > 0 && (utf8[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(utf8, 0, end, StandardCharsets.UTF_8) + TRUNCATION_MARKER;
    }

    public String redact(String input) {
        String s = input == null ? "" : input;
        if (!dlpEnabled) {
            return s;
        }
        for (Pattern p : DLP) {
            s = p.matcher(s).replaceAll(REDACTION);
        }
        return s;
    }

    /** Bound first, then redact. This is the entry point the interceptor chain uses. */
    public String boundAndRedact(String input) {
        return redact(bound(input));
    }

    public int maxBytes() {
        return maxBytes;
    }

    public boolean dlpEnabled() {
        return dlpEnabled;
    }
}
