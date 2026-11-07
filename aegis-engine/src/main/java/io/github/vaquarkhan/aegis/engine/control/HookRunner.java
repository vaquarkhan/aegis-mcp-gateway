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
package io.github.vaquarkhan.aegis.engine.control;

import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs configured {@link GovernanceHook}s for one operation. Shared by the tool chain and the
 * content chain so an operator's custom control behaves identically for {@code tools/call},
 * {@code resources/read}, {@code prompts/get} and {@code completion/complete}.
 *
 * <p>Two rules make a hook safe to add to a running deployment:
 * <ul>
 *   <li><b>Fail-closed in ENFORCE.</b> A hook that throws, times out or returns nothing denies the
 *       operation (before phase) or withholds the output (after phase). It never fails open.</li>
 *   <li><b>No effect in AUDIT.</b> The hook still runs, and what it would have done is written to the
 *       audit record under {@code hookAudit}, but it cannot deny and it cannot rewrite the response.
 *       That is what makes AUDIT a true dry run of a new control rather than a partial rollout.</li>
 * </ul>
 *
 * <p>A hook may only deny with a code from the existing registry ({@code POLICY_DENIED},
 * {@code INVALID_INPUT}, {@code PROMPT_INJECTION}); anything else becomes {@code POLICY_DENIED}, so a
 * deployment-specific control cannot invent wire codes that clients have never seen.
 */
public final class HookRunner {

    private static final System.Logger LOG = System.getLogger(HookRunner.class.getName());
    private static final Set<String> HOOK_CODES =
            Set.of(DenyCodes.POLICY_DENIED, DenyCodes.INVALID_INPUT, DenyCodes.PROMPT_INJECTION);

    private final List<HookBinding> bindings;

    public HookRunner(List<HookBinding> bindings) {
        this.bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    public boolean isEmpty() {
        return bindings.isEmpty();
    }

    public List<HookBinding> bindings() {
        return bindings;
    }

    /** Outcome of the content after phase: the content to return, or the denial that stops it. */
    public record Content(Map<String, Object> value, Decision denial) {

        public boolean denied() {
            return denial != null;
        }
    }

    /**
     * Before phase, for every hook bound to this operation, in configured order.
     *
     * @param notes collects what AUDIT-mode hooks would have done; written to the audit record
     * @return the first ENFORCE denial, or null to continue
     */
    public Decision before(GovernanceHook.Context context, int step, List<String> notes) {
        for (HookBinding b : bindings) {
            if (!b.applies(context)) {
                continue;
            }
            GovernanceHook h = b.hook();
            Decision d;
            try {
                d = h.before(context);
            } catch (GovernanceHook.HookDenied e) {
                if (b.enforcing()) {
                    return Decision.deny(code(e.code()), step, "hook " + h.name() + ": " + e.getMessage());
                }
                notes.add(h.name() + ": would deny (" + e.getMessage() + ")");
                continue;
            } catch (Exception e) {
                if (b.enforcing()) {
                    return Decision.deny(DenyCodes.POLICY_DENIED, step, failure(h, e));
                }
                notes.add(h.name() + ": failed (" + e.getClass().getSimpleName() + ")");
                continue;
            }
            if (d == null || d.denied()) {
                String reason = d == null ? "no decision" : d.reason();
                if (b.enforcing()) {
                    return Decision.deny(d == null ? DenyCodes.POLICY_DENIED : code(d.code()), step,
                            "hook " + h.name() + ": " + reason);
                }
                notes.add(h.name() + ": would deny (" + reason + ")");
            }
        }
        return null;
    }

    /**
     * Tool after phase. A withheld output is an error result rather than a protocol error, because the
     * call already ran: the model must be told its output was suppressed, not that the call failed.
     *
     * @return the result to return; never null
     */
    public ToolResult afterTool(GovernanceHook.Context context, ToolResult result, List<String> notes) {
        ToolResult current = result;
        for (HookBinding b : bindings) {
            if (!b.applies(context)) {
                continue;
            }
            GovernanceHook h = b.hook();
            try {
                ToolResult next = h.afterTool(context, current);
                if (next == null) {
                    if (b.enforcing()) {
                        current = withheld(h.name(), "the hook returned no result");
                    } else {
                        notes.add(h.name() + ": would withhold the output");
                    }
                } else if (b.enforcing()) {
                    current = next;
                }
            } catch (GovernanceHook.HookDenied e) {
                if (b.enforcing()) {
                    current = withheld(h.name(), e.getMessage());
                } else {
                    notes.add(h.name() + ": would withhold the output (" + e.getMessage() + ")");
                }
            } catch (Exception e) {
                if (b.enforcing()) {
                    current = withheld(h.name(), "the hook failed (" + e.getClass().getSimpleName() + ")");
                } else {
                    notes.add(h.name() + ": failed (" + e.getClass().getSimpleName() + ")");
                }
            }
        }
        return current;
    }

    /**
     * Content after phase, on the rebuilt spec-only result and before redaction, the size bound and
     * the content screen, so anything a hook adds is still guarded.
     */
    public Content afterContent(GovernanceHook.Context context, Map<String, Object> result, int step, List<String> notes) {
        Map<String, Object> current = result;
        for (HookBinding b : bindings) {
            if (!b.applies(context)) {
                continue;
            }
            GovernanceHook h = b.hook();
            try {
                Map<String, Object> next = h.afterContent(context, current);
                if (next == null) {
                    if (b.enforcing()) {
                        return new Content(null, Decision.deny(DenyCodes.POLICY_DENIED, step,
                                "hook " + h.name() + " withheld the content"));
                    }
                    notes.add(h.name() + ": would withhold the content");
                } else if (b.enforcing()) {
                    current = next;
                }
            } catch (GovernanceHook.HookDenied e) {
                if (b.enforcing()) {
                    return new Content(null, Decision.deny(code(e.code()), step, "hook " + h.name() + ": " + e.getMessage()));
                }
                notes.add(h.name() + ": would withhold the content (" + e.getMessage() + ")");
            } catch (Exception e) {
                if (b.enforcing()) {
                    return new Content(null, Decision.deny(DenyCodes.POLICY_DENIED, step, failure(h, e)));
                }
                notes.add(h.name() + ": failed (" + e.getClass().getSimpleName() + ")");
            }
        }
        return new Content(current, null);
    }

    /** Closes every hook. Called once, when the gateway shuts down. */
    public void close() {
        for (HookBinding b : bindings) {
            try {
                b.hook().close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "hook " + b.hook().name() + " failed to close", e);
            }
        }
    }

    private static ToolResult withheld(String hook, String why) {
        return ToolResult.text("Output withheld by Aegis: hook " + hook + ": " + why, true);
    }

    /** A hook error names the hook and the failure type, never the backend's own message. */
    private static String failure(GovernanceHook h, Exception e) {
        return "hook " + h.name() + " failed (" + e.getClass().getSimpleName() + ")";
    }

    private static String code(String requested) {
        return requested != null && HOOK_CODES.contains(requested) ? requested : DenyCodes.POLICY_DENIED;
    }
}
