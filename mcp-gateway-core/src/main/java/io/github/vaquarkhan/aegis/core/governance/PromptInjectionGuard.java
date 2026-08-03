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

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 8. Pattern based prompt injection screening of tool arguments.
 *
 * <p>This is a coarse tripwire, not a classifier. It exists because injected instructions usually
 * arrive as literal English imperatives inside an otherwise structured argument, and catching the
 * obvious cases is cheap. It is deliberately the eighth gate rather than the first: authorization
 * decisions should never depend on heuristics.
 *
 * @author Viquar Khan
 */
public final class PromptInjectionGuard {

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts|rules)"),
            Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|prior|above|earlier|the)\\s+"
                    + "(instructions|prompts|rules|system)"),
            Pattern.compile("(?i)(reveal|print|output|show|repeat)\\s+(your|the)\\s+"
                    + "(system\\s+prompt|instructions|developer\\s+message)"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an|in)\\s"),
            Pattern.compile("(?i)(developer|system)\\s+mode\\s+(enabled|on|activated)"),
            Pattern.compile("(?i)\\bDAN\\b\\s+mode"),
            Pattern.compile("(?i)(exfiltrate|leak|send)\\s+(the\\s+)?(secret|credential|token|api\\s*key)"),
            Pattern.compile("(?i)(curl|wget)\\s+[^\\s]*169\\.254\\.169\\.254"),
            Pattern.compile("(?i)</?(system|assistant|developer)>"),
            Pattern.compile("(?i)\\[\\[?\\s*(system|assistant)\\s*\\]\\]?\\s*:"),
            Pattern.compile("(?i)override\\s+(the\\s+)?(safety|guardrail|policy|approval)")
    };

    private final boolean enabled;

    public PromptInjectionGuard(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isSuspicious(String text) {
        return matchedPattern(text) != null;
    }

    /** The pattern that fired, or {@code null} when the text looks clean. */
    public String matchedPattern(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return null;
        }
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return p.pattern();
            }
        }
        return null;
    }

    /**
     * Scans every string valued argument. Returns the offending argument name, or {@code null}
     * when nothing matched.
     */
    public String scan(Map<String, Object> arguments) {
        if (!enabled || arguments == null || arguments.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (matchedPattern(String.valueOf(v)) != null) {
                return e.getKey();
            }
        }
        return null;
    }
}
