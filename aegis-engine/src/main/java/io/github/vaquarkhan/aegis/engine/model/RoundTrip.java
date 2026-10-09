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

/**
 * MCP 2026-07-28 Multi Round-Trip Request (MRTR) data that travels with a retried request.
 *
 * <p>A backend may answer {@code tools/call}, {@code prompts/get} or {@code resources/read} with
 * {@code resultType: "input_required"}, carrying {@code inputRequests} (elicitation, sampling or
 * roots requests for the client) and an opaque {@code requestState}. The client fulfils them and
 * re-sends the original request with {@code inputResponses} and the echoed {@code requestState}.
 * Aegis governs that retry as a new request: every check runs again (including approval, whose
 * token is single use), {@code inputResponses} are screened like arguments, and both fields are
 * forwarded to the backend unchanged. {@code requestState} is the backend's own opaque value;
 * integrity protection of it is the backend's job (the MCP specification requires it), and Aegis
 * only bounds its size.
 *
 * @param inputResponses     the client's answers, keyed like the backend's {@code inputRequests}; empty if none
 * @param requestState       the echoed opaque state, or null
 * @param clientCapabilities the client's declared {@code elicitation} / {@code sampling} / {@code roots}
 *                           capabilities, forwarded so a compliant backend knows what it may ask for
 */
public record RoundTrip(Map<String, Object> inputResponses, String requestState, Map<String, Object> clientCapabilities) {

    public static final RoundTrip NONE = new RoundTrip(Map.of(), null, Map.of());
    /** Upper bound on the opaque state a client may echo (it is server-minted, so large values are suspicious). */
    public static final int MAX_REQUEST_STATE_CHARS = 64 * 1024;

    public RoundTrip {
        inputResponses = inputResponses == null ? Map.of() : inputResponses;
        clientCapabilities = clientCapabilities == null ? Map.of() : clientCapabilities;
    }

    /** True when this request is an MRTR retry. */
    public boolean isRetry() {
        return !inputResponses.isEmpty() || requestState != null;
    }

    /**
     * Reads the MRTR fields of a request's params.
     *
     * @throws IllegalArgumentException when {@code inputResponses} is not an object or {@code requestState}
     *                                  is not a string of bounded size
     */
    public static RoundTrip fromParams(Map<String, Object> params, Map<String, Object> meta) {
        Object ir = params.get("inputResponses");
        Object rs = params.get("requestState");
        if (ir != null && !(ir instanceof Map)) {
            throw new IllegalArgumentException("inputResponses must be an object");
        }
        if (rs != null && (!(rs instanceof String s) || s.isEmpty() || s.length() > MAX_REQUEST_STATE_CHARS)) {
            throw new IllegalArgumentException("requestState must be a non-empty string of at most " + MAX_REQUEST_STATE_CHARS + " characters");
        }
        Map<String, Object> caps = new LinkedHashMap<>();
        Object declared = meta == null ? null : meta.get("io.modelcontextprotocol/clientCapabilities");
        if (declared instanceof Map<?, ?> m) {
            for (String k : new String[] {"elicitation", "sampling", "roots"}) {
                if (m.get(k) instanceof Map<?, ?> v) {
                    caps.put(k, v);
                }
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> irm = ir == null ? Map.of() : (Map<String, Object>) ir;
        if (irm.isEmpty() && rs == null && caps.isEmpty()) {
            return NONE;
        }
        return new RoundTrip(irm, (String) rs, caps);
    }

    /** Adds the retry fields to downstream params (the capabilities go into {@code _meta} separately). */
    public void addTo(Map<String, Object> params) {
        if (!inputResponses.isEmpty()) {
            params.put("inputResponses", inputResponses);
        }
        if (requestState != null) {
            params.put("requestState", requestState);
        }
    }
}
