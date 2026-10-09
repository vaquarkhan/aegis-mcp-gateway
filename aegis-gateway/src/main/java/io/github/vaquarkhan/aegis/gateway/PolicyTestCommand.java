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
package io.github.vaquarkhan.aegis.gateway;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigLoader;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.GovernanceEngine;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * {@code --policy-test <aegis.yaml> <cases.json>}: evaluates governed calls against a configuration
 * without running them, so a policy change can be reviewed in CI before it is deployed.
 *
 * <p>The gateway is built from the configuration with a strictly local subset: every source must be
 * declarative {@code type: http}, whose descriptors already live in the configuration. MCP and plugin
 * sources are refused before their discovery can contact a backend. Three additional changes keep the
 * run side-effect free: the audit journal and state go to a temporary directory, source credentials are
 * removed, a learned pin file is read from a copy, and tracing is off. Each case runs
 * {@link GovernanceEngine#explain}, which applies steps 1 to 3 exactly as a real call does and reports
 * whether approval and a dry-run receipt would follow. No backend is called and no configured secret
 * provider or source credential is resolved.
 *
 * <p>Cases file:
 * <pre>
 * {"cases": [
 *   {"name": "analysts read", "caller": {"actor": "alice", "groups": ["analysts"], "scopes": []},
 *    "tool": "list_tables", "arguments": {"namespace": "sales"}, "expect": "ALLOWED"},
 *   {"name": "drop needs approval", "caller": {"actor": "olga", "groups": ["operators"]},
 *    "tool": "drop_table", "arguments": {"namespace": "sales", "table": "orders"}, "expect": "APPROVAL_REQUIRED"}]}
 * </pre>
 * The outcome of a case is the deny code of steps 1 to 3, {@code APPROVAL_REQUIRED} when they allow a
 * call that still needs a per-call approval, or {@code ALLOWED}. Exit 0 when every case with an
 * {@code expect} matches, 1 when any does not, 2 for usage errors.
 */
final class PolicyTestCommand {

    private static final String USAGE = "--policy-test <aegis.yaml|aegis.json> <cases.json>";

    private PolicyTestCommand() {}

    static int run(String[] a, PrintStream out, PrintStream err, Function<String, String> env) {
        if (a.length != 2) {
            err.println("usage: " + USAGE);
            return Main.USAGE;
        }
        Path cfg = Path.of(a[0]).toAbsolutePath();
        Path work = null;
        try {
            List<Map<String, Object>> cases = cases(Path.of(a[1]));
            work = Files.createTempDirectory("aegis-policy-test");
            Map<String, Object> raw = isolated(ConfigLoader.load(cfg), cfg.getParent(), work);
            int failed = 0;
            int checked = 0;
            // Policy evaluation never executes an executor. Supplying an opaque value for every
            // environment lookup preserves signed source blocks while avoiding secret resolution.
            try (Gateway g = GatewayFactory.build(raw, BuildContext.defaults(cfg.getParent())
                    .withEnv(name -> "AEGIS_REQUIRE_SERVER_CATALOG_KEY".equals(name)
                            ? null : "policy-test-non-secret-placeholder"))) {
                for (Map<String, Object> c : cases) {
                    String name = c.get("name") instanceof String s ? s : "case " + (checked + failed + 1);
                    GovernanceEngine.Explanation x = g.engine().explain(caller(c), call(c));
                    String outcome = !x.decision().allowed() ? x.decision().code()
                            : x.approvalRequired() ? "APPROVAL_REQUIRED" : "ALLOWED";
                    String detail = !x.decision().allowed()
                            ? "step " + x.decision().step() + ": " + x.decision().reason()
                            : (x.approvalRequired() ? "allowed by policy; needs a per-call approval" : "allowed")
                                    + (x.dryRunReceiptRequired() ? " and a dry-run receipt" : "");
                    Object expect = c.get("expect");
                    if (expect == null) {
                        out.println("INFO " + name + ": " + outcome + " (" + detail + ")");
                    } else if (expect.equals(outcome)) {
                        checked++;
                        out.println("PASS " + name + ": " + outcome);
                    } else {
                        failed++;
                        out.println("FAIL " + name + ": expected " + expect + ", got " + outcome + " (" + detail + ")");
                    }
                }
            }
            out.println((failed == 0 ? "OK" : "FAILED") + " cases=" + cases.size() + " passed=" + checked + " failed=" + failed);
            return failed == 0 ? Main.OK : Main.BROKEN;
        } catch (IOException | RuntimeException e) {
            err.println("BROKEN " + e.getMessage());
            return Main.BROKEN;
        } catch (Exception e) {
            err.println("BROKEN " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Main.BROKEN;
        } finally {
            delete(work);
        }
    }

    /**
     * The configuration with its journal, state and learned pins moved to {@code work}, tracing off,
     * and no secret or backend discovery path. Policy tests deliberately support declarative
     * {@code type: http} sources only because their tool descriptors are local configuration; an MCP
     * or plugin source discovers from a backend and would make this command a network operation.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> isolated(Map<String, Object> loaded, Path base, Path work) throws IOException {
        Map<String, Object> raw = new LinkedHashMap<>(loaded);
        raw.remove("secrets");
        raw.put("audit", Map.of("file", work.resolve("policy-test-audit.jsonl").toString()));
        raw.remove("state");
        raw.remove("tracing");
        if (raw.get("policy") instanceof Map<?, ?> policy && policy.containsKey("opa")) {
            throw new IOException("--policy-test does not support policy.opa because it would call a remote policy endpoint");
        }
        if (!(raw.get("sources") instanceof List<?> sources) || sources.isEmpty()) {
            throw new IOException("configuration needs at least one declarative http source for --policy-test");
        }
        List<Object> offlineSources = new java.util.ArrayList<>();
        for (Object source : sources) {
            if (!(source instanceof Map<?, ?> map)) {
                throw new IOException("configuration has an invalid source for --policy-test");
            }
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
            if (!"http".equals(copy.get("type"))) {
                throw new IOException("--policy-test supports only declarative http sources; source '"
                        + copy.get("id") + "' has type '" + copy.get("type")
                        + "' and would require backend discovery");
            }
            // Preserve the complete source block. A signed server catalog covers credentials
            // references as well as the endpoint and tools. The opaque policy-test environment
            // passed to GatewayFactory prevents any configured secret value from being read.
            offlineSources.add(copy);
        }
        raw.put("sources", offlineSources);
        Map<String, Object> cat = raw.get("catalog") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        // A signed server catalog validates every source independently and rejects a learned
        // pin file. Preserve its configuration exactly. Unsigned configurations may isolate a
        // learned pin file because the command must not change operator-owned state.
        if (!raw.containsKey("serverCatalog") && cat.get("pinFile") instanceof String pinFile) {
            Path learned = base.resolve(pinFile);
            Path copy = work.resolve("pins.json");
            if (Files.exists(learned)) {
                Files.copy(learned, copy, StandardCopyOption.REPLACE_EXISTING);
            }
            cat.put("pinFile", copy.toString());
        }
        cat.put("failOnSourceError", false);
        cat.remove("refreshSeconds");
        raw.put("catalog", cat);
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(Path file) throws IOException {
        Map<String, Object> doc = Json.parseObject(Files.readAllBytes(file), JsonLimits.CONFIG);
        if (!(doc.get("cases") instanceof List<?> list) || list.isEmpty()) {
            throw new IOException(file + " needs a non-empty \"cases\" list");
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("tool") instanceof String) || !(m.get("caller") instanceof Map)) {
                throw new IOException(file + ": every case needs a \"tool\" and a \"caller\"");
            }
        }
        return (List<Map<String, Object>>) list;
    }

    @SuppressWarnings("unchecked")
    private static CallerIdentity caller(Map<String, Object> c) throws IOException {
        Map<String, Object> m = (Map<String, Object>) c.get("caller");
        if (!(m.get("actor") instanceof String actor) || actor.isBlank()) {
            throw new IOException("caller.actor is required in " + c.get("name"));
        }
        String subject = m.get("subject") instanceof String s ? s : actor;
        return new CallerIdentity(actor, subject, strings(m.get("groups")), "policy-test", "policy-test",
                List.copyOf(strings(m.get("actorChain"))), strings(m.get("scopes")));
    }

    @SuppressWarnings("unchecked")
    private static ToolCall call(Map<String, Object> c) {
        Map<String, Object> args = c.get("arguments") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return new ToolCall("policy-test", (String) c.get("tool"), args, Map.of(), "2026-07-28");
    }

    private static Set<String> strings(Object v) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (v == null) {
            return out;
        }
        if (!(v instanceof List<?> list)) {
            throw new IOException("groups, scopes and actorChain must be lists of strings");
        }
        for (Object o : list) {
            if (!(o instanceof String s)) {
                throw new IOException("groups, scopes and actorChain must be lists of strings");
            }
            out.add(s);
        }
        return out;
    }

    private static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Temporary files only; a leftover is harmless.
                }
            });
        } catch (IOException ignored) {
            // Nothing to clean up.
        }
    }
}
