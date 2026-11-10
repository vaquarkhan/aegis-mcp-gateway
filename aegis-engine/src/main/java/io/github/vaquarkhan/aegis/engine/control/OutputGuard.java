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
package io.github.vaquarkhan.aegis.engine.control;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bounds and redacts backend output before it reaches the model. Redaction walks every string at
 * any depth of {@code content} and {@code structuredContent} (see {@link ValueWalker}).
 *
 * <p>Default patterns catch common secret shapes (cloud access keys, private-key blocks, bearer
 * tokens, JWTs). They are heuristics that reduce accidental leakage; they are not a DLP product.
 */
public final class OutputGuard {

  /**
   * Default regular-expression redactions. PEM private-key blocks are NOT a regex: a lazy {@code
   * [\s\S]{0,16384}?} body costs up to 16K steps per unterminated BEGIN marker, and a caller can
   * pack thousands of markers into one argument string (reachable through approver evidence). They
   * are redacted by the linear scanner in {@link #redactPem} instead, always, independent of this
   * list.
   */
  public static final List<String> DEFAULT_REDACTIONS =
      List.of(
          "AKIA[0-9A-Z]{16}",
          "(?i)bearer\\s+[A-Za-z0-9._~+/=-]{20,}",
          "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

  /**
   * Personal-data patterns, opt-in ({@code output.redactPii}; on by default in complete control).
   * Every quantifier is bounded so no input can make a pattern backtrack without limit. Payment
   * card matching is shape-based (13 to 19 digits, optional single space or dash separators) and
   * will also redact some long numeric identifiers; that is the deliberate fail-safe direction.
   */
  public static final List<String> PII_REDACTIONS =
      List.of(
          "[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){0,8}\\.[A-Za-z]{2,24}",
          "(?<![0-9])\\+[1-9][0-9]{7,14}(?![0-9])",
          "(?<![0-9])\\(?[0-9]{3}\\)?[ .-][0-9]{3}[ .-][0-9]{4}(?![0-9])",
          "(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])",
          "(?<![0-9])[0-9]{3}-[0-9]{2}-[0-9]{4}(?![0-9])",
          "\\b[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}\\b");

  private static final String PEM_BEGIN = "-----BEGIN ";
  private static final String PEM_END = "-----END ";
  private static final String PEM_KEY_TAIL = "PRIVATE KEY-----";

  /** Longest label between BEGIN and PRIVATE KEY (e.g. "RSA ", "EC ", "ENCRYPTED ", "OPENSSH "). */
  private static final int PEM_LABEL_MAX = 40;

  /** Longest PEM body we will span looking for the matching END; a real key is a few KiB. */
  private static final int PEM_BODY_MAX = 16384;

  public static final String REDACTED = "[REDACTED]";
  private static final int MAX_REGEX_INPUT = 1 << 20;

  /**
   * Shape of a credential that has no recognisable prefix: a long run of token characters. Only
   * applied when {@code redactHighEntropy} is on, and only when the run also has the entropy and
   * character mix of random material (see {@link #looksRandom}). Hex-only digests are left alone on
   * purpose.
   */
  static final Pattern HIGH_ENTROPY_TOKEN = Pattern.compile("[A-Za-z0-9+/_-]{32,}={0,2}");

  /**
   * Shannon entropy, bits per character, below which a run is treated as a word or an identifier.
   */
  static final double MIN_ENTROPY_BITS = 4.0;

  /**
   * Fraction of the maximum possible entropy for the run's length ({@code log2(min(n, 64))}) that
   * random key material reaches: 0.9 to 1.0 for random base64 of 32 to 64 characters, 0.75 to 0.8
   * for long camel-case identifiers, which pick up absolute entropy from their length alone. The
   * ratio is what separates them.
   */
  static final double MIN_ENTROPY_RATIO = 0.85;

  private final int maxBytes;
  private final List<Pattern> redactions;
  private final boolean allowBinary;

  /** Null means results are not screened for injection phrases. */
  private final InjectionScreen resultScreen;

  private final boolean redactHighEntropy;

  /** Guard that refuses binary tool output (the default). */
  public OutputGuard(int maxBytes, List<String> redactionPatterns) {
    this(maxBytes, redactionPatterns, false);
  }

  /**
   * @param allowBinary pass binary parts of tool results ({@code data} of image and audio content,
   *     and any {@code blob}); they are opaque to redaction and screening, so the default refuses
   *     them
   */
  public OutputGuard(int maxBytes, List<String> redactionPatterns, boolean allowBinary) {
    this(maxBytes, redactionPatterns, allowBinary, null, false);
  }

  /**
   * @param resultScreen injection screen applied to every string of a tool result after redaction;
   *     null or an OFF screen disables it. A hostile backend's result enters the model's context
   *     exactly like a tool description does, so it is screened with the same phrases.
   * @param redactHighEntropy also redact long high-entropy runs with no known prefix (opt-in
   *     heuristic)
   */
  public OutputGuard(
      int maxBytes,
      List<String> redactionPatterns,
      boolean allowBinary,
      InjectionScreen resultScreen,
      boolean redactHighEntropy) {
    this.maxBytes = maxBytes <= 0 ? 1 << 20 : maxBytes;
    List<Pattern> ps = new ArrayList<>();
    for (String p : redactionPatterns == null ? DEFAULT_REDACTIONS : redactionPatterns) {
      ps.add(Pattern.compile(p));
    }
    this.redactions = List.copyOf(ps);
    this.allowBinary = allowBinary;
    this.resultScreen =
        resultScreen == null || resultScreen.mode() == ControlMode.OFF ? null : resultScreen;
    this.redactHighEntropy = redactHighEntropy;
  }

  /** Mode of the result injection screen; OFF when none is configured. */
  public ControlMode resultScreenMode() {
    return resultScreen == null ? ControlMode.OFF : resultScreen.mode();
  }

  public boolean redactHighEntropy() {
    return redactHighEntropy;
  }

  public static OutputGuard defaults() {
    return new OutputGuard(1 << 20, DEFAULT_REDACTIONS);
  }

  public boolean allowBinary() {
    return allowBinary;
  }

  /**
   * Result of guarding: the safe result plus what was changed (for audit).
   *
   * @param binaryRefused the backend returned binary content and this guard does not allow it; the
   *     caller must withhold the result
   */
  public record Guarded(
      ToolResult result,
      int redactions,
      boolean truncated,
      boolean binaryRefused,
      String injectionMatch) {
    public Guarded(ToolResult result, int redactions, boolean truncated, boolean binaryRefused) {
      this(result, redactions, truncated, binaryRefused, null);
    }
  }

  public Guarded apply(ToolResult in) {
    int[] count = new int[1];
    boolean[] binary = new boolean[1];
    // Every field of the result is redacted, not only content/structuredContent: MRTR
    // inputRequests, upstream _meta and any extension field reach the client too. Binary payloads
    // are left byte-for-byte intact (a regular expression would corrupt them) and reported.
    Map<String, Object> body = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : in.body().entrySet()) {
      body.put(e.getKey(), guardValue(e.getValue(), count, binary, 0));
    }
    if (binary[0] && !allowBinary) {
      return new Guarded(
          ToolResult.text("Aegis withheld this result: it contains binary content", true),
          count[0],
          false,
          true);
    }
    // Screened after redaction, over every string the client would receive (content, structured
    // content, MRTR input requests, upstream _meta). The engine decides what a match means.
    String injection = resultScreen == null ? null : resultScreen.firstMatch(body).orElse(null);
    byte[] size = Json.write(body).getBytes(StandardCharsets.UTF_8);
    boolean truncated = false;
    if (size.length > maxBytes) {
      truncated = true;
      String text = firstText(body);
      byte[] tb = text.getBytes(StandardCharsets.UTF_8);
      int keep = Math.max(0, Math.min(tb.length, maxBytes / 2));
      String cut = new String(tb, 0, keep, StandardCharsets.UTF_8);
      Map<String, Object> small = new LinkedHashMap<>();
      small.put(
          "content",
          List.of(
              Map.of(
                  "type",
                  "text",
                  "text",
                  cut
                      + "\n[output truncated by Aegis: "
                      + size.length
                      + " bytes exceeds "
                      + maxBytes
                      + "]")));
      small.put("isError", Boolean.TRUE.equals(body.get("isError")));
      if (body.containsKey("resultType")) {
        small.put("resultType", body.get("resultType"));
      }
      body = small;
    }
    if (count[0] > 0 || truncated) {
      Map<String, Object> meta = new LinkedHashMap<>();
      Object existing = body.get("_meta");
      if (existing instanceof Map<?, ?> em) {
        for (Map.Entry<?, ?> e : em.entrySet()) {
          meta.put(String.valueOf(e.getKey()), e.getValue());
        }
      }
      meta.put(ControlTokens.META_PREFIX + "redactions", (long) count[0]);
      meta.put(ControlTokens.META_PREFIX + "truncated", truncated);
      body.put("_meta", meta);
    }
    return new Guarded(new ToolResult(body), count[0], truncated, false, injection);
  }

