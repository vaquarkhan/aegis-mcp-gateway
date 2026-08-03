package io.github.vaquarkhan.aegis.core.governance;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outbound controls: size bounding followed by data loss prevention redaction.
 *
 * <p>Order matters. Bounding runs first so redaction always sees the exact byte range that will be
 * returned. Secrets collapse to {@code <redacted>}. Email addresses (and similar PII shapes) get
 * stable referential placeholders ({@code PERSON_1}, …) so the same value redacts the same way
 * within one process without leaking the original.
 *
 * @author Viquar Khan
 */
public final class OutputControls {

    public static final String REDACTION = "<redacted>";
    public static final String TRUNCATION_MARKER = "...<truncated>";

    private static final Pattern[] SECRET_DLP = {
            Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|token)[\"']?\\s*[=:]\\s*[\"']?[^\"'\\s,}]+"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._-]+"),
            Pattern.compile("(?i)AKIA[0-9A-Z]{16}"),
            Pattern.compile("(?i)aws_secret_access_key[\"']?\\s*[=:]\\s*[\"']?[^\"'\\s,}]+")
    };

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final int maxBytes;
    private final boolean dlpEnabled;
    private final Map<String, String> piiPlaceholders = new LinkedHashMap<>();
    private final AtomicInteger piiSeq = new AtomicInteger();

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
        for (Pattern p : SECRET_DLP) {
            s = p.matcher(s).replaceAll(REDACTION);
        }
        s = referentialReplace(EMAIL, s, "PERSON_");
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

    private String referentialReplace(Pattern pattern, String input, String prefix) {
        Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String value = m.group();
            String placeholder = piiPlaceholders.computeIfAbsent(
                    value.toLowerCase(), k -> prefix + piiSeq.incrementAndGet());
            m.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
