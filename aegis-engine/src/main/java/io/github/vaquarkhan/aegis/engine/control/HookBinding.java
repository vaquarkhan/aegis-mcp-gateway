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

import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One configured hook plus the operator's decision about when it runs and what its verdict means.
 *
 * <p>A hook on its own says only "allow" or "deny". The binding says where it applies and how
 * strongly, which is what makes a custom control safe to introduce into a running deployment: start
 * it in AUDIT to see what it would have blocked, then switch it to ENFORCE.
 *
 * @param hook       the control
 * @param mode       ENFORCE denies (fail-closed, including on a hook error), AUDIT records the
 *                   violation in the audit journal and continues, OFF never runs the hook
 * @param operations operations the hook sees; empty means all of them
 * @param methods    tool-name or MCP-method globs; empty means any
 * @param groups     caller groups; empty means any caller
 * @param sources    configured source ids; empty means any source
 */
public record HookBinding(GovernanceHook hook, ControlMode mode, Set<GovernanceHook.Operation> operations,
                          List<String> methods, Set<String> groups, Set<String> sources) {

    public HookBinding {
        if (hook == null) {
            throw new IllegalArgumentException("a hook binding needs a hook");
        }
        mode = mode == null ? ControlMode.ENFORCE : mode;
        operations = operations == null || operations.isEmpty()
                ? Set.copyOf(EnumSet.allOf(GovernanceHook.Operation.class)) : Set.copyOf(operations);
        methods = methods == null ? List.of() : List.copyOf(methods);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        sources = sources == null ? Set.of() : Set.copyOf(sources);
    }

    /** Enforcing, for every operation, with no conditions (the default for a programmatically added hook). */
    public static HookBinding enforceAll(GovernanceHook hook) {
        return new HookBinding(hook, ControlMode.ENFORCE, null, null, null, null);
    }

    /** True when this hook must run for this operation. */
    public boolean applies(GovernanceHook.Context context) {
        if (mode == ControlMode.OFF) {
            return false;
        }
        if (!operations.contains(context.operation())) {
            return false;
        }
        if (!methods.isEmpty() && !Glob.anyMatches(methods, context.method() == null ? "" : context.method())) {
            return false;
        }
        if (!groups.isEmpty() && !context.caller().inAnyGroup(groups)) {
            return false;
        }
        return sources.isEmpty() || sources.contains(context.sourceId() == null ? "" : context.sourceId());
    }

    /** True when a violation or a hook failure must stop the operation. */
    public boolean enforcing() {
        return mode == ControlMode.ENFORCE;
    }
}
