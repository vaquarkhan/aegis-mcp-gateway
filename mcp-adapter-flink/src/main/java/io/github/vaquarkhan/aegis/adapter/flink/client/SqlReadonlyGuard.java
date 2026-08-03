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

package io.github.vaquarkhan.aegis.adapter.flink.client;

import io.github.vaquarkhan.aegis.core.spi.ReadOnlyGuard;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Flink SQL dialect read-only guard. Allow-list of statement prefixes, comments stripped first,
 * stacked statements rejected. Fail-closed on anything unrecognized.
 *
 * @author Viquar Khan
 */
public final class SqlReadonlyGuard implements ReadOnlyGuard {

    private static final String[] ALLOWED = {"SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN"};
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    @Override
    public boolean isReadOnly(String sql) {
        if (sql == null) {
            return false;
        }
        String cleaned = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        cleaned = LINE_COMMENT.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            return false;
        }
        // reject stacked statements: semicolon appears before the final one
        int lastSemi = cleaned.lastIndexOf(';');
        if (lastSemi >= 0) {
            String beforeLast = cleaned.substring(0, lastSemi);
            if (beforeLast.indexOf(';') >= 0) {
                return false;
            }
            if (lastSemi == cleaned.length() - 1) {
                cleaned = cleaned.substring(0, lastSemi).trim();
            } else {
                // semicolon mid-statement without being terminal stacked form
                return false;
            }
        }
        String upper = cleaned.toUpperCase(Locale.ROOT);
        for (String prefix : ALLOWED) {
            if (upper.equals(prefix)) {
                return true;
            }
            if (upper.startsWith(prefix + " ") || upper.startsWith(prefix + "\n") || upper.startsWith(prefix + "\t")) {
                return true;
            }
        }
        return false;
    }
}
