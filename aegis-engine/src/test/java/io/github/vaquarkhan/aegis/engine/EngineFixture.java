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
package io.github.vaquarkhan.aegis.engine;

import io.github.vaquarkhan.aegis.engine.approval.ApprovalService;
import io.github.vaquarkhan.aegis.engine.approval.VrpService;
import io.github.vaquarkhan.aegis.engine.audit.InMemoryAuditSink;
import io.github.vaquarkhan.aegis.engine.catalog.CatalogIntegrity;
import io.github.vaquarkhan.aegis.engine.catalog.DryRunSpec;
import io.github.vaquarkhan.aegis.engine.catalog.GovernedTool;
import io.github.vaquarkhan.aegis.engine.catalog.ResourceRule;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.control.CircuitBreakers;
import io.github.vaquarkhan.aegis.engine.control.EgressGuard;
import io.github.vaquarkhan.aegis.engine.control.InjectionScreen;
import io.github.vaquarkhan.aegis.engine.control.OutputGuard;
import io.github.vaquarkhan.aegis.engine.control.RateLimiter;
import io.github.vaquarkhan.aegis.engine.control.RulePolicy;
import io.github.vaquarkhan.aegis.engine.control.ScopeRule;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonPointer;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.CredentialProvider;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic in-process engine for tests: fixed clock, scripted DNS answers and recording executor. */
final class EngineFixture {

    static final byte[] KEY = ("0123456789abcdef" + "0123456789abcdef-test-key").getBytes(StandardCharsets.UTF_8);

    static final CallerIdentity ANALYST = CallerIdentity.of("alice", Set.of("analysts"), "test-idp", "jwt");
    static final CallerIdentity OPERATOR = CallerIdentity.of("olga", Set.of("analysts", "operators"), "test-idp", "jwt");
    static final CallerIdentity APPROVER = CallerIdentity.of("bob", Set.of("approvers"), "test-idp", "jwt");

    /** Mutable clock. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-21T12:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    /** Records calls; can fail on demand. */
    static final class RecordingExecutor implements ToolExecutor {
        final List<ExecutionContext> calls = new ArrayList<>();
        URI target = URI.create("https://engine.example.com/api");
        volatile boolean fail;
        volatile String responseText = "ok";

        @Override
        public List<URI> egressTargets(Map<String, Object> args) {
            return List.of(target);
        }

        @Override
        public ToolResult execute(ExecutionContext ctx) throws ExecutionException {
            calls.add(ctx);
            if (fail) {
                throw new ExecutionException("backend down");
            }
            return ToolResult.text(responseText, false);
        }
    }

    /** A source whose tool list tests can change (to simulate a rug pull). */
    static final class MutableSource implements ToolSource {
        final String id;
        volatile List<SourcedTool> tools;

        MutableSource(String id, List<SourcedTool> tools) {
            this.id = id;
            this.tools = tools;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<SourcedTool> discover() {
            return tools;
        }
    }

    final MutableClock clock = new MutableClock();
    final InMemoryStateStore state = new InMemoryStateStore(clock, 10_000);
    final InMemoryAuditSink audit = new InMemoryAuditSink();
    final RecordingExecutor executor = new RecordingExecutor();
    final AtomicInteger credentialLookups = new AtomicInteger();
    final List<io.github.vaquarkhan.aegis.engine.spi.DecisionListener.Event> decisions =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    final io.github.vaquarkhan.aegis.engine.observe.DecisionMetrics metrics = new io.github.vaquarkhan.aegis.engine.observe.DecisionMetrics();
    final MutableSource source;
    final ToolCatalog catalog;
    final GovernanceEngine engine;
    final ApprovalService approvals;

    EngineFixture() throws Exception {
        this(List.of(), RateLimiter.DISABLED, 3);
    }

