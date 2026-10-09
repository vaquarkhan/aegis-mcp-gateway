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

/**
 * Full-string glob matching ({@code *} = any sequence, {@code ?} = one character).
 *
 * <p>The grammar intentionally has no regular expressions, but the greedy last-star algorithm can
 * revisit a literal suffix. Evaluation is therefore bounded rather than described as linear. A limit
 * exhaustion throws {@link LimitExceededException}; authorization callers must convert it to a denial,
 * never interpret it as an unmatched pattern.
 */
public final class Glob {

    /** Maximum UTF-16 code units in one operator-configured pattern. */
    public static final int MAX_PATTERN_CHARS = 512;
    /** Maximum UTF-16 code units in one matched, potentially caller-controlled value. */
    public static final int MAX_TEXT_CHARS = 4_096;
    /** Maximum wildcard count accepted in one pattern. */
    public static final int MAX_STARS = 64;
    /** Maximum patterns examined in a single {@link #anyMatches(Iterable, String)} call. */
    public static final int MAX_PATTERNS = 128;
    /** Maximum deterministic matcher transitions for one pattern/value comparison. */
    public static final int MAX_WORK = 65_536;

    private Glob() {}

    /** Signals a fail-closed matcher bound rather than an unmatched pattern. */
    public static final class LimitExceededException extends IllegalArgumentException {
        public LimitExceededException(String message) {
            super(message);
        }
    }

    public static boolean matches(String pattern, String text) {
        if (pattern == null || text == null) {
            throw new IllegalArgumentException("glob pattern and text are required");
        }
        validatePattern(pattern);
        if (text.length() > MAX_TEXT_CHARS) {
            throw new LimitExceededException("glob candidate exceeds the configured character limit");
        }
        int p = 0;
        int t = 0;
        int star = -1;
        int mark = 0;
        int work = 0;
        while (t < text.length()) {
            if (++work > MAX_WORK) {
                throw new LimitExceededException("glob evaluation exceeds the configured work limit");
            }
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(t))) {
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = t;
            } else if (star >= 0) {
                p = star + 1;
                t = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            if (++work > MAX_WORK) {
                throw new LimitExceededException("glob evaluation exceeds the configured work limit");
            }
            p++;
        }
        return p == pattern.length();
    }

    public static boolean anyMatches(Iterable<String> patterns, String text) {
        if (patterns == null) {
            throw new IllegalArgumentException("glob patterns are required");
        }
        int count = 0;
        for (String pattern : patterns) {
            if (++count > MAX_PATTERNS) {
                throw new LimitExceededException("glob pattern collection exceeds the configured limit");
            }
            if (matches(pattern, text)) {
                return true;
            }
        }
        return false;
    }

    private static void validatePattern(String pattern) {
        if (pattern.length() > MAX_PATTERN_CHARS) {
            throw new LimitExceededException("glob pattern exceeds the configured character limit");
        }
        int stars = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '*' && ++stars > MAX_STARS) {
                throw new LimitExceededException("glob pattern exceeds the configured wildcard limit");
            }
        }
    }
}
