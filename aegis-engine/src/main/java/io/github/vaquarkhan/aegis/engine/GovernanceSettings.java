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

import io.github.vaquarkhan.aegis.engine.control.EgressGuard;
import io.github.vaquarkhan.aegis.engine.control.HookBinding;
import io.github.vaquarkhan.aegis.engine.control.InjectionScreen;
import io.github.vaquarkhan.aegis.engine.control.OutputGuard;
import io.github.vaquarkhan.aegis.engine.control.RateLimiter;
import io.github.vaquarkhan.aegis.engine.control.ScopeRule;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.spi.DecisionListener;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Engine-wide governance settings. Built from configuration by the config module; every field has
 * a secure default.
 *
 * @param gatewayName             identifies this gateway in audit and tokens
 * @param writeGroups             groups allowed to call MUTATE tools at all (0.1 "write unlock")
 * @param destructiveGroups       groups allowed to call DESTRUCTIVE tools; empty means writeGroups
 * @param approvalRequiredFor     classes that need a per-call approval token (default MUTATE, DESTRUCTIVE)
 * @param vrpEnabled              DESTRUCTIVE calls need a dry-run receipt (default true)
 * @param scopes                  resource scopes; empty disables step 2 scope checks (policy still applies)
 * @param rateLimiter             step 6 limits
 * @param argumentScreen          step 8 argument screening
 * @param outputGuard             output bounding and redaction
 * @param egressGuard             step 5 egress control
 * @param hooks                   extension controls, in order, each with its mode and conditions
 * @param advertiseControlArguments advertise 0.1 control arguments on write tools in tools/list
 * @param auditArguments          include full backend arguments in audit (default false: digest only)
 * @param listeners               observers of final outcomes (metrics); they cannot change a decision
 */
public record GovernanceSettings(
        String gatewayName,
        Set<String> writeGroups,
        Set<String> destructiveGroups,
        Set<ToolClass> approvalRequiredFor,
        boolean vrpEnabled,
        List<ScopeRule> scopes,
        RateLimiter rateLimiter,
        InjectionScreen argumentScreen,
        OutputGuard outputGuard,
        EgressGuard egressGuard,
        List<HookBinding> hooks,
        boolean advertiseControlArguments,
        boolean auditArguments,
        List<DecisionListener> listeners) {

    /** Settings without decision listeners (metrics off). */
    public GovernanceSettings(String gatewayName, Set<String> writeGroups, Set<String> destructiveGroups,
                              Set<ToolClass> approvalRequiredFor, boolean vrpEnabled, List<ScopeRule> scopes,
                              RateLimiter rateLimiter, InjectionScreen argumentScreen, OutputGuard outputGuard,
                              EgressGuard egressGuard, List<HookBinding> hooks, boolean advertiseControlArguments,
                              boolean auditArguments) {
        this(gatewayName, writeGroups, destructiveGroups, approvalRequiredFor, vrpEnabled, scopes, rateLimiter,
                argumentScreen, outputGuard, egressGuard, hooks, advertiseControlArguments, auditArguments, List.of());
    }

    public GovernanceSettings {
        listeners = listeners == null ? List.of() : List.copyOf(listeners);
        gatewayName = gatewayName == null || gatewayName.isBlank() ? "aegis" : gatewayName;
        writeGroups = writeGroups == null ? Set.of() : Set.copyOf(writeGroups);
        destructiveGroups = destructiveGroups == null || destructiveGroups.isEmpty() ? writeGroups : Set.copyOf(destructiveGroups);
        approvalRequiredFor = approvalRequiredFor == null
                ? Set.copyOf(EnumSet.of(ToolClass.MUTATE, ToolClass.DESTRUCTIVE)) : Set.copyOf(approvalRequiredFor);
        if (approvalRequiredFor.contains(ToolClass.READ)) {
            throw new IllegalArgumentException("approval for READ tools is not supported; classify the tool as MUTATE instead");
        }
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        rateLimiter = rateLimiter == null ? RateLimiter.DISABLED : rateLimiter;
        argumentScreen = argumentScreen == null ? InjectionScreen.defaults() : argumentScreen;
        outputGuard = outputGuard == null ? OutputGuard.defaults() : outputGuard;
        if (egressGuard == null) {
            throw new IllegalArgumentException("an egress guard is required (use an empty allow list to deny all egress)");
        }
        hooks = hooks == null ? List.of() : List.copyOf(hooks);
    }
}
