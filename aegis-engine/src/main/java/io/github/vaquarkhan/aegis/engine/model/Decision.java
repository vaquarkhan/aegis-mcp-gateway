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

import java.util.Map;

/**
 * Outcome of one control. The chain stops at the first denial.
 *
 * @param allowed true when the control passed
 * @param code    stable deny code from {@link DenyCodes}
 * @param step    0.1-compatible step number
 * @param reason  operator-facing reason; never contains argument values or secrets
 * @param data    optional machine-readable details returned to the client (e.g. approval request id)
 */
public record Decision(boolean allowed, String code, int step, String reason, Map<String, Object> data) {

    private static final Decision ALLOW = new Decision(true, DenyCodes.ALLOWED, 0, "", Map.of());

    public Decision {
        code = code == null ? "" : code;
        reason = reason == null ? "" : reason;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static Decision allow() {
        return ALLOW;
    }

    public static Decision deny(String code, int step, String reason) {
        return new Decision(false, code, step, reason, Map.of());
    }

    public static Decision deny(String code, int step, String reason, Map<String, Object> data) {
        return new Decision(false, code, step, reason, data);
    }

    public boolean denied() {
        return !allowed;
    }

    /** Audit rendering, identical to 0.1: {@code ALLOWED} or {@code DENIED:<code>:step<N>}. */
    public String auditOutcome() {
        return allowed ? DenyCodes.ALLOWED : "DENIED:" + code + ":step" + step;
    }
}
