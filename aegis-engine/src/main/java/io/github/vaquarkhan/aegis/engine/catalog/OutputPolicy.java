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

import io.github.vaquarkhan.aegis.engine.control.SchemaValidator;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trusted release policy for backend output. PASS_THROUGH retains compatibility. STRUCTURED_ALLOWLIST
 * releases only structuredContent that satisfies a strict operator schema and rebuilds text from that
 * object, so an upstream text field cannot bypass the allow list. HANDLE_ONLY releases no backend data,
 * only a digest handle and basic result facts. Complete-control mode requires one of the latter two.
 */
public record OutputPolicy(Mode mode, Map<String, Object> schema) {

    public enum Mode { PASS_THROUGH, STRUCTURED_ALLOWLIST, HANDLE_ONLY }

    public static final OutputPolicy PASS_THROUGH = new OutputPolicy(Mode.PASS_THROUGH, null);
    public static final OutputPolicy HANDLE_ONLY = new OutputPolicy(Mode.HANDLE_ONLY, null);

    private static final Set<String> ALLOWED_SCHEMA_KEYS = Set.of(
            "$defs", "type", "properties", "required", "additionalProperties", "enum", "const",
            "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "items", "minItems", "maxItems", "title", "description");

    public OutputPolicy {
        mode = mode == null ? Mode.PASS_THROUGH : mode;
        schema = schema == null ? null : deepCopy(schema);
        if (mode == Mode.STRUCTURED_ALLOWLIST) {
            if (schema == null) {
                throw new IllegalArgumentException("STRUCTURED_ALLOWLIST needs an operator output schema");
            }
            strictSchema(schema, "$");
        } else if (schema != null) {
            throw new IllegalArgumentException("output schema applies only to STRUCTURED_ALLOWLIST");
        }
    }

    public boolean allowedInCompleteControl() {
        return mode != Mode.PASS_THROUGH;
    }

    /** Stable policy form included in action binding and audit metadata. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode.name());
        if (schema != null) {
            out.put("schema", schema);
        }
        return out;
    }

    /**
     * Apply the release contract before the ordinary OutputGuard. A non-null error means no backend
     * content may be returned. HANDLE_ONLY is successful and returns a safe replacement result.
     */
    public Release release(ToolResult input, String requestId) {
        if (mode == Mode.PASS_THROUGH) {
            return new Release(input, null, false, null);
        }
        String digest = Digests.canonicalSha256(input.body());
        // The handle names this call's result, not the content: identical results of two calls get
        // different handles, so one caller's retained copy can never be addressed through another's.
        String handle = "result:" + Digests.sha256Hex("aegis-result-handle-v1\n" + requestId + "\n" + digest);
        if (mode == Mode.HANDLE_ONLY) {
            Map<String, Object> publicValue = new LinkedHashMap<>();
            publicValue.put("handle", handle);
            publicValue.put("digest", digest);
            publicValue.put("requestId", requestId);
            publicValue.put("backendError", input.isError());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("content", List.of(Map.of("type", "text", "text", "Aegis withheld backend output; handle=" + handle)));
            body.put("structuredContent", publicValue);
            body.put("isError", input.isError());
            return new Release(new ToolResult(body), null, true, handle);
        }
        Object structured = input.body().get("structuredContent");
        if (structured == null) {
            return new Release(null, "backend result has no structuredContent required by outputPolicy", true, handle);
        }
        String problem = SchemaValidator.validate(schema, structured);
        if (problem != null) {
            return new Release(null, "backend structuredContent does not match the operator output schema: " + problem,
                    true, handle);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // This is the only model-visible text. It is reconstructed from the validated object; original
        // content, extension fields and upstream _meta are deliberately not copied.
        body.put("content", List.of(Map.of("type", "text", "text", Json.write(structured))));
        body.put("structuredContent", structured);
        body.put("isError", input.isError());
        if (input.body().containsKey("resultType")) {
            body.put("resultType", input.body().get("resultType"));
        }
        return new Release(new ToolResult(body), null, false, null);
    }

    public record Release(ToolResult result, String error, boolean withheld, String handle) {
        public boolean allowed() {
            return error == null;
        }
    }

    /**
     * Security schemas are intentionally stricter than general advertised schemas: every object at every
     * depth must explicitly deny additional properties, and only keywords SchemaValidator enforces are
     * accepted. Remote/local references and composition are refused rather than silently ignored.
     */
    private static void strictSchema(Map<String, Object> value, String path) {
        for (String key : value.keySet()) {
            if (!ALLOWED_SCHEMA_KEYS.contains(key)) {
                throw new IllegalArgumentException("unsupported output schema keyword '" + key + "' at " + path);
            }
        }
        if (value.containsKey("$defs")) {
            Map<String, Object> defs = JsonValues.asObject(value.get("$defs"));
            if (defs == null) {
                throw new IllegalArgumentException("$defs must be an object at " + path);
            }
            for (Map.Entry<String, Object> e : defs.entrySet()) {
                Map<String, Object> child = JsonValues.asObject(e.getValue());
                if (child == null) {
                    throw new IllegalArgumentException("definition must be an object at " + path + "/$defs/" + e.getKey());
                }
                strictSchema(child, path + "/$defs/" + e.getKey());
            }
        }
        Object type = value.get("type");
        boolean object = "object".equals(type);
        if (object) {
            if (!Boolean.FALSE.equals(value.get("additionalProperties"))) {
                throw new IllegalArgumentException("object output schema must set additionalProperties: false at " + path);
            }
            Map<String, Object> props = JsonValues.object(value, "properties");
            if (props == null) {
                throw new IllegalArgumentException("object output schema needs properties at " + path);
            }
            for (Map.Entry<String, Object> e : props.entrySet()) {
                Map<String, Object> child = JsonValues.asObject(e.getValue());
                if (child == null) {
                    throw new IllegalArgumentException("property schema must be an object at " + path + "/" + e.getKey());
                }
                strictSchema(child, path + "/" + e.getKey());
            }
        }
        if (value.containsKey("items")) {
            Map<String, Object> items = JsonValues.asObject(value.get("items"));
            if (items == null) {
                throw new IllegalArgumentException("items must be an object at " + path);
            }
            strictSchema(items, path + "/items");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> value) {
        // Json round-trip removes caller-owned mutable maps/lists and uses the same bounded value types as config.
        return Json.parseObject(Json.write(value));
    }
}
