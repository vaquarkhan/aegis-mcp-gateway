package io.github.vaquarkhan.aegis.core.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Argument validation helpers shared by every adapter.
 *
 * <p>{@link InvalidInput} is the marker the interceptor chain uses to classify a failure as a
 * caller error ({@code INVALID_INPUT}) rather than a backend error. Caller errors never trip the
 * circuit breaker, so one misbehaving client cannot deny a tool for everyone else.
 *
 * @author Viquar Khan
 */
public final class Inputs {

    /**
     * Thrown for any argument the gateway refuses to forward to a backend.
     *
     * @author Viquar Khan
     */
    public static final class InvalidInput extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public InvalidInput(String message) {
            super(message);
        }
    }

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,256}");
    private static final Pattern INT = Pattern.compile("[0-9]{1,9}");
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,249}");
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9_]([A-Za-z0-9._-]{0,126}[A-Za-z0-9_])?");
    private static final Pattern TABLE = Pattern.compile("[A-Za-z0-9_]([A-Za-z0-9._-]{0,126}[A-Za-z0-9_])?");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9._*:+/=@-]{1,1024}");

    private Inputs() {}

    /** Generic backend identifier: job id, application id, jar id, snapshot id. */
    public static String requireId(String id) {
        if (id == null || !ID.matcher(id).matches() || id.contains("..")) {
            throw new InvalidInput("invalid id: " + id);
        }
        return id;
    }

    /**
     * HTTP or filesystem style path segment(s). Allows {@code /} but rejects {@code ..} and control
     * characters so adapters can safely interpolate into URL paths.
     */
    public static String requirePath(String path) {
        if (path == null || path.isBlank() || path.contains("..") || !PATH.matcher(path).matches()) {
            throw new InvalidInput("invalid path: " + path);
        }
        return path;
    }

    /** Non-negative integer supplied as a string, bounded to nine digits. */
    public static String requireInt(String value) {
        if (value == null || !INT.matcher(value).matches()) {
            throw new InvalidInput("invalid int: " + value);
        }
        return value;
    }

    /**
     * Kafka topic name. Rejects the reserved single-dot and double-dot forms that some brokers
     * treat as path traversal in their log directory layout.
     */
    public static String requireTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new InvalidInput("topic required");
        }
        if (".".equals(topic) || "..".equals(topic) || topic.contains("..")) {
            throw new InvalidInput("invalid topic: " + topic);
        }
        if (!TOPIC.matcher(topic).matches()) {
            throw new InvalidInput("invalid topic: " + topic);
        }
        return topic;
    }

    /** Catalog namespace, optionally dotted, as used by Iceberg and Spark catalogs. */
    public static String requireNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new InvalidInput("namespace required");
        }
        if (namespace.contains("..") || !NAMESPACE.matcher(namespace).matches()) {
            throw new InvalidInput("invalid namespace: " + namespace);
        }
        return namespace;
    }

    /** Table identifier, optionally namespace qualified. */
    public static String requireTable(String table) {
        if (table == null || table.isBlank()) {
            throw new InvalidInput("table required");
        }
        if (table.contains("..") || !TABLE.matcher(table).matches()) {
            throw new InvalidInput("invalid table: " + table);
        }
        return table;
    }

    /**
     * Resolves {@code path} and requires it to be a regular file under one of the allow-listed
     * directories. An empty allow list rejects everything, which keeps artifact upload fail-closed
     * until an operator opts in.
     */
    public static Path requireJarPath(String path, Set<String> allowDirs) {
        if (path == null || path.isBlank()) {
            throw new InvalidInput("jar path required");
        }
        if (allowDirs == null || allowDirs.isEmpty()) {
            throw new InvalidInput("jar upload directories not configured (MCP_GW_JAR_UPLOAD_ALLOW_DIRS)");
        }
        try {
            Path resolved = Path.of(path).toAbsolutePath().normalize();
            if (!Files.isRegularFile(resolved)) {
                throw new InvalidInput("jar path is not a regular file");
            }
            boolean ok = false;
            for (String dir : allowDirs) {
                Path root = Path.of(dir).toAbsolutePath().normalize();
                if (resolved.startsWith(root)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new InvalidInput("jar path outside allow-listed directories");
            }
            return resolved;
        } catch (InvalidInput e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidInput("invalid jar path: " + e.getMessage());
        }
    }

    /** Bounded SQL text. Length bounding happens before any parsing to limit guard cost. */
    public static String requireSql(String sql, int maxChars) {
        if (sql == null || sql.isBlank()) {
            throw new InvalidInput("sql required");
        }
        if (sql.length() > maxChars) {
            throw new InvalidInput("sql exceeds max length " + maxChars);
        }
        return sql;
    }

    public static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
