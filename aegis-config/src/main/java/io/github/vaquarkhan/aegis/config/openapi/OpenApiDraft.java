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
package io.github.vaquarkhan.aegis.config.openapi;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an OpenAPI 3.x document into a <em>draft</em> Aegis configuration with one {@code http}
 * source and one tool per operation. The draft is a starting point for review, not a policy:
 * <ul>
 *   <li>every tool is {@code DESTRUCTIVE}, the strictest class, until a person classifies it. An
 *       OpenAPI method is not evidence of what an operation does ({@code POST} is used for reads and
 *       {@code GET} sometimes changes state), so the generator never guesses a weaker class;</li>
 *   <li>the only policy rule denies everyone, so no tool is callable until rules are written;</li>
 *   <li>no resources, scopes or dry-run modes are generated: they need knowledge of the backend.</li>
 * </ul>
 * Schemas are copied with local {@code $ref}s inlined. Remote references are never fetched; they
 * become an empty schema and a warning, so the reviewer sees what was not checked.
 */
public final class OpenApiDraft {

    /** The result: YAML text plus the warnings it also carries as comments. */
    public record Draft(String yaml, int tools, List<String> warnings) {}

    /** Generator options; null fields take their defaults. */
    public record Options(String sourceId, String baseUrl) {}

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch");
    private static final Pattern NAME_OK = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern SOURCE_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern PATH_VAR = Pattern.compile("\\{([^}/]*)}");
    private static final int MAX_DEPTH = 32;
    private static final int MAX_DESCRIPTION = 300;
    private static final int MAX_TOOL_NAME = 64;

    private final Map<String, Object> spec;
    private final List<String> warnings = new ArrayList<>();

    private OpenApiDraft(Map<String, Object> spec) {
        this.spec = spec;
    }

