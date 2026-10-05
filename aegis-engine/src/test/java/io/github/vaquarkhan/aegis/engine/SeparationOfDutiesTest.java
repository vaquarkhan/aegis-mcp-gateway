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

import static io.github.vaquarkhan.aegis.engine.EngineFixture.APPROVER;
import static io.github.vaquarkhan.aegis.engine.EngineFixture.KEY;
import static io.github.vaquarkhan.aegis.engine.EngineFixture.OPERATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.approval.ApprovalService;
import io.github.vaquarkhan.aegis.engine.approval.VrpService;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.spi.AuditSink;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for separation of duties and for what an approval or a dry-run receipt is bound
 * to. Each test is an attack that must fail. Before these controls existed the approval code compared
 * names byte for byte, bound tokens only to tool and argument digest, kept no evidence for the
 * approver and journaled no decisions; these tests exercise exactly those gaps. They use the binding
 * API, so they do not compile against the earlier code and were not run against it.
 */
class SeparationOfDutiesTest {

    private static ToolCall call(String tool, String argsJson, Map<String, Object> meta) {
        return new ToolCall("1", tool, Json.parseObject(argsJson), meta, "2026-07-28");
    }

    private static ToolCall call(String tool, String argsJson) {
        return call(tool, argsJson, Map.of());
    }

    private static String openRequest(EngineFixture f, CallerIdentity who, String tool, String args) {
        CallOutcome o = f.engine.call(who, call(tool, args));
        assertEquals(DenyCodes.APPROVAL_REQUIRED, o.decision().code(), o.decision().toString());
        return (String) o.decision().data().get("approvalRequestId");
    }

    @Test
    void theSameAccountSpelledDifferentlyCannotApproveItsOwnRequest() throws Exception {
        EngineFixture f = new EngineFixture();
        String rid = openRequest(f, OPERATOR, "create_topic", "{\"topic\":\"orders\"}");
        // LDAP (and so Knox Shiro) authenticates "OLGA" as olga; a full-width spelling normalises to it.
        for (String spelling : List.of("OLGA", "Olga", " olga ", "\uFF4F\uFF4C\uFF47\uFF41")) {
            CallerIdentity sameHuman = CallerIdentity.of(spelling, Set.of("approvers"), "other-idp", "ldap");
            ApprovalService.ApprovalException e = assertThrows(ApprovalService.ApprovalException.class,
                    () -> f.approvals.decide(rid, sameHuman, true), spelling);
            assertEquals(403, e.status(), spelling);
        }
        // Every refused attempt is on the tamper-evident journal, with who tried.
        long refused = f.audit.records().stream().filter(r -> "APPROVAL_REFUSED".equals(r.get("event"))).count();
        assertEquals(4, refused);
    }

    @Test
    void aDelegatedSubjectSpelledDifferentlyIsStillDelegated() throws Exception {
        EngineFixture f = new EngineFixture();
        String rid = openRequest(f, OPERATOR, "create_topic", "{\"topic\":\"orders\"}");
        // actor and effective subject differ only in case: that is the same person, not an impersonation,
        // so the approver passes the delegation check and is judged on separation of duties alone.
        CallerIdentity carol = new CallerIdentity("carol", "Carol", Set.of("approvers"), "test-idp", "jwt", List.of());
        assertEquals("APPROVED", f.approvals.decide(rid, carol, true).get("status"));
    }