  /**
   * Redacts every string value at any depth, except binary payloads: {@code blob} anywhere, and
   * {@code data} of an {@code image} or {@code audio} content part. Those are reported in {@code
   * binary} and copied unchanged.
   */
  private Object guardValue(Object v, int[] count, boolean[] binary, int depth) {
    if (depth > ValueWalker.MAX_DEPTH) {
      throw new IllegalArgumentException("value nesting exceeds " + ValueWalker.MAX_DEPTH);
    }
    if (v instanceof String s) {
      return redact(s, count);
    }
    if (v instanceof List<?> l) {
      List<Object> out = new ArrayList<>(l.size());
      for (Object o : l) {
        out.add(guardValue(o, count, binary, depth + 1));
      }
      return out;
    }
    if (v instanceof Map<?, ?> m) {
      Object type = m.get("type");
      boolean media = "image".equals(type) || "audio".equals(type);
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : m.entrySet()) {
        String k = String.valueOf(e.getKey());
        if ("blob".equals(k) || (media && "data".equals(k))) {
          binary[0] = true;
          out.put(k, e.getValue());
        } else {
          out.put(k, guardValue(e.getValue(), count, binary, depth + 1));
        }
      }
      return out;
    }
    return v;
  }

  /** Redacts every string of any JSON value (used for resources and prompts). */
  public record Redacted(Object value, int redactions) {}

  public Redacted redactAll(Object value) {
    int[] count = new int[1];
    Object v = ValueWalker.mapStrings(value, s -> redact(s, count));
    return new Redacted(v, count[0]);
  }

  public int maxBytes() {
    return maxBytes;
  }

  /**
   * Linear-time PEM private-key redaction. For each {@code -----BEGIN <label>PRIVATE KEY-----} the
   * matching {@code -----END <label>PRIVATE KEY-----} is searched within {@link #PEM_BODY_MAX}
   * chars; found or not, scanning resumes after the BEGIN marker, so N markers cost O(N + input),
   * never O(N x body). An unterminated block is left as is (it carries no complete key).
   */
  static String redactPem(String s, int[] count) {
    int from = 0;
    StringBuilder sb = null;
    int copied = 0;
    // Position of the next "-----END " at or after the current header, or -1 when there is none
    // left.
    // Computed with a forward-only indexOf, so all BEGIN markers together cost one pass over the
    // input.
    int nextEnd = Integer.MIN_VALUE;
    while (true) {
      int begin = s.indexOf(PEM_BEGIN, from);
      if (begin < 0) {
        break;
      }
      int labelStart = begin + PEM_BEGIN.length();
      int tail =
          indexOfWithin(
              s, PEM_KEY_TAIL, labelStart, labelStart + PEM_LABEL_MAX + PEM_KEY_TAIL.length());
      if (tail < 0 || !labelOnly(s, labelStart, tail)) {
        from = labelStart;
        continue;
      }
      String label = s.substring(labelStart, tail);
      int headerEnd = tail + PEM_KEY_TAIL.length();
      String endMarker = PEM_END + label + PEM_KEY_TAIL;
      int bodyLimit = Math.min(s.length(), headerEnd + PEM_BODY_MAX + endMarker.length());
      if (nextEnd != -1 && nextEnd < headerEnd) {
        nextEnd = s.indexOf(PEM_END, headerEnd);
      }
      if (nextEnd < 0 || nextEnd + endMarker.length() > bodyLimit) {
        from = labelStart; // no END of any kind inside this block's window
        continue;
      }
      int end = indexOfWithin(s, endMarker, nextEnd, bodyLimit);
      if (end < 0) {
        from = labelStart;
        continue;
      }
      if (sb == null) {
        sb = new StringBuilder(s.length());
      }
      sb.append(s, copied, begin).append(REDACTED);
      copied = end + endMarker.length();
      from = copied;
      count[0]++;
    }
    if (sb == null) {
      return s;
    }
    return sb.append(s, copied, s.length()).toString();
  }

  private static boolean labelOnly(String s, int from, int to) {
    for (int i = from; i < to; i++) {
      char c = s.charAt(i);
      if (!((c >= 'A' && c <= 'Z') || c == ' ')) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@code indexOf} that never looks past {@code limit}. {@link String#indexOf(String, int)} has no
   * end bound and would scan the whole remainder for every BEGIN marker, which is the quadratic
   * cost this scanner exists to avoid.
   */
  private static int indexOfWithin(String s, String needle, int from, int limit) {
    int last = Math.min(limit, s.length()) - needle.length();
    char first = needle.charAt(0);
    for (int i = from; i <= last; i++) {
      if (s.charAt(i) == first && s.regionMatches(i, needle, 0, needle.length())) {
        return i;
      }
    }
    return -1;
  }

  private String redact(String s, int[] count) {
    String out = s.length() > MAX_REGEX_INPUT ? s.substring(0, MAX_REGEX_INPUT) : s;
    out = redactPem(out, count);
    for (Pattern p : redactions) {
      java.util.regex.Matcher m = p.matcher(out);
      StringBuilder sb = new StringBuilder();
      boolean any = false;
      while (m.find()) {
        any = true;
        count[0]++;
        m.appendReplacement(sb, REDACTED);
      }
      if (any) {
        m.appendTail(sb);
        out = sb.toString();
      }
    }
    if (redactHighEntropy) {
      java.util.regex.Matcher m = HIGH_ENTROPY_TOKEN.matcher(out);
      StringBuilder sb = new StringBuilder();
      boolean any = false;
      while (m.find()) {
        if (looksRandom(m.group())) {
          any = true;
          count[0]++;
          m.appendReplacement(sb, REDACTED);
        }
      }
      if (any) {
        m.appendTail(sb);
        out = sb.toString();
      }
    }
    return out;
  }

  /**
   * True for a run that has the character mix and entropy of random key material: upper case, lower
   * case and digits all present, and at least {@link #MIN_ENTROPY_BITS} bits per character. A word,
   * an identifier, a URL path or a lower-case hex digest does not pass; a base64 secret or an API
   * key with a mixed alphabet does. Heuristic, hence opt-in.
   */
  public static boolean looksRandom(String run) {
    boolean upper = false;
    boolean lower = false;
    boolean digit = false;
    int[] freq = new int[128];
    int n = 0;
    for (int i = 0; i < run.length(); i++) {
      char c = run.charAt(i);
      if (c >= 128) {
        return false;
      }
      if (c == '=') {
        continue; // base64 padding carries no information
      }
      upper |= c >= 'A' && c <= 'Z';
      lower |= c >= 'a' && c <= 'z';
      digit |= c >= '0' && c <= '9';
      freq[c]++;
      n++;
    }
    if (!(upper && lower && digit)) {
      return false;
    }
    double bits = 0;
    for (int f : freq) {
      if (f > 0) {
        double p = (double) f / n;
        bits -= p * (Math.log(p) / Math.log(2));
      }
    }
    double max = Math.log(Math.min(n, 64)) / Math.log(2);
    return bits >= MIN_ENTROPY_BITS && bits >= MIN_ENTROPY_RATIO * max;
  }

  private static String firstText(Map<String, Object> body) {
    Object c = body.get("content");
    if (c instanceof List<?> l) {
      for (Object o : l) {
        if (o instanceof Map<?, ?> m && m.get("text") instanceof String t) {
          return t;
        }
      }
    }
    return "";
  }
}