    EngineFixture(List<io.github.vaquarkhan.aegis.engine.control.HookBinding> hooks, RateLimiter rate,
                  int breakerThreshold) throws Exception {
        source = new MutableSource("engine", defaultTools());
        CatalogIntegrity integrity = new CatalogIntegrity(CatalogIntegrity.Mode.TOFU, Map.of(), null);
        catalog = new ToolCatalog(List.of(source), EngineFixture::bind, integrity, InjectionScreen.defaults(),
                true, false, e -> { });
        catalog.load();
        approvals = new ApprovalService(KEY, state, Set.of("approvers"), Duration.ofMinutes(30), Duration.ofMinutes(10), clock,
                audit, "test-gw");
        VrpService vrp = new VrpService(KEY, state, Duration.ofMinutes(15), clock);
        EgressGuard egress = new EgressGuard(List.of("engine.example.com", "*.corp.example.com"), List.of(), Set.of("https"),
                host -> switch (host) {
                    case "engine.example.com" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34})};
                    case "internal.corp.example.com" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {10, 0, 0, 5})};
                    default -> throw new java.net.UnknownHostException(host);
                });
        RulePolicy policy = new RulePolicy(List.of(
                new RulePolicy.Rule("analysts-read", RulePolicy.Effect.ALLOW, Set.of("analysts"), Set.of(), List.of(),
                        EnumSet.of(ToolClass.READ), List.of()),
                new RulePolicy.Rule("operators-write", RulePolicy.Effect.ALLOW, Set.of("operators"), Set.of(), List.of(),
                        EnumSet.of(ToolClass.MUTATE, ToolClass.DESTRUCTIVE), List.of("kafka:topic:*")),
                new RulePolicy.Rule("no-prod-delete", RulePolicy.Effect.DENY, Set.of("*"), Set.of(), List.of("delete_topic"),
                        Set.of(), List.of("kafka:topic:prod.*"))));
        GovernanceSettings settings = new GovernanceSettings("test-gw", Set.of("operators"), Set.of(), null, true,
                List.of(new ScopeRule(Set.of("operators"), List.of("kafka:topic:*")),
                        new ScopeRule(Set.of("analysts"), List.of("kafka:topic:*"))),
                rate, InjectionScreen.defaults(), OutputGuard.defaults(), egress, hooks, true, false,
                // A recording listener, real metrics, and a listener that always throws (must be harmless).
                List.of(decisions::add, metrics, e -> { throw new IllegalStateException("broken listener"); }));
        CredentialProvider creds = (sourceId, caller) -> {
            credentialLookups.incrementAndGet();
            return Map.of("Authorization", "Bearer backend-service-token");
        };
        engine = new GovernanceEngine(settings, catalog, policy, approvals, vrp, state, audit, creds,
                new CircuitBreakers(breakerThreshold, 30_000, clock), clock, true);
    }

    List<ToolSource.SourcedTool> defaultTools() {
        return List.of(
                new ToolSource.SourcedTool(tool("list_topics", "List topics.", "{\"type\":\"object\",\"properties\":{\"prefix\":{\"type\":\"string\"},\"filter\":{\"type\":\"object\"}},\"additionalProperties\":false}"), executor),
                new ToolSource.SourcedTool(tool("create_topic", "Create a topic.", "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\",\"maxLength\":100},\"partitions\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"topic\"]}"), executor),
                new ToolSource.SourcedTool(tool("delete_topic", "Delete a topic.", "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}}}"), executor),
                new ToolSource.SourcedTool(tool("fetch_url", "Fetch a URL.", "{\"type\":\"object\"}"), executor),
                new ToolSource.SourcedTool(tool("read_report", "Read a report.", "{\"type\":\"object\"}"), executor));
    }

    static ToolDescriptor tool(String name, String description, String schema) {
        return new ToolDescriptor(name, null, description, Json.parseObject(schema), null, null);
    }

    static Optional<GovernedTool> bind(String sourceId, ToolSource.SourcedTool st) {
        String n = st.descriptor().name();
        ToolClass cls = switch (n) {
            case "create_topic" -> ToolClass.MUTATE;
            case "delete_topic" -> ToolClass.DESTRUCTIVE;
            default -> ToolClass.READ;
        };
        List<ResourceRule> res = n.endsWith("_topic")
                ? List.of(new ResourceRule("kafka:topic", List.of(JsonPointer.parse("/topic")), ".", true))
                : List.of();
        // read_report models an operator-configured tool that needs two verified OAuth grants.
        Set<String> actionScopes = n.equals("read_report") ? Set.of("reports:read", "tenant:acme") : Set.of();
        return Optional.of(new GovernedTool(n, sourceId, st.descriptor(), cls, res, DryRunSpec.GATEWAY, Set.of(),
                actionScopes, st.executor(), 5_000));
    }
}
