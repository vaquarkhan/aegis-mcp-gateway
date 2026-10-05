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

import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.spi.PolicyDecisionPoint;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in declarative policy: an ordered list of allow/deny rules with deny-overrides and a
 * default of deny. Rules match on principal (groups or actors), tool name globs, tool classes and
 * resource globs. Enough for most deployments without running a separate policy engine; OPA or
 * another PDP can be composed with it (all must allow).
 */
public final class RulePolicy implements PolicyDecisionPoint {

    public enum Effect { ALLOW, DENY }

    /**
     * @param id        rule id (appears in the decision reason)
     * @param effect    ALLOW or DENY
     * @param groups    caller groups; {@code *} matches any authenticated caller
     * @param actors    caller actors (exact); empty means "not constrained by actor"
     * @param tools     tool name globs; empty means any tool
     * @param classes   tool classes; empty means any class
     * @param resources resource globs; empty means any. For ALLOW every action resource must match
     *                  one glob; for DENY any matching resource triggers the rule.
     */
    public record Rule(String id, Effect effect, Set<String> groups, Set<String> actors, List<String> tools,
                       Set<ToolClass> classes, List<String> resources) {
        public Rule {
            groups = groups == null ? Set.of() : Set.copyOf(groups);
            actors = actors == null ? Set.of() : Set.copyOf(actors);
            tools = tools == null ? List.of() : List.copyOf(tools);
            classes = classes == null ? Set.of() : Set.copyOf(classes);
            resources = resources == null ? List.of() : List.copyOf(resources);
            if (groups.isEmpty() && actors.isEmpty()) {
                throw new IllegalArgumentException("policy rule " + id + " must name groups or actors (use \"*\" for any caller)");
            }
        }

        boolean principalMatches(CallerIdentity c) {
            boolean g = groups.contains("*") || c.inAnyGroup(groups);
            boolean a = actors.contains(c.actor()) || actors.contains(c.effectiveSubject());
            return g || a;
        }

        boolean matches(CallerIdentity c, Action action, boolean forAllow) {
            if (!principalMatches(c)) {
                return false;
            }
            if (!tools.isEmpty() && !Glob.anyMatches(tools, action.tool())) {
                return false;
            }
            if (!classes.isEmpty() && !classes.contains(action.toolClass())) {
                return false;
            }
            if (resources.isEmpty()) {
                return true;
            }
            if (forAllow) {
                // A resource-scoped ALLOW must never grant a call that names no resource
                // (the empty "for all" would otherwise be vacuously true).
                if (action.resources().isEmpty()) {
                    return false;
                }
                for (String r : action.resources()) {
                    if (!Glob.anyMatches(resources, r)) {
                        return false;
                    }
                }
                return true;
            }
            for (String r : action.resources()) {
                if (Glob.anyMatches(resources, r)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Rule> rules;

    public RulePolicy(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public Result decide(CallerIdentity caller, Action action, Map<String, Object> backendArguments) {
        for (Rule r : rules) {
            if (r.effect() == Effect.DENY && r.matches(caller, action, false)) {
                return Result.deny("denied by rule " + r.id());
            }
        }
        for (Rule r : rules) {
            if (r.effect() == Effect.ALLOW && r.matches(caller, action, true)) {
                return Result.allow("allowed by rule " + r.id());
            }
        }
        return Result.deny("no policy rule allows this action (default deny)");
    }

    /** Every PDP must allow; the first denial (or exception) wins. */
    public static PolicyDecisionPoint allOf(List<PolicyDecisionPoint> pdps) {
        List<PolicyDecisionPoint> list = List.copyOf(pdps);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("at least one policy decision point is required");
        }
        return (caller, action, args) -> {
            Result last = null;
            for (PolicyDecisionPoint p : list) {
                Result r = p.decide(caller, action, args);
                if (r == null || !r.allow()) {
                    return r == null ? Result.deny("policy returned no decision") : r;
                }
                last = r;
            }
            return last;
        };
    }
}