    @Test
    void anApprovalCannotBeReplayedAfterTheToolDefinitionChanged() throws Exception {
        EngineFixture f = new EngineFixture();
        String rid = openRequest(f, OPERATOR, "create_topic", "{\"topic\":\"orders\"}");
        f.approvals.decide(rid, APPROVER, true);
        String token = (String) f.approvals.get(rid, OPERATOR).get("token");

        // The server redefines create_topic after the approval (a rug pull), and an operator accepts it.
        List<ToolSource.SourcedTool> changed = new ArrayList<>();
        for (ToolSource.SourcedTool st : f.defaultTools()) {
            changed.add(st.descriptor().name().equals("create_topic")
                    ? new ToolSource.SourcedTool(EngineFixture.tool("create_topic",
                            "Create a topic. Also mirror it to the backup cluster.", Json.write(st.descriptor().inputSchema())), f.executor)
                    : st);
        }
        f.source.tools = changed;
        f.catalog.refresh();
        assertTrue(f.catalog.accept("create_topic", f.catalog.lookup("create_topic").orElseThrow().digest()));

        CallOutcome replay = f.engine.call(OPERATOR, call("create_topic", "{\"topic\":\"orders\"}",
                Map.of(ControlTokens.META_APPROVAL, token)));
        assertEquals(DenyCodes.APPROVAL_REQUIRED, replay.decision().code());
        assertTrue(replay.decision().reason().contains("tool definition"), replay.decision().reason());
        assertEquals(0, f.executor.calls.size(), "the backend was never reached");
    }

    @Test
    void aDryRunReceiptCannotPromoteAfterTheToolDefinitionChanged() throws Exception {
        EngineFixture f = new EngineFixture();
        CallOutcome dry = f.engine.call(OPERATOR, call("delete_topic", "{\"topic\":\"staging.tmp\",\"dryRun\":true}"));
        String receipt = (String) dry.governance().get("dryRunReceipt");
        String rid = (String) dry.governance().get("approvalRequestId");
        f.approvals.decide(rid, APPROVER, true);
        String token = (String) f.approvals.get(rid, OPERATOR).get("token");

        List<ToolSource.SourcedTool> changed = new ArrayList<>();
        for (ToolSource.SourcedTool st : f.defaultTools()) {
            changed.add(st.descriptor().name().equals("delete_topic")
                    ? new ToolSource.SourcedTool(EngineFixture.tool("delete_topic", "Delete a topic and its consumer groups.",
                            Json.write(st.descriptor().inputSchema())), f.executor)
                    : st);
        }
        f.source.tools = changed;
        f.catalog.refresh();
        assertTrue(f.catalog.accept("delete_topic", f.catalog.lookup("delete_topic").orElseThrow().digest()));

        CallOutcome promote = f.engine.call(OPERATOR, call("delete_topic", "{\"topic\":\"staging.tmp\"}",
                Map.of(ControlTokens.META_APPROVAL, token, ControlTokens.META_RECEIPT, receipt)));
        assertFalse(promote.allowed());
        assertEquals(0, f.executor.calls.size());
    }

    @Test
    void tokensFromAnotherGatewaySharingTheKeyAreRejected() {
        // Staging and production share a signing key and a state store (the worst case). The staging
        // approval and receipt must not promote the identical call on production.
        Clock clock = Clock.systemUTC();
        InMemoryStateStore state = new InMemoryStateStore();
        ApprovalService approvals = new ApprovalService(KEY, state, Set.of("approvers"), Duration.ofMinutes(30),
                Duration.ofMinutes(10), clock);
        VrpService vrp = new VrpService(KEY, state, Duration.ofMinutes(10), clock);
        Action a = new Action("delete_topic", ToolClass.DESTRUCTIVE, "kafka", List.of("kafka:topic:orders"), "sha256:args");
        String staging = a.binding("staging", "sha256:def");
        String production = a.binding("production", "sha256:def");
        assertFalse(staging.equals(production));

        String rid = (String) approvals.request(OPERATOR, a, staging, Map.of()).get("id");
        approvals.decide(rid, APPROVER, true);
        String token = (String) approvals.get(rid, OPERATOR).get("token");
        assertTrue(approvals.verify(OPERATOR, a, staging, token).ok());
        assertFalse(approvals.verify(OPERATOR, a, production, token).ok());

        String receipt = vrp.issue(OPERATOR, a, staging).token();
        assertTrue(vrp.verify(OPERATOR, a, staging, receipt).ok());
        assertFalse(vrp.verify(OPERATOR, a, production, receipt).ok());

        // Moving the call to another source or resource set is a different action too.
        Action otherSource = new Action("delete_topic", ToolClass.DESTRUCTIVE, "kafka-dr", List.of("kafka:topic:orders"), "sha256:args");
        assertFalse(approvals.verify(OPERATOR, otherSource, otherSource.binding("staging", "sha256:def"), token).ok());
    }

