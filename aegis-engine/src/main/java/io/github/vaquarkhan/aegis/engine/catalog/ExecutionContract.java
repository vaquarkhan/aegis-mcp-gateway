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
package io.github.vaquarkhan.aegis.engine.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trusted operator statement of how a tool turns arguments into an effect. Upstream annotations cannot
 * supply or lower this contract. Complete-control mode rejects UNKNOWN and FREE_FORM: approval may grant
 * a reviewed operation, but it must never make arbitrary shell, script, code or expression text safe.
 *
 * @param kind        reviewed execution shape
 * @param operationId stable operation identifier for TYPED_OPERATION (for example report.monthly)
 */
public record ExecutionContract(Kind kind, String operationId) {

    public enum Kind {
        /** No operator statement. Accepted only outside complete-control mode for compatibility. */
        UNKNOWN,
        /** Fixed adapter/API operation with typed fields, never an evaluator. */
        TYPED_OPERATION,
        /** One read-only SQL statement under the sql-readonly allow-list and a read-only credential. */
        SQL_READ,
        /** File access through an opaque ResourceBroker handle, never an ambient host path. */
        FILE_BROKERED,
        /** Network read through a reviewed destination contract and enforced egress policy. */
        NETWORK_READ,
        /** Pure data transformation with no filesystem, process or network capability. */
        PURE_DATA,
        /** Arbitrary command, code, script, expression or unrestricted SQL. Always prohibited in complete control. */
        FREE_FORM
    }

    public static final ExecutionContract UNKNOWN = new ExecutionContract(Kind.UNKNOWN, null);

    public ExecutionContract {
        kind = kind == null ? Kind.UNKNOWN : kind;
        operationId = operationId == null || operationId.isBlank() ? null : operationId;
        if (kind == Kind.TYPED_OPERATION && operationId == null) {
            throw new IllegalArgumentException("TYPED_OPERATION needs a non-blank operationId");
        }
        if (kind != Kind.TYPED_OPERATION && operationId != null) {
            throw new IllegalArgumentException("operationId applies only to TYPED_OPERATION");
        }
        if (operationId != null && !operationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("operationId must be a 1..128 character identifier");
        }
    }

    public boolean allowedInCompleteControl() {
        return kind != Kind.UNKNOWN && kind != Kind.FREE_FORM;
    }

    /** Stable policy form included in action binding and audit metadata. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind.name());
        if (operationId != null) {
            out.put("operationId", operationId);
        }
        return out;
    }
}
