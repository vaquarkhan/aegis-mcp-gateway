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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Governance control values a client supplies with a {@code tools/call}: the approval token, the
 * dry-run flag and the dry-run (VRP) receipt.
 *
 * <p>Preferred carrier (2.0): {@code params._meta} under the {@code io.github.vaquarkhan.aegis/}
 * prefix. MCP reserves {@code _meta} for exactly this kind of out-of-band metadata, so governance
 * values never leak into the tool's own arguments or reach a backend that does not know them.
 *
 * <p>Compatibility carrier (0.1): top-level arguments {@code approvalToken}, {@code dryRun},
 * {@code dryRunReceipt}. They are recognised ONLY when the upstream tool schema does not declare a
 * property of that name, so a backend tool that genuinely has a {@code dryRun} argument keeps it.
 * Supplying the same control in both carriers with different values is rejected as ambiguous.
 */
public record ControlTokens(String approvalToken, boolean dryRun, String dryRunReceipt) {

    public static final String META_PREFIX = "io.github.vaquarkhan.aegis/";
    public static final String META_APPROVAL = META_PREFIX + "approvalToken";
    public static final String META_DRY_RUN = META_PREFIX + "dryRun";
    public static final String META_RECEIPT = META_PREFIX + "dryRunReceipt";

    public static final String ARG_APPROVAL = "approvalToken";
    public static final String ARG_DRY_RUN = "dryRun";
    public static final String ARG_RECEIPT = "dryRunReceipt";

    public static final Set<String> LEGACY_ARGS = Set.of(ARG_APPROVAL, ARG_DRY_RUN, ARG_RECEIPT);

    public static final ControlTokens NONE = new ControlTokens(null, false, null);

    /** Result of splitting client arguments into control values and the arguments a backend sees. */
    public record Split(ControlTokens tokens, Map<String, Object> backendArguments) {}

    /**
     * @param meta             {@code params._meta} (may be null)
     * @param arguments        {@code params.arguments} as sent (may be null)
     * @param declaredProperties property names the upstream input schema declares
     * @throws IllegalArgumentException when a control value has the wrong type or is ambiguous
     */
    public static Split split(Map<String, Object> meta, Map<String, Object> arguments, Set<String> declaredProperties) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        Map<String, Object> m = meta == null ? Map.of() : meta;
        Map<String, Object> backend = new LinkedHashMap<>(args);

        String approvalMeta = stringOrNull(m, META_APPROVAL);
        Boolean dryMeta = boolOrNull(m, META_DRY_RUN);
        String receiptMeta = stringOrNull(m, META_RECEIPT);

        String approvalArg = null;
        Boolean dryArg = null;
        String receiptArg = null;
        if (!declaredProperties.contains(ARG_APPROVAL) && args.containsKey(ARG_APPROVAL)) {
            approvalArg = stringOrNull(args, ARG_APPROVAL);
            backend.remove(ARG_APPROVAL);
        }
        if (!declaredProperties.contains(ARG_DRY_RUN) && args.containsKey(ARG_DRY_RUN)) {
            dryArg = boolOrNull(args, ARG_DRY_RUN);
            backend.remove(ARG_DRY_RUN);
        }
        if (!declaredProperties.contains(ARG_RECEIPT) && args.containsKey(ARG_RECEIPT)) {
            receiptArg = stringOrNull(args, ARG_RECEIPT);
            backend.remove(ARG_RECEIPT);
        }
        String approval = pick(approvalMeta, approvalArg, "approvalToken");
        Boolean dry = pick(dryMeta, dryArg, "dryRun");
        String receipt = pick(receiptMeta, receiptArg, "dryRunReceipt");
        // Unmodifiable view, not Map.copyOf: JSON null argument values are legal and must be preserved.
        return new Split(new ControlTokens(approval, Boolean.TRUE.equals(dry), receipt),
                java.util.Collections.unmodifiableMap(backend));
    }

    private static <T> T pick(T meta, T arg, String what) {
        if (meta != null && arg != null && !meta.equals(arg)) {
            throw new IllegalArgumentException("ambiguous " + what + ": supplied in _meta and arguments with different values");
        }
        return meta != null ? meta : arg;
    }

    private static String stringOrNull(Map<String, Object> m, String key) {
        if (!m.containsKey(key) || m.get(key) == null) {
            return null;
        }
        Object v = m.get(key);
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return s;
    }

    private static Boolean boolOrNull(Map<String, Object> m, String key) {
        if (!m.containsKey(key) || m.get(key) == null) {
            return null;
        }
        Object v = m.get(key);
        if (!(v instanceof Boolean b)) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return b;
    }
}
