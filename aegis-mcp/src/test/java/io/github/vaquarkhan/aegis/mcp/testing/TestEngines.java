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
package io.github.vaquarkhan.aegis.mcp.testing;

import io.github.vaquarkhan.aegis.engine.GovernanceEngine;
import io.github.vaquarkhan.aegis.engine.GovernanceSettings;
import io.github.vaquarkhan.aegis.engine.approval.ApprovalService;
import io.github.vaquarkhan.aegis.engine.approval.VrpService;
import io.github.vaquarkhan.aegis.engine.audit.InMemoryAuditSink;
import io.github.vaquarkhan.aegis.engine.catalog.CatalogIntegrity;
import io.github.vaquarkhan.aegis.engine.catalog.DryRunSpec;
import io.github.vaquarkhan.aegis.engine.catalog.GovernedTool;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.control.CircuitBreakers;
import io.github.vaquarkhan.aegis.engine.control.EgressGuard;
import io.github.vaquarkhan.aegis.engine.control.InjectionScreen;
import io.github.vaquarkhan.aegis.engine.control.OutputGuard;
import io.github.vaquarkhan.aegis.engine.control.RateLimiter;
import io.github.vaquarkhan.aegis.engine.control.RulePolicy;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Programmatic engine for protocol tests (the config module builds the same thing from YAML/JSON). */
public final class TestEngines {

    private TestEngines() {}

    public static final CallerIdentity ALICE = CallerIdentity.of("alice", Set.of("analysts"), "test", "test");
    public static final CallerIdentity OLGA = CallerIdentity.of("olga", Set.of("analysts", "operators"), "test", "test");
    public static final CallerIdentity BOB = CallerIdentity.of("bob", Set.of("approvers"), "test", "test");

    public static final class Built {
        public final GovernanceEngine engine;
        public final InMemoryAuditSink audit;
        public final ApprovalService approvals;

        Built(GovernanceEngine engine, InMemoryAuditSink audit, ApprovalService approvals) {
            this.engine = engine;
            this.audit = audit;
            this.approvals = approvals;
        }
    }

    /** @param classes tool name to class; unlisted tools default to DESTRUCTIVE (fail-safe) */
    public static Built build(List<ToolSource> sources, Map<String, ToolClass> classes) throws Exception {
        return build(sources, classes, null);
    }

    /** @param journal audit sink the engine writes to; null uses a fresh in-memory journal */
    public static Built build(List<ToolSource> sources, Map<String, ToolClass> classes,
                              io.github.vaquarkhan.aegis.engine.spi.AuditSink journal) throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryStateStore state = new InMemoryStateStore();
        InMemoryAuditSink audit = new InMemoryAuditSink();
        io.github.vaquarkhan.aegis.engine.spi.AuditSink sink = journal == null ? audit : journal;
        byte[] key = ("test-signing-key-" + "0123456789abcdef-xyz").getBytes(StandardCharsets.UTF_8);
        ToolCatalog catalog = new ToolCatalog(sources, (sourceId, st) -> Optional.of(new GovernedTool(
                st.descriptor().name(), sourceId, st.descriptor(),
                classes.getOrDefault(st.descriptor().name(), ToolClass.DESTRUCTIVE),
                List.of(), DryRunSpec.GATEWAY, Set.of(), st.executor(), 5_000)),
                new CatalogIntegrity(CatalogIntegrity.Mode.TOFU, Map.of(), null), InjectionScreen.defaults(), true, false, e -> { });
        catalog.load();
        ApprovalService approvals = new ApprovalService(key, state, Set.of("approvers"), Duration.ofMinutes(30), Duration.ofMinutes(10), clock,
                sink, "test");
        VrpService vrp = new VrpService(key, state, Duration.ofMinutes(10), clock);
        EgressGuard egress = new EgressGuard(List.of("127.0.0.1", "localhost"), List.of("127.0.0.0/8"), Set.of("http", "https"), null);
        RulePolicy policy = new RulePolicy(List.of(
                new RulePolicy.Rule("read", RulePolicy.Effect.ALLOW, Set.of("analysts"), Set.of(), List.of(), Set.of(ToolClass.READ), List.of()),
                new RulePolicy.Rule("write", RulePolicy.Effect.ALLOW, Set.of("operators"), Set.of(), List.of(), Set.of(), List.of())));
        GovernanceSettings settings = new GovernanceSettings("test", Set.of("operators"), Set.of(), null, true, List.of(),
                RateLimiter.DISABLED, InjectionScreen.defaults(), OutputGuard.defaults(), egress, List.of(), true, false);
        GovernanceEngine engine = new GovernanceEngine(settings, catalog, policy, approvals, vrp, state, sink, null,
                new CircuitBreakers(5, 10_000, clock), clock, true);
        return new Built(engine, audit, approvals);
    }
}
