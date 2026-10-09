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

import static io.github.vaquarkhan.aegis.engine.EngineFixture.ANALYST;
import static io.github.vaquarkhan.aegis.engine.EngineFixture.OPERATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.control.ControlMode;
import io.github.vaquarkhan.aegis.engine.control.HookBinding;
import io.github.vaquarkhan.aegis.engine.control.RateLimiter;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Hook modes, conditions and lifecycle on the tool chain.
 *
 * <p>The reason these are tested as carefully as the built-in controls: a hook is the one place a
 * deployment can add its own logic to the decision path, so the ways it can go wrong (throwing,
 * hanging, denying with a code nobody has seen, quietly being skipped) are the ways a deployment can
 * lose its guarantees. AUDIT mode in particular has to be genuinely inert, or "try it in audit first"
 * is bad advice.
 */
class HooksTest {

    private static ToolCall call(String tool, String argsJson) {
        return new ToolCall("1", tool, Json.parseObject(argsJson), Map.of(), "2026-07-28");
    }

    /** Records every context it is given. */
    static final class Recorder implements GovernanceHook {
        final List<GovernanceHook.Context> seen = new ArrayList<>();
        final List<GovernanceHook.Context> afterSeen = new ArrayList<>();
        boolean closed;

        @Override
        public String name() {
            return "recorder";
        }

        @Override
        public Decision before(Context c) {
            seen.add(c);
            return Decision.allow();
        }

