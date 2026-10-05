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
package io.github.vaquarkhan.aegis.source.jdbc;

/**
 * Settings for a {@code type: jdbc} source.
 *
 * @param url                  JDBC URL, for example {@code jdbc:hive2://hiveserver2:10000/default}
 * @param driverClass          driver class to load explicitly; null relies on JDBC 4 service discovery
 * @param user                 resolved user name, or null
 * @param password             resolved password, or null (never logged, never audited)
 * @param maxRows              hard cap on rows returned to the model
 * @param maxCellChars         hard cap on the rendered length of one column value
 * @param loginTimeoutSeconds  connection timeout
 * @param allowWrites          when false, the write tool is not offered at all
 */
public record JdbcSettings(String url, String driverClass, String user, String password,
                           int maxRows, int maxCellChars, int loginTimeoutSeconds, boolean allowWrites) {

    public JdbcSettings {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        if (!url.startsWith("jdbc:")) {
            // The value is not echoed: a malformed URL can still carry a password.
            throw new IllegalArgumentException("url must start with jdbc:");
        }
        if (maxRows < 1 || maxRows > 10_000) {
            throw new IllegalArgumentException("maxRows must be between 1 and 10000");
        }
        if (maxCellChars < 16 || maxCellChars > 1_000_000) {
            throw new IllegalArgumentException("maxCellChars must be between 16 and 1000000");
        }
        if (loginTimeoutSeconds < 1 || loginTimeoutSeconds > 600) {
            throw new IllegalArgumentException("loginTimeoutSeconds must be between 1 and 600");
        }
    }

    /**
     * The URL with any embedded credentials removed, safe to put in an audit record or a log. JDBC
     * URLs carry secrets in several shapes, and all of them are stripped here:
     * <ul>
     *   <li>a query string ({@code ?user=..&password=..}), dropped whole;</li>
     *   <li>a userinfo authority ({@code //user:pass@host} or {@code //user@host});</li>
     *   <li>semicolon or ampersand properties whose key names a credential
     *       ({@code ;password=..}, {@code ;user=..}, as SQL Server, Hive and others use);</li>
     *   <li>an Oracle thin basic form ({@code :scott/tiger@//host});</li>
     *   <li>a parenthesised TNS credential ({@code (PASSWORD=..)}, {@code (USER=..)}).</li>
     * </ul>
     */
    public String safeUrl() {
        int q = url.indexOf('?');
        String s = q < 0 ? url : url.substring(0, q);
        s = s.replaceAll("//[^/@]*@", "//");
        s = s.replaceAll("(?i)([;&](?:password|passwd|pwd|user|username|uid|user id|accesstoken|token|secret|apikey|api key)\\s*=)[^;&]*", "$1<redacted>");
        s = s.replaceAll("(?i)\\(\\s*(password|user|uid|secret)\\s*=[^)]*\\)", "($1=<redacted>)");
        s = s.replaceAll(":[^:/@\\s]+/[^:/@\\s]+@", ":<redacted>@");
        return s;
    }
}
