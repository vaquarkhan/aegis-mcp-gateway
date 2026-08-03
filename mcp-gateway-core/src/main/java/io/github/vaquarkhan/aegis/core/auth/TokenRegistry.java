package io.github.vaquarkhan.aegis.core.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Named HTTP callers loaded from {@code MCP_GW_AUTH_TOKENS_FILE}.
 *
 * <p>Line format of LLD section 15 ({@code TOKEN_ENTRY}), colon separated:
 * {@code callerId : sha256Hex(token) : jobsAllowCsv : jarsAllowCsv : readonly
 * [: outboundAuthorizationHeader]}
 *
 * <p>The pre-LLD four field form
 * {@code callerId : sha256Hex(token) : resourceScopesCsv : readonly [: outbound]} is still accepted
 * so existing token files keep working; its single scope list becomes the resource scopes, the job
 * allow list and the jar allow list. The two five field shapes are told apart by field four: a
 * boolean there means the legacy form, anything else means the LLD form.
 *
 * <p>Inbound tokens are stored as hashes only, so a leaked tokens file does not immediately yield
 * usable credentials. Blank lines and lines starting with {@code #} are ignored.
 *
 * @author Viquar Khan
 */
public final class TokenRegistry {

    private final Map<String, CallerIdentity> byTokenHash;

    private TokenRegistry(Map<String, CallerIdentity> byTokenHash) {
        this.byTokenHash = Collections.unmodifiableMap(byTokenHash);
    }

    public static TokenRegistry load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("auth tokens file missing or not a file: " + file);
        }
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8), file.toString());
    }

    /** Parses already read lines. Exposed so tests do not need a temporary file. */
    public static TokenRegistry parse(List<String> lines, String source) {
        Map<String, CallerIdentity> map = new LinkedHashMap<>();
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = Arrays.stream(line.split(":", -1)).map(String::trim).toArray(String[]::new);
            if (parts.length < 4 || parts.length > 6) {
                throw new IllegalArgumentException(
                        "auth tokens file line " + lineNo + ": expected 4..6 colon fields, got " + parts.length);
            }
            String callerId = parts[0];
            String hash = parts[1].toLowerCase(Locale.ROOT);
            if (callerId.isBlank() || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "auth tokens file line " + lineNo + ": invalid callerId or sha256 hex");
            }
            boolean legacy = parts.length == 4 || (parts.length == 5 && isBoolean(parts[3]));
            Set<String> jobs = parseCsv(parts[2]);
            Set<String> jars = legacy ? jobs : parseCsv(parts[3]);
            if (jobs.isEmpty() || jars.isEmpty()) {
                throw new IllegalArgumentException(
                        "auth tokens file line " + lineNo + ": allow lists must not be empty");
            }
            boolean readonly = Boolean.parseBoolean(legacy ? parts[3] : parts[4]);
            String outbound = null;
            if (legacy && parts.length == 5) {
                outbound = parts[4];
            } else if (!legacy && parts.length == 6) {
                outbound = parts[5];
            }
            CallerIdentity identity = CallerIdentity.of(callerId, jobs, jars, readonly, outbound);
            if (map.put(hash, identity) != null) {
                throw new IllegalArgumentException(
                        "auth tokens file line " + lineNo + ": duplicate token hash");
            }
        }
        if (map.isEmpty()) {
            throw new IllegalArgumentException("auth tokens file has no entries: " + source);
        }
        return new TokenRegistry(map);
    }

    public Optional<CallerIdentity> authenticateBearerToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(rawToken);
        CallerIdentity match = null;
        // Iterate every entry so lookup time does not leak which prefix matched.
        for (Map.Entry<String, CallerIdentity> e : byTokenHash.entrySet()) {
            if (constantTimeEquals(e.getKey(), hash)) {
                match = e.getValue();
            }
        }
        return Optional.ofNullable(match);
    }

    public int size() {
        return byTokenHash.size();
    }

    public static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isBoolean(String v) {
        return "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v);
    }

    private static Set<String> parseCsv(String csv) {
        Set<String> set = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return set;
        }
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return set;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] aa = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int len = Math.max(aa.length, bb.length);
        byte[] left = new byte[len];
        byte[] right = new byte[len];
        System.arraycopy(aa, 0, left, 0, aa.length);
        System.arraycopy(bb, 0, right, 0, bb.length);
        int diff = aa.length ^ bb.length;
        if (!MessageDigest.isEqual(left, right)) {
            diff |= 1;
        }
        return diff == 0;
    }
}