        @Override
        public ToolResult afterTool(Context c, ToolResult r) {
            afterSeen.add(c);
            return r;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static EngineFixture withHooks(HookBinding... hooks) throws Exception {
        return new EngineFixture(List.of(hooks), RateLimiter.DISABLED, 3);
    }

    private static Map<String, Object> lastRecord(EngineFixture f) {
        List<Map<String, Object>> records = f.audit.records();
        return records.get(records.size() - 1);
    }

    @Test
    void contextDescribesTheToolCallWithoutCarryingCredentials() throws Exception {
        Recorder rec = new Recorder();
        EngineFixture f = withHooks(HookBinding.enforceAll(rec));
        assertTrue(f.engine.call(ANALYST, call("list_topics", "{\"prefix\":\"app.\"}")).allowed());

        assertEquals(1, rec.seen.size());
        GovernanceHook.Context c = rec.seen.get(0);
        assertEquals(GovernanceHook.Operation.TOOL_CALL, c.operation());
        assertFalse(c.operation().isContent());
        assertEquals("list_topics", c.method());
        assertEquals("engine", c.sourceId());
        assertNotNull(c.tool());
        assertEquals("list_topics", c.tool().exposedName());
        assertEquals("app.", c.arguments().get("prefix"));
        assertFalse(c.dryRun());
        assertTrue(c.requestId().startsWith("req_"));
        assertEquals(32, c.traceId().length());
        assertEquals("alice", c.caller().actor());
        assertEquals(Set.of("analysts"), c.caller().groups());
        assertEquals(1, rec.afterSeen.size());
        assertEquals(c.requestId(), rec.afterSeen.get(0).requestId());
        // The context is a copy: a hook cannot reach in and change the arguments the backend will get.
        assertThrows(UnsupportedOperationException.class, () -> c.arguments().put("prefix", "hr."));
    }

    @Test
    void enforceDeniesAndAuditOnlyRecords() throws Exception {
        GovernanceHook deny = new GovernanceHook() {
            @Override
            public String name() {
                return "sensitive-prefix";
            }

            @Override
            public Decision before(Context c) {
                return String.valueOf(c.arguments().get("prefix")).startsWith("hr.")
                        ? Decision.deny(DenyCodes.PROMPT_INJECTION, 8, "hr topics are off limits") : Decision.allow();
            }
        };
        EngineFixture enforcing = withHooks(HookBinding.enforceAll(deny));
        CallOutcome denied = enforcing.engine.call(ANALYST, call("list_topics", "{\"prefix\":\"hr.\"}"));
        assertFalse(denied.allowed());
        assertEquals(DenyCodes.PROMPT_INJECTION, denied.decision().code(), "a registry code a hook may use is kept");
        assertEquals(DenyCodes.STEP_INJECTION, denied.decision().step());
        assertEquals(0, enforcing.executor.calls.size(), "the backend was never contacted");

        EngineFixture advisory = withHooks(new HookBinding(deny, ControlMode.AUDIT, null, null, null, null));
        CallOutcome allowed = advisory.engine.call(ANALYST, call("list_topics", "{\"prefix\":\"hr.\"}"));
        assertTrue(allowed.allowed(), "AUDIT records, it does not deny");
        assertEquals(1, advisory.executor.calls.size());
        String notes = String.valueOf(lastRecord(advisory).get("hookAudit"));
        assertTrue(notes.contains("sensitive-prefix: would deny (hr topics are off limits)"), notes);
        // An allowed call with nothing to report carries no hookAudit field at all.
        EngineFixture quiet = withHooks(HookBinding.enforceAll(new Recorder()));
        assertTrue(quiet.engine.call(ANALYST, call("list_topics", "{}")).allowed());
        assertFalse(lastRecord(quiet).containsKey("hookAudit"));
    }

    @Test
    void offIsNeverConsulted() throws Exception {
        Recorder rec = new Recorder();
        EngineFixture f = withHooks(new HookBinding(rec, ControlMode.OFF, null, null, null, null));
        assertTrue(f.engine.call(ANALYST, call("list_topics", "{}")).allowed());
        assertEquals(List.of(), rec.seen);
        assertEquals(List.of(), rec.afterSeen);
        assertFalse(lastRecord(f).containsKey("hookAudit"));
    }

    @Test
    void conditionsNarrowAHookToOperationsMethodsGroupsAndSources() throws Exception {
        Recorder contentOnly = new Recorder();
        Recorder writesOnly = new Recorder();
        Recorder operatorsOnly = new Recorder();
        Recorder otherSource = new Recorder();
        EngineFixture f = withHooks(
                new HookBinding(contentOnly, ControlMode.ENFORCE, Set.of(GovernanceHook.Operation.RESOURCE_READ), null, null, null),
                new HookBinding(writesOnly, ControlMode.ENFORCE, null, List.of("create_*", "delete_*"), null, null),
                new HookBinding(operatorsOnly, ControlMode.ENFORCE, null, null, Set.of("operators"), null),
                new HookBinding(otherSource, ControlMode.ENFORCE, null, null, null, Set.of("some-other-source")));

        assertTrue(f.engine.call(ANALYST, call("list_topics", "{}")).allowed());
        assertTrue(f.engine.call(OPERATOR, call("list_topics", "{}")).allowed());

        assertEquals(List.of(), contentOnly.seen, "a content-only hook never sees a tool call");
        assertEquals(List.of(), writesOnly.seen, "list_topics matches neither glob");
        assertEquals(List.of("olga"), operatorsOnly.seen.stream().map(c -> c.caller().actor()).toList());
        assertEquals(List.of(), otherSource.seen);
    }

    @Test
    void hooksRunInConfiguredOrderAndTheFirstDenialWins() throws Exception {
        List<String> order = new ArrayList<>();
        GovernanceHook first = named("first", order, false);
        GovernanceHook second = named("second", order, true);
        GovernanceHook third = named("third", order, false);
        EngineFixture f = withHooks(HookBinding.enforceAll(first), HookBinding.enforceAll(second),
                HookBinding.enforceAll(third));
        CallOutcome o = f.engine.call(ANALYST, call("list_topics", "{}"));
        assertFalse(o.allowed());
        assertTrue(o.decision().reason().contains("second"), o.decision().reason());
        assertEquals(List.of("first", "second"), order, "evaluation stops at the first denial");
    }

    private static GovernanceHook named(String name, List<String> order, boolean deny) {
        return new GovernanceHook() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Decision before(Context c) {
                order.add(name);
                return deny ? Decision.deny(DenyCodes.POLICY_DENIED, 8, "no") : Decision.allow();
            }
        };
    }