    /** Reads an OpenAPI document (JSON, or YAML by file extension). Integer YAML keys such as 200 become strings. */
    public static Map<String, Object> read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Object root;
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            root = SpecYaml.load(text, file.toString());
        } else {
            root = Json.parseObject(text.getBytes(StandardCharsets.UTF_8), io.github.vaquarkhan.aegis.engine.json.JsonLimits.CONFIG);
        }
        Map<String, Object> m = JsonValues.asObject(root);
        if (m == null) {
            throw new ConfigException(file + ": an OpenAPI document must be a mapping");
        }
        return m;
    }

    public static Draft generate(Map<String, Object> spec, Options options) {
        return new OpenApiDraft(spec).run(options == null ? new Options(null, null) : options);
    }

    private Draft run(Options options) {
        Object version = spec.get("openapi");
        if (!(version instanceof String v) || !v.startsWith("3.")) {
            throw new ConfigException("only OpenAPI 3.x documents are supported (the 'openapi' field must start with 3.)");
        }
        String sourceId = options.sourceId() == null ? "api" : options.sourceId();
        if (!SOURCE_ID.matcher(sourceId).matches()) {
            throw new ConfigException("source id must be lower case letters, digits and '-', starting with a letter: " + sourceId);
        }
        String baseUrl = options.baseUrl() != null ? options.baseUrl() : serverUrl();
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("base URL is not a valid URI: " + baseUrl);
        }
        if (base.getScheme() == null || base.getHost() == null
                || !Set.of("http", "https").contains(base.getScheme().toLowerCase(Locale.ROOT))) {
            throw new ConfigException("base URL must be an absolute http(s) URL; pass --base-url (servers[0] gave: " + baseUrl + ")");
        }

        Map<String, Object> paths = JsonValues.object(spec, "paths");
        if (paths == null || paths.isEmpty()) {
            throw new ConfigException("the document has no paths");
        }
        Set<String> usedNames = new HashSet<>();
        List<String> toolBlocks = new ArrayList<>();
        for (Map.Entry<String, Object> pe : paths.entrySet()) {
            Map<String, Object> item = JsonValues.asObject(deref(pe.getValue(), "path " + pe.getKey()));
            if (item == null) {
                continue;
            }
            for (Map.Entry<String, Object> oe : item.entrySet()) {
                String method = oe.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(method)) {
                    continue;
                }
                Map<String, Object> op = JsonValues.asObject(oe.getValue());
                if (op == null) {
                    continue;
                }
                String block = tool(sourceId, pe.getKey(), method, item, op, usedNames);
                if (block != null) {
                    toolBlocks.add(block);
                }
            }
        }
        if (toolBlocks.isEmpty()) {
            throw new ConfigException("no operation could be turned into a tool; see the warnings: " + warnings);
        }
        return new Draft(render(sourceId, base.toString(), toolBlocks), toolBlocks.size(), List.copyOf(warnings));
    }

    // ------------------------------------------------------------------ one operation

    private String tool(String sourceId, String path, String method, Map<String, Object> item,
                        Map<String, Object> op, Set<String> usedNames) {
        String where = method.toUpperCase(Locale.ROOT) + " " + path;
        // Path placeholders must be names Aegis can template.
        Matcher vm = PATH_VAR.matcher(path);
        while (vm.find()) {
            if (!NAME_OK.matcher(vm.group(1)).matches()) {
                warn(where + ": skipped, path parameter '" + vm.group(1) + "' is not a name Aegis can template");
                return null;
            }
        }
        Map<String, Map<String, Object>> params = new LinkedHashMap<>();
        for (Object p : listOrEmpty(item.get("parameters"))) {
            addParam(params, p, where);
        }
        for (Object p : listOrEmpty(op.get("parameters"))) {
            addParam(params, p, where); // operation level overrides path level, by name and location
        }

        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        Map<String, String> query = new LinkedHashMap<>();
        for (Map<String, Object> p : params.values()) {
            String in = String.valueOf(p.get("in"));
            String name = String.valueOf(p.get("name"));
            switch (in) {
                case "path" -> {
                    props.put(name, paramSchema(p, where));
                    if (!required.contains(name)) {
                        required.add(name);
                    }
                }
                case "query" -> {
                    if (!NAME_OK.matcher(name).matches()) {
                        warn(where + ": query parameter '" + name + "' is not a name Aegis can template; left out");
                        continue;
                    }
                    props.put(name, paramSchema(p, where));
                    query.put(name, "{" + name + "}");
                    if (Boolean.TRUE.equals(p.get("required"))) {
                        required.add(name);
                    }
                    Map<String, Object> qs = JsonValues.asObject(props.get(name));
                    String type = qs == null ? null : String.valueOf(qs.get("type"));
                    if ("array".equals(type) || "object".equals(type)) {
                        warn(where + ": query parameter '" + name + "' is an " + type
                                + "; Aegis sends scalar query values only, so a call that sets it will fail");
                    }
                }
                default -> warn(where + ": " + in + " parameter '" + name
                        + "' left out (Aegis sends no per-call headers or cookies; credentials come from the source)");
            }
        }
        // Every path placeholder needs a declared, required argument.
        Matcher pm = PATH_VAR.matcher(path);
        while (pm.find()) {
            String n = pm.group(1);
            if (!props.containsKey(n)) {
                props.put(n, new LinkedHashMap<>(Map.of("type", "string")));
                warn(where + ": path parameter '" + n + "' is not declared in the document; added as a string");
            }
            if (!required.contains(n)) {
                required.add(n);
            }
        }

        String bodyName = null;
        Object rb = op.get("requestBody");
        if (rb != null) {
            Map<String, Object> body = JsonValues.asObject(deref(rb, where + " requestBody"));
            Map<String, Object> content = body == null ? null : JsonValues.object(body, "content");
            Map<String, Object> media = null;
            if (content != null) {
                for (Map.Entry<String, Object> ce : content.entrySet()) {
                    String ct = ce.getKey().toLowerCase(Locale.ROOT);
                    if (ct.equals("application/json") || ct.endsWith("+json")) {
                        media = JsonValues.asObject(ce.getValue());
                        break;
                    }
                }
            }
            if (media == null) {
                warn(where + ": skipped, the request body is not JSON and Aegis http tools send JSON bodies only");
                return null;
            }
            bodyName = props.containsKey("body") ? "requestBody" : "body";
            Object bs = media.get("schema");
            props.put(bodyName, bs == null ? new LinkedHashMap<>() : schema(bs, where, 0, new ArrayList<>()));
            if (Boolean.TRUE.equals(body.get("required"))) {
                required.add(bodyName);
            }
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("properties", props);
        if (!required.isEmpty()) {
            input.put("required", required);
        }
        input.put("additionalProperties", false);

        String name = toolName(sourceId, op, method, path, usedNames);
        String description = description(op, where);

        StringBuilder sb = new StringBuilder();
        String opId = op.get("operationId") instanceof String s ? "  operationId: " + s : "";
        sb.append("      # ").append(where).append(oneLine(opId)).append('\n');
        sb.append("      # REVIEW: set class to READ, MUTATE or DESTRUCTIVE; add resources and a dryRun mode.\n");
        if (Boolean.TRUE.equals(op.get("deprecated"))) {
            sb.append("      # The document marks this operation deprecated.\n");
        }
        sb.append("      - name: ").append(q(name)).append('\n');
        sb.append("        description: ").append(q(description)).append('\n');
        sb.append("        class: DESTRUCTIVE\n");
        sb.append("        inputSchema:\n");
        emit(sb, input, 10);
        sb.append("        request:\n");
        sb.append("          method: ").append(method.toUpperCase(Locale.ROOT)).append('\n');
        sb.append("          path: ").append(q(path)).append('\n');
        if (!query.isEmpty()) {
            sb.append("          query:\n");
            emit(sb, query, 12);
        }
        if (bodyName != null) {
            sb.append("          body: ").append(q("{" + bodyName + "}")).append('\n');
        }
        return sb.toString();
    }

    private void addParam(Map<String, Map<String, Object>> params, Object raw, String where) {
        Map<String, Object> p = JsonValues.asObject(deref(raw, where + " parameter"));
        if (p == null || !(p.get("name") instanceof String n) || !(p.get("in") instanceof String in)) {
            warn(where + ": a parameter without a name or location was left out");
            return;
        }
        params.put(in + ":" + n, p);
    }

    private Map<String, Object> paramSchema(Map<String, Object> p, String where) {
        Object s = p.get("schema");
        Map<String, Object> out = s == null ? new LinkedHashMap<>(Map.of("type", "string"))
                : schema(s, where, 0, new ArrayList<>());
        if (p.get("description") instanceof String d && !out.containsKey("description")) {
            out.put("description", trim(d));
        }
        return out;
    }

    // ------------------------------------------------------------------ schemas and references

    /** Copies a schema with local references inlined; cycles and remote references become {} with a warning. */
    private Map<String, Object> schema(Object raw, String where, int depth, List<String> stack) {
        if (depth > MAX_DEPTH) {
            warn(where + ": schema nesting deeper than " + MAX_DEPTH + " was cut off (left unconstrained)");
            return new LinkedHashMap<>();
        }
        Map<String, Object> s = JsonValues.asObject(raw);
        if (s == null) {
            return new LinkedHashMap<>(); // boolean schemas: true accepts anything, which is what {} does
        }
        if (s.get("$ref") instanceof String ref) {
            if (!ref.startsWith("#/")) {
                warn(where + ": remote $ref '" + ref + "' was not fetched; that part is left unconstrained");
                return new LinkedHashMap<>();
            }
            if (stack.contains(ref)) {
                warn(where + ": recursive $ref '" + ref + "' was not expanded again; that part is left unconstrained");
                return new LinkedHashMap<>();
            }
            Object target = pointer(ref);
            if (target == null) {
                warn(where + ": $ref '" + ref + "' does not resolve; that part is left unconstrained");
                return new LinkedHashMap<>();
            }
            List<String> next = new ArrayList<>(stack);
            next.add(ref);
            Map<String, Object> resolved = schema(target, where, depth + 1, next);
            for (Map.Entry<String, Object> e : s.entrySet()) {
                if (!"$ref".equals(e.getKey())) {
                    resolved.put(e.getKey(), copy(e.getValue(), where, depth + 1, stack));
                }
            }
            return resolved;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : s.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            switch (k) {
                case "properties", "patternProperties", "$defs", "definitions", "dependentSchemas" -> {
                    Map<String, Object> m = JsonValues.asObject(v);
                    Map<String, Object> o = new LinkedHashMap<>();
                    if (m != null) {
                        for (Map.Entry<String, Object> pe : m.entrySet()) {
                            o.put(pe.getKey(), schema(pe.getValue(), where, depth + 1, stack));
                        }
                    }
                    out.put(k, o);
                }
                case "items", "additionalProperties", "not", "contains", "propertyNames", "if", "then", "else",
                        "unevaluatedItems", "unevaluatedProperties", "additionalItems" ->
                        out.put(k, v instanceof Boolean ? v : schema(v, where, depth + 1, stack));
                case "allOf", "anyOf", "oneOf", "prefixItems" -> {
                    List<Object> o = new ArrayList<>();
                    for (Object x : listOrEmpty(v)) {
                        o.add(schema(x, where, depth + 1, stack));
                    }
                    out.put(k, o);
                }
                case "example", "examples", "xml", "externalDocs", "discriminator" -> {
                    // documentation only; examples can be large and carry sample secrets
                }
                default -> out.put(k, copy(v, where, depth + 1, stack));
            }
        }
        // OpenAPI 3.0 "nullable: true" is "type: [T, null]" in JSON Schema, which is what Aegis evaluates.
        if (Boolean.TRUE.equals(out.remove("nullable")) && out.get("type") instanceof String t) {
            out.put("type", new ArrayList<>(List.of(t, "null")));
        }
        return out;
    }

    private Object copy(Object v, String where, int depth, List<String> stack) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        if (v instanceof Map<?, ?>) {
            Map<String, Object> o = new LinkedHashMap<>();
            JsonValues.asObject(v).forEach((k, x) -> o.put(k, copy(x, where, depth + 1, stack)));
            return o;
        }
        if (v instanceof List<?> l) {
            List<Object> o = new ArrayList<>();
            for (Object x : l) {
                o.add(copy(x, where, depth + 1, stack));
            }
            return o;
        }
        return v;
    }

    /** Resolves a {@code $ref} on a parameter, request body or path item; remote ones are refused with a warning. */
    private Object deref(Object raw, String where) {
        Object cur = raw;
        for (int i = 0; i < 8; i++) {
            Map<String, Object> m = JsonValues.asObject(cur);
            if (m == null || !(m.get("$ref") instanceof String ref)) {
                return cur;
            }
            if (!ref.startsWith("#/")) {
                warn(where + ": remote $ref '" + ref + "' was not fetched; left out");
                return null;
            }
            cur = pointer(ref);
            if (cur == null) {
                warn(where + ": $ref '" + ref + "' does not resolve; left out");
                return null;
            }
        }
        warn(where + ": $ref chain longer than 8; left out");
        return null;
    }

    private Object pointer(String ref) {
        Object node = spec;
        for (String raw : ref.substring(2).split("/", -1)) {
            String key = java.net.URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
                    .replace("~1", "/").replace("~0", "~");
            if (node instanceof Map<?, ?> m) {
                node = m.get(key);
            } else if (node instanceof List<?> l && key.matches("0|[1-9][0-9]{0,8}") && Integer.parseInt(key) < l.size()) {
                node = l.get(Integer.parseInt(key));
            } else {
                return null;
            }
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    // ------------------------------------------------------------------ names, text, servers

    private String toolName(String sourceId, Map<String, Object> op, String method, String path, Set<String> used) {
        String basis = op.get("operationId") instanceof String id && !id.isBlank() ? id
                : method + "_" + path.replaceAll("\\{([^}]*)}", "by_$1");
        String snake = basis.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        String prefix = sourceId.replace('-', '_') + "_";
        String name = prefix + (snake.isEmpty() ? method : snake);
        if (name.length() > MAX_TOOL_NAME) {
            name = name.substring(0, MAX_TOOL_NAME).replaceAll("_$", "");
        }
        String unique = name;
        for (int i = 2; !used.add(unique); i++) {
            String suffix = "_" + i;
            unique = (name.length() + suffix.length() > MAX_TOOL_NAME
                    ? name.substring(0, MAX_TOOL_NAME - suffix.length()) : name) + suffix;
        }
        return unique;
    }

    private String description(Map<String, Object> op, String where) {
        String text = op.get("summary") instanceof String s && !s.isBlank() ? s
                : op.get("description") instanceof String d && !d.isBlank() ? d : null;
        if (text == null) {
            warn(where + ": no summary or description in the document; the tool description is the method and path");
            return where;
        }
        return trim(text);
    }

    private static String trim(String text) {
        String t = oneLine(text).trim();
        if (t.length() > MAX_DESCRIPTION) {
            int cut = t.lastIndexOf(' ', MAX_DESCRIPTION);
            t = t.substring(0, cut > 40 ? cut : MAX_DESCRIPTION) + "...";
        }
        return t;
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ");
    }

    /** servers[0].url with its variables replaced by their defaults. */
    private String serverUrl() {
        List<Object> servers = JsonValues.asList(spec.get("servers"));
        Map<String, Object> first = servers == null || servers.isEmpty() ? null : JsonValues.asObject(servers.get(0));
        if (first == null || !(first.get("url") instanceof String url)) {
            throw new ConfigException("the document declares no servers; pass --base-url");
        }
        Map<String, Object> vars = JsonValues.object(first, "variables");
        Matcher m = PATH_VAR.matcher(url);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Map<String, Object> var = vars == null ? null : JsonValues.asObject(vars.get(m.group(1)));
            Object def = var == null ? null : var.get("default");
            if (def == null) {
                throw new ConfigException("server variable {" + m.group(1) + "} has no default; pass --base-url");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(def)));
        }
        m.appendTail(out);
        String resolved = out.toString().replaceAll("(?<!:)//+", "/");
        if (resolved.endsWith("/")) {
            resolved = resolved.substring(0, resolved.length() - 1);
        }
        warn("base URL taken from servers[0] with default variables: " + resolved + " (override with --base-url)");
        return resolved;
    }

    private void warn(String w) {
        String line = oneLine(w);
        if (!warnings.contains(line)) {
            warnings.add(line);
        }
    }

    private static List<Object> listOrEmpty(Object v) {
        List<Object> l = JsonValues.asList(v);
        return l == null ? List.of() : l;
    }

    // ------------------------------------------------------------------ YAML output

    private String render(String sourceId, String baseUrl, List<String> tools) {
        Map<String, Object> info = JsonValues.object(spec, "info");
        String title = info != null && info.get("title") instanceof String t ? oneLine(t) : "untitled API";
        String version = info != null && info.get("version") != null ? oneLine(String.valueOf(info.get("version"))) : "?";
        StringBuilder sb = new StringBuilder();
        sb.append("# DRAFT Aegis configuration generated by --openapi-draft from \"").append(title)
                .append("\" version ").append(version).append(".\n");
        sb.append("#\n");
        sb.append("# Nothing here is reviewed. Before use:\n");
        sb.append("#  1. Classify every tool. Each one is DESTRUCTIVE (approval plus a dry-run receipt on every\n");
        sb.append("#     call) because an HTTP method does not say what an operation really does.\n");
        sb.append("#  2. Delete the tools you do not want agents to have. Fewer tools is a smaller attack surface.\n");
        sb.append("#  3. Add resources (which argument names the governed object) and dryRun modes.\n");
        sb.append("#  4. Replace the deny-all rule with real policy rules, and set access groups and credentials.\n");
        sb.append("#  5. Check the warnings below: they list what the generator could not express.\n");
        if (!warnings.isEmpty()) {
            sb.append("#\n# Warnings:\n");
            for (String w : warnings) {
                sb.append("#  - ").append(w).append('\n');
            }
        }
        sb.append("\ngateway:\n  name: ").append(q("aegis-" + sourceId)).append("\n\n");
        sb.append("# approval:\n#   keyEnv: AEGIS_SIGNING_KEY   # needed before any MUTATE or DESTRUCTIVE tool can be approved\n\n");
        sb.append("policy:\n  rules:\n");
        sb.append("    # Deny-all until reviewed. Replace with rules naming groups, classes and tools.\n");
        sb.append("    - {id: draft-deny-all, effect: DENY, groups: [\"*\"]}\n\n");
        sb.append("audit:\n  file: ").append(q("audit/aegis-" + sourceId + ".jsonl")).append("\n\n");
        sb.append("# host:   # the front door: who may call. Required before the gateway serves HTTP; see docs/CONFIGURATION.md\n");
        sb.append("#   identity: {type: jwt, jwt: {issuer: \"https://idp.example.com/\", audience: aegis, jwksUri: \"https://idp.example.com/jwks\"}}\n\n");
        sb.append("sources:\n");
        sb.append("  - id: ").append(q(sourceId)).append('\n');
        sb.append("    type: http\n");
        sb.append("    baseUrl: ").append(q(baseUrl)).append('\n');
        sb.append("    # credentials: {type: bearer, tokenEnv: CHANGE_ME}   # the gateway's own credential for this API\n");
        sb.append("    tools:\n");
        for (String t : tools) {
            sb.append('\n').append(t);
        }
        return sb.toString();
    }

    /** Block-style YAML for maps and lists; every string is double quoted so no value is reinterpreted. */
    private static void emit(StringBuilder sb, Object v, int indent) {
        String pad = " ".repeat(indent);
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = key(String.valueOf(e.getKey()));
                Object x = e.getValue();
                if (isEmptyContainer(x) || !(x instanceof Map || x instanceof List)) {
                    sb.append(pad).append(key).append(": ").append(scalar(x)).append('\n');
                } else {
                    sb.append(pad).append(key).append(":\n");
                    emit(sb, x, indent + 2);
                }
            }
        } else if (v instanceof List<?> l) {
            for (Object x : l) {
                if (isEmptyContainer(x) || !(x instanceof Map || x instanceof List)) {
                    sb.append(pad).append("- ").append(scalar(x)).append('\n');
                } else {
                    sb.append(pad).append("-\n");
                    emit(sb, x, indent + 2);
                }
            }
        }
    }

    private static boolean isEmptyContainer(Object x) {
        return (x instanceof Map<?, ?> m && m.isEmpty()) || (x instanceof List<?> l && l.isEmpty());
    }

    private static String scalar(Object x) {
        if (x == null) {
            return "null";
        }
        if (x instanceof Map) {
            return "{}";
        }
        if (x instanceof List) {
            return "[]";
        }
        if (x instanceof Boolean || x instanceof Long || x instanceof Integer) {
            return String.valueOf(x);
        }
        if (x instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        return q(String.valueOf(x));
    }

    private static String key(String k) {
        return k.matches("[A-Za-z_][A-Za-z0-9_]*") ? k : q(k);
    }

    /** YAML double-quoted scalar, valid for YAML 1.1 and 1.2. */
    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f) || c == 0x2028 || c == 0x2029 || c == 0xfeff) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Isolated so this class only links against snakeyaml when a YAML document is read. */
    private static final class SpecYaml {
        static Object load(String text, String source) {
            org.yaml.snakeyaml.LoaderOptions opts = new org.yaml.snakeyaml.LoaderOptions();
            opts.setAllowDuplicateKeys(false);
            opts.setMaxAliasesForCollections(50);
            opts.setAllowRecursiveKeys(false);
            opts.setNestingDepthLimit(128);
            opts.setCodePointLimit(16 * 1024 * 1024);
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(opts));
            try {
                return normalise(yaml.load(text), 0);
            } catch (RuntimeException e) {
                throw new ConfigException(source + ": invalid YAML: " + e.getMessage(), e);
            }
        }

        /** Engine value model; non-string keys (HTTP status codes) become strings, dates become text. */
        private static Object normalise(Object v, int depth) {
            if (depth > 128) {
                throw new ConfigException("OpenAPI document nesting too deep");
            }
            if (v == null || v instanceof String || v instanceof Boolean) {
                return v;
            }
            if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                return ((Number) v).longValue();
            }
            if (v instanceof java.math.BigInteger bi) {
                return bi.bitLength() < 64 ? bi.longValue() : new BigDecimal(bi);
            }
            if (v instanceof Double || v instanceof Float) {
                double d = ((Number) v).doubleValue();
                return Double.isFinite(d) ? BigDecimal.valueOf(d) : String.valueOf(d);
            }
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()), normalise(e.getValue(), depth + 1));
                }
                return out;
            }
            if (v instanceof List<?> l) {
                List<Object> out = new ArrayList<>();
                for (Object o : l) {
                    out.add(normalise(o, depth + 1));
                }
                return out;
            }
            if (v instanceof java.util.Date d) {
                return d.toInstant().toString();
            }
            if (v instanceof byte[]) {
                return "(binary value)";
            }
            return String.valueOf(v);
        }
    }
}
