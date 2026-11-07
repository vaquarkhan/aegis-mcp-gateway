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
package io.github.vaquarkhan.aegis.engine.model;

/**
 * Stable deny codes and step numbers. Every code and step below is reused verbatim from the Aegis 0.1
 * {@code core.interceptor.Decision} registry, except the two marked "2.0 addition", which are
 * proposed for the registry (see docs/DENY-CODES.md). Audit records render a denial as
 * {@code DENIED:<code>:step<N>}, identical to 0.1.
 */
public final class DenyCodes {

    private DenyCodes() {}

    // ---- 0.1 registry (unchanged)
    public static final String NOT_EXPOSED = "NOT_EXPOSED";
    public static final String READONLY_CALLER = "READONLY_CALLER";
    public static final String SCOPE_DENIED = "SCOPE_DENIED";
    public static final String POLICY_DENIED = "POLICY_DENIED";
    public static final String APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
    public static final String EGRESS_DENIED = "EGRESS_DENIED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String BREAKER_OPEN = "BREAKER_OPEN";
    public static final String PROMPT_INJECTION = "PROMPT_INJECTION";
    public static final String VRP_FAILED = "VRP_FAILED";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String BACKEND_ERROR = "BACKEND_ERROR";
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String ALLOWED = "ALLOWED";

    // ---- 2.0 additions (proposed)
    /** The tool definition no longer matches its pinned digest (rug-pull / tool poisoning). */
    public static final String CATALOG_INTEGRITY = "CATALOG_INTEGRITY";
    /** The audit journal could not durably record the decision, so the call is not executed. */
    public static final String AUDIT_UNAVAILABLE = "AUDIT_UNAVAILABLE";

    // ---- 0.1 step numbers (unchanged)
    public static final int STEP_EXPOSURE = 1;
    public static final int STEP_SCOPE = 2;
    public static final int STEP_POLICY = 3;
    public static final int STEP_APPROVAL = 4;
    public static final int STEP_EGRESS = 5;
    public static final int STEP_RATE = 6;
    public static final int STEP_BREAKER = 7;
    public static final int STEP_INJECTION = 8;
    public static final int STEP_VRP = 9;
    public static final int STEP_EXECUTE = 10;
}