    @Test
    void anAfterHookCanRewriteOrWithholdTheOutput() throws Exception {
        GovernanceHook masker = new GovernanceHook() {
            @Override
            public String name() {
                return "masker";
            }

            @Override
            public ToolResult afterTool(Context c, ToolResult r) {
                return ToolResult.text("masked", r.isError());
            }
        };
        EngineFixture masked = withHooks(HookBinding.enforceAll(masker));
        CallOutcome o = masked.engine.call(ANALYST, call("list_topics", "{}"));
        assertTrue(o.allowed());
        assertTrue(Json.write(o.result().body()).contains("masked"));

        // AUDIT mode cannot rewrite either: the caller still gets the backend's own output.
        EngineFixture advisory = withHooks(new HookBinding(masker, ControlMode.AUDIT, null, null, null, null));
        assertTrue(Json.write(advisory.engine.call(ANALYST, call("list_topics", "{}")).result().body())
                .contains("\"text\":\"ok\""));

        GovernanceHook withholds = new GovernanceHook() {
            @Override
            public String name() {
                return "dlp";
            }

            @Override
            public ToolResult afterTool(Context c, ToolResult r) throws HookDenied {
                throw new HookDenied(DenyCodes.POLICY_DENIED, "the output contains customer records");
            }
        };
        EngineFixture withheld = withHooks(HookBinding.enforceAll(withholds));
        CallOutcome w = withheld.engine.call(ANALYST, call("list_topics", "{}"));
        // The call already ran, so this is an error result and not a protocol denial: the model has to
        // be told its output was suppressed rather than that the call failed.
        assertTrue(w.allowed());
        assertTrue(w.result().isError());
        String body = Json.write(w.result().body());
        assertTrue(body.contains("dlp"), body);
        assertTrue(body.contains("the output contains customer records"), body);
        assertFalse(body.contains("\"text\":\"ok\""), "the backend output does not leak past a withholding hook");
    }

    @Test
    void anAfterHookThatFailsWithholdsAndDoesNotRelayItsOwnMessage() throws Exception {
        GovernanceHook broken = new GovernanceHook() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public ToolResult afterTool(Context c, ToolResult r) {
                throw new IllegalStateException("connection refused to internal-dlp:9000");
            }
        };
        EngineFixture f = withHooks(HookBinding.enforceAll(broken));
        CallOutcome o = f.engine.call(ANALYST, call("list_topics", "{}"));
        assertTrue(o.result().isError());
        String body = Json.write(o.result().body());
        assertTrue(body.contains("withheld"), body);
        assertFalse(body.contains("internal-dlp"), "a hook's failure message is not sent to the caller");

        EngineFixture advisory = withHooks(new HookBinding(broken, ControlMode.AUDIT, null, null, null, null));
        CallOutcome a = advisory.engine.call(ANALYST, call("list_topics", "{}"));
        assertFalse(a.result().isError(), "a broken advisory hook does not affect the caller");
        assertTrue(String.valueOf(lastRecord(advisory).get("hookAudit")).contains("broken: failed (IllegalStateException)"));
    }

    @Test
    void closingTheEngineClosesEveryHookEvenIfOneThrows() throws Exception {
        Recorder ok = new Recorder();
        GovernanceHook badClose = new GovernanceHook() {
            @Override
            public String name() {
                return "bad-close";
            }

            @Override
            public void close() {
                throw new IllegalStateException("cannot close");
            }
        };
        EngineFixture f = withHooks(HookBinding.enforceAll(badClose), HookBinding.enforceAll(ok));
        f.engine.close();
        assertTrue(ok.closed, "a hook that throws on close must not stop the others from closing");
    }

    @Test
    void aHookSeesTheDryRunFlagAndTheWriteClassOfTheCall() throws Exception {
        Recorder rec = new Recorder();
        EngineFixture f = withHooks(HookBinding.enforceAll(rec));
        CallOutcome o = f.engine.call(OPERATOR, new ToolCall("1", "create_topic",
                Json.parseObject("{\"topic\":\"app.events\"}"),
                Map.of(ControlTokens.META_DRY_RUN, Boolean.TRUE), "2026-07-28"));
        assertTrue(o.allowed(), o.decision().toString());
        assertEquals(1, rec.seen.size());
        GovernanceHook.Context c = rec.seen.get(0);
        assertTrue(c.dryRun(), "a hook must be able to tell a preview from a real write");
        assertEquals("MUTATE", c.action().toolClass().name());
        assertEquals(1, c.action().resources().size());
        assertTrue(c.action().resources().get(0).endsWith("app.events"), String.valueOf(c.action().resources()));
        assertFalse(c.action().argsDigest().isBlank());
    }
}
