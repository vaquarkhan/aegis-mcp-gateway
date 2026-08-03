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
package io.github.vaquarkhan.aegis.core.authz;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.governance.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File backed deny-rule policy engine.
 *
 * <p>Rule syntax, one per line, {@code #} starts a comment:
 *
 * <pre>
 * deny tool     &lt;glob&gt;
 * deny resource &lt;glob&gt;
 * deny caller   &lt;glob&gt;
 * </pre>
 *
 * <p>A configured file that cannot be read produces a fail-closed engine that denies everything,
 * because "policy file missing" and "no policy" must not look the same to the gateway.
 *
 * @author Viquar Khan
 */
public final class BuiltinPolicyEngine implements PolicyDecisionPoint {

    private static final Logger LOG = LoggerFactory.getLogger(BuiltinPolicyEngine.class);

    private final boolean failClosed;
    private final List<Pattern> denyTools;
    private final List<Pattern> denyResources;
    private final List<Pattern> denyCallers;

    private BuiltinPolicyEngine(
            boolean failClosed, List<Pattern> denyTools, List<Pattern> denyResources, List<Pattern> denyCallers) {
        this.failClosed = failClosed;
        this.denyTools = denyTools;
        this.denyResources = denyResources;
        this.denyCallers = denyCallers;
    }

    /** No policy file means no deny rules. */
    public static BuiltinPolicyEngine permissive() {
        return new BuiltinPolicyEngine(false, List.of(), List.of(), List.of());
    }

    public static BuiltinPolicyEngine load(String policyFile) {
        if (policyFile == null || policyFile.isBlank()) {
            return permissive();
        }
        try {
            return parse(Files.readAllLines(Path.of(policyFile), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOG.error("policy file {} unreadable, failing closed: {}", policyFile, e.getMessage());
            return new BuiltinPolicyEngine(true, List.of(), List.of(), List.of());
        }
    }

    public static BuiltinPolicyEngine parse(List<String> lines) {
        List<Pattern> tools = new ArrayList<>();
        List<Pattern> resources = new ArrayList<>();
        List<Pattern> callers = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("deny tool ")) {
                tools.add(globToRegex(line.substring("deny tool ".length()).trim()));
            } else if (line.startsWith("deny resource ")) {
                resources.add(globToRegex(line.substring("deny resource ".length()).trim()));
            } else if (line.startsWith("deny caller ")) {
                callers.add(globToRegex(line.substring("deny caller ".length()).trim()));
            } else {
                LOG.warn("ignoring unrecognised policy line: {}", line);
            }
        }
        return new BuiltinPolicyEngine(false, List.copyOf(tools), List.copyOf(resources), List.copyOf(callers));
    }

    @Override
    public boolean allows(CallerIdentity caller, String tool, Map<String, Object> args) {
        if (failClosed) {
            return false;
        }
        if (matchesAny(denyTools, tool)) {
            return false;
        }
        if (caller != null && matchesAny(denyCallers, caller.subject())) {
            return false;
        }
        String resource = Scope.resourceOf(args);
        return resource == null || !matchesAny(denyResources, resource);
    }

    /** True when the engine is denying everything because its policy source was unreadable. */
    public boolean failClosed() {
        return failClosed;
    }

    public int ruleCount() {
        return denyTools.size() + denyResources.size() + denyCallers.size();
    }

    private static boolean matchesAny(List<Pattern> patterns, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (Pattern p : patterns) {
            if (p.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }
}
