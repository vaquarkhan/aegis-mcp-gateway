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
package io.github.vaquarkhan.aegis.validation.frontdoor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the relative {@code audit.file} out of an adopter YAML, so the journal can be kept with the transcript. */
final class AuditPath {

    private static final Pattern FILE = Pattern.compile("(?m)^audit:\\s*\\R(?:[ \\t]+.*\\R)*?[ \\t]+file:\\s*([^\\s#]+)");

    private AuditPath() {}

    static String of(String yaml) {
        Matcher m = FILE.matcher(yaml);
        if (!m.find()) {
            throw new IllegalArgumentException("adopter YAML has no audit.file");
        }
        return m.group(1);
    }
}
