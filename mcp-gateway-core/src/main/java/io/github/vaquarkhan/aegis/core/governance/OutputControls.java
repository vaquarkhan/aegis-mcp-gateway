/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core.governance;

import java.util.regex.Pattern;

/**
 * Outbound controls: size bounding followed by data loss prevention redaction.
 *
 * <p>Order matters. Bounding runs first so redaction always sees the exact byte range that will be
 * returned, and a secret cannot survive by straddling the truncation boundary.
 *
 * @author Viquar Khan
 */
public final class OutputControls {

    public static final String REDACTION = "<redacted>";
    public static final String TRUNCATION_MARKER = "...<truncated>";

    private static final Pattern[] DLP = {
            // The optional quote before the separator matches JSON keys such as "password": "x".
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

    /** Truncates to the configured ceiling and appends a marker so truncation is visible. */
    public String bound(String input) {
        String s = input == null ? "" : input;
        if (s.length() > maxBytes) {
            return s.substring(0, maxBytes) + TRUNCATION_MARKER;
        }
        return s;
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