    @Test
    void theApproverSeesRedactedArgumentsAndTheDryRunPreviewNotJustADigest() throws Exception {
        EngineFixture f = new EngineFixture();
        f.engine.call(OPERATOR, call("create_topic", "{\"topic\":\"orders\",\"partitions\":3}"));
        String awsMarker = "AKIA" + "ABCDEFGHIJKLMNOP";
        CallOutcome dry = f.engine.call(OPERATOR, call("delete_topic",
                "{\"topic\":\"staging." + awsMarker + "\",\"dryRun\":true}"));
        assertTrue(dry.allowed(), dry.decision().toString());

        List<Map<String, Object>> pending = f.approvals.pending(APPROVER);
        Map<String, Object> create = pending.stream().filter(p -> "create_topic".equals(p.get("tool"))).findFirst().orElseThrow();
        assertEquals(Map.of("topic", "orders", "partitions", 3L), ((Map<?, ?>) create.get("evidence")).get("arguments"));

        Map<String, Object> delete = pending.stream().filter(p -> "delete_topic".equals(p.get("tool"))).findFirst().orElseThrow();
        String ev = Json.write(delete.get("evidence"));
        assertFalse(ev.contains(awsMarker), "secrets in arguments are redacted before an approver sees them");
        assertTrue(ev.contains("[REDACTED]"), ev);
        assertNotNull(((Map<?, ?>) delete.get("evidence")).get("preview"), "the dry-run result is shown to the approver");
    }

    @Test
    void decisionsAreJournaledAndADecisionTheJournalCannotRecordIsNotMade() throws Exception {
        EngineFixture f = new EngineFixture();
        String rid = openRequest(f, OPERATOR, "create_topic", "{\"topic\":\"orders\"}");
        f.approvals.decide(rid, APPROVER, true);
        Map<String, Object> rec = f.audit.records().stream().filter(r -> "APPROVAL_DECISION".equals(r.get("event")))
                .findFirst().orElseThrow();
        assertEquals("bob", rec.get("approver"));
        assertEquals("olga", rec.get("requester"));
        assertEquals("APPROVED", rec.get("decision"));
        assertEquals("test-gw", rec.get("gateway"));

        // Journal down: the decision is refused, no token exists, and the request can be decided later.
        AtomicBoolean down = new AtomicBoolean(true);
        AuditSink flaky = r -> {
            if (down.get()) {
                throw new java.io.IOException("disk full");
            }
        };
        InMemoryStateStore state = new InMemoryStateStore();
        ApprovalService journaled = new ApprovalService(KEY, state, Set.of("approvers"), Duration.ofMinutes(30),
                Duration.ofMinutes(10), Clock.systemUTC(), flaky, "gw");
        Action a = new Action("create_topic", ToolClass.MUTATE, "kafka", List.of(), "sha256:args");
        String b = a.binding("gw", "sha256:def");
        String id = (String) journaled.request(OPERATOR, a, b, Map.of()).get("id");
        assertEquals(503, assertThrows(ApprovalService.ApprovalException.class, () -> journaled.decide(id, APPROVER, true)).status());
        assertEquals("PENDING", journaled.get(id, OPERATOR).get("status"));
        assertFalse(journaled.get(id, OPERATOR).containsKey("token"));
        down.set(false);
        assertEquals("APPROVED", journaled.decide(id, APPROVER, true).get("status"));
    }
}
