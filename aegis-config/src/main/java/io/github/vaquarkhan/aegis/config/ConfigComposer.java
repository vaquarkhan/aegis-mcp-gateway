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
package io.github.vaquarkhan.aegis.config;

import io.github.vaquarkhan.aegis.engine.model.Digests;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Authoring-time composition engine for two-layer Aegis configurations.
 *
 * <p>Validates and merges a core gateway governance document ({@code aegis.yaml}) with one or more
 * backend-specific adopter documents ({@code adopters/<name>.yaml}), enforces strict field
 * ownership and collision rules, resolves schema-defined filesystem paths while preserving
 * non-filesystem paths (such as HTTP {@code request.path} and Vault {@code secrets.settings.path}),
 * and materializes a deterministic single-file configuration for {@code --config}.
 */
public final class ConfigComposer {

    private static final String APACHE_LICENSE_HEADER = """
            # Licensed to the Apache Software Foundation (ASF) under one or more
            # contributor license agreements.  See the NOTICE file distributed with
            # this work for additional information regarding copyright ownership.
            # The ASF licenses this file to You under the Apache License, Version 2.0
            # (the "License"); you may not use this file except in compliance with
            # the License.  You may obtain a copy of the License at
            #
            #     http://www.apache.org/licenses/LICENSE-2.0
            #
            # Unless required by applicable law or agreed to in writing, software
            # distributed under the License is distributed on an "AS IS" BASIS,
            # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            # See the License for the specific language governing permissions and
            # limitations under the License.
            """;

    /** Top-level sections exclusively owned by the core gateway configuration. */
    public static final Set<String> GATEWAY_OWNED_KEYS = Set.of(
            "host", "knox", "completeControl", "secrets", "gateway",
            "access", "approval", "vrp", "serverCatalog", "egress",
            "policy", "rateLimits", "breaker", "screening", "output",
            "audit", "catalog", "server", "metrics", "tracing",
            "state", "sources"
    );

    /**
     * Top-level keys allowed inside a single-adopter configuration file (Issue #38):
     * source integration fields (normalized into {@code sources[]}), plus optional
     * backend-scoped {@code scopes} and adapter {@code hooks}.
     */
    public static final Set<String> ALLOWED_ADOPTER_KEYS = Set.of(
            // Core source identity & transport
            "id", "type", "namePrefix", "timeoutMs", "unlistedTools",
            "strictProperties", "defaultClass", "trust", "credentials",
            // Security & admission metadata on source
            "artifacts", "deploymentAttestation",
            // MCP source fields
            "transport", "era", "legacyVersion", "maxResponseBytes",
            "url", "command", "envFrom", "env", "workingDir", "sandbox",
            "resources", "prompts", "completions",
            // HTTP source fields
            "baseUrl",
            // Plugin / backend settings & tools (including per-tool executionContract & outputPolicy)
            "settings", "tools",
            // Adopter-scoped resource rules & hooks (merged into top-level scopes / hooks)
            "scopes", "hooks"
    );

    /**
     * Schema-defined filesystem path keys resolved relative to their file of origin (or rebased
     * relative to {@code outputDir} when provided). Deliberately excludes {@code "path"} so HTTP
     * {@code request.path} and Vault {@code secrets.settings.path} are preserved verbatim.
     */
    private static final Set<String> FILESYSTEM_PATH_KEYS = Set.of(
            "file", "signatureFile", "publicKeyFile", "keyFile", "pinFile",
            "directory", "tokenFile", "caFile", "signingKeyFile", "workingDir",
            "keystoreFile", "truststoreFile", "clientPropertiesFile"
    );

    public record ComposedConfig(
            Map<String, Object> effectiveMap,
            String effectiveYaml,
            String effectiveConfigHash
    ) {}

    public ComposedConfig compose(Path gatewayFile, List<Path> adopterFiles) throws IOException {
        return compose(gatewayFile, adopterFiles, null);
    }

    public ComposedConfig compose(Path gatewayFile, List<Path> adopterFiles, Path outputDir) throws IOException {
        if (gatewayFile == null || !Files.isRegularFile(gatewayFile)) {
            throw new ConfigException("gateway config file does not exist: " + gatewayFile);
        }
        if (adopterFiles == null || adopterFiles.isEmpty()) {
            throw new ConfigException("at least one adopter config file is required");
        }

        Path normalizedGateway = gatewayFile.toAbsolutePath().normalize();
        Path gatewayBaseDir = normalizedGateway.getParent();
        Path normalizedOutputDir = outputDir == null ? null : outputDir.toAbsolutePath().normalize();
        Map<String, Object> rawGateway = ConfigLoader.load(normalizedGateway);

        if (rawGateway.containsKey("sources")) {
            throw new ConfigException(normalizedGateway
                    + ": core gateway config must not define 'sources'; declare backends in adopter files");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> resolvedGateway =
                (Map<String, Object>) resolvePaths(rawGateway, gatewayBaseDir, normalizedOutputDir);

        List<Object> composedSources = new ArrayList<>();
        List<Object> composedScopes = extractMapList(resolvedGateway.get("scopes"), "scopes", normalizedGateway);
        List<Object> composedHooks = extractMapList(resolvedGateway.get("hooks"), "hooks", normalizedGateway);

        Set<String> seenSourceIds = new LinkedHashSet<>();
        Set<String> seenExposedTools = new LinkedHashSet<>();
        Set<String> seenHookKeys = new LinkedHashSet<>();
        recordHookCollisions(composedHooks, normalizedGateway, seenHookKeys);

        for (Path adopterFile : adopterFiles) {
            if (adopterFile == null || !Files.isRegularFile(adopterFile)) {
                throw new ConfigException("adopter config file does not exist: " + adopterFile);
            }
            Path normalizedAdopter = adopterFile.toAbsolutePath().normalize();
            Path adopterBaseDir = normalizedAdopter.getParent();
            Map<String, Object> rawAdopter = ConfigLoader.load(normalizedAdopter);

            validateAdopterSchema(rawAdopter, normalizedAdopter);
            checkCollisions(rawAdopter, normalizedAdopter, seenSourceIds, seenExposedTools, seenHookKeys);

            @SuppressWarnings("unchecked")
            Map<String, Object> resolvedAdopter =
                    (Map<String, Object>) resolvePaths(rawAdopter, adopterBaseDir, normalizedOutputDir);

            // Lift adopter-declared scopes and hooks into top-level gateway collections (fail-closed)
            List<Object> adopterScopes = extractMapList(resolvedAdopter.remove("scopes"), "scopes", normalizedAdopter);
            composedScopes.addAll(adopterScopes);

            List<Object> adopterHooks = extractMapList(resolvedAdopter.remove("hooks"), "hooks", normalizedAdopter);
            String sourceId = (String) resolvedAdopter.get("id");
            for (Object h : adopterHooks) {
                composedHooks.add(bindHookToSource((Map<?, ?>) h, sourceId, normalizedAdopter));
            }

            composedSources.add(resolvedAdopter);
        }

        Map<String, Object> effective = new TreeMap<>(resolvedGateway);
        effective.put("sources", composedSources);
        if (!composedScopes.isEmpty()) {
            effective.put("scopes", composedScopes);
        }
        if (!composedHooks.isEmpty()) {
            effective.put("hooks", composedHooks);
        }

        String effectiveHash = Digests.canonicalSha256(effective);
        String yamlBody = renderCanonicalYaml(effective);
        String header = APACHE_LICENSE_HEADER + "\n" + "# Generated by Aegis ConfigComposer\n"
                + "# effectiveConfigHash: " + effectiveHash + "\n";

        return new ComposedConfig(Collections.unmodifiableMap(effective), header + yamlBody, effectiveHash);
    }

    private void validateAdopterSchema(Map<String, Object> adopter, Path source) {
        for (String key : adopter.keySet()) {
            if (GATEWAY_OWNED_KEYS.contains(key)) {
                throw new ConfigException(source + ": adopter config cannot define gateway-owned section '" + key + "'");
            }
            if (!ALLOWED_ADOPTER_KEYS.contains(key)) {
                throw new ConfigException(source + ": disallowed or unknown adopter field '" + key + "'");
            }
        }
        if (!(adopter.get("id") instanceof String id) || id.isBlank()) {
            throw new ConfigException(source + ": adopter config must define a non-empty string 'id'");
        }
        if (!(adopter.get("type") instanceof String type) || type.isBlank()) {
            throw new ConfigException(source + ": adopter config must define a non-empty string 'type'");
        }
    }

    private void checkCollisions(
            Map<String, Object> adopter,
            Path source,
            Set<String> seenSourceIds,
            Set<String> seenExposedTools,
            Set<String> seenHookKeys
    ) {
        String sourceId = (String) adopter.get("id");
        if (!seenSourceIds.add(sourceId)) {
            throw new ConfigException(source + ": duplicate source id '" + sourceId + "'");
        }
        String prefix = adopter.get("namePrefix") instanceof String p ? p : "";
        Object toolsObj = adopter.get("tools");
        if (toolsObj instanceof List<?> toolsList) {
            for (Object item : toolsList) {
                if (item instanceof Map<?, ?> tm && tm.get("name") instanceof String name) {
                    String exposed = prefix + name;
                    if (!seenExposedTools.add(exposed)) {
                        throw new ConfigException(source + ": duplicate exposed tool '" + exposed + "'");
                    }
                }
            }
        } else if (toolsObj instanceof Map<?, ?> toolsMap) {
            for (Object key : toolsMap.keySet()) {
                String exposed = prefix + key;
                if (!seenExposedTools.add(exposed)) {
                    throw new ConfigException(source + ": duplicate exposed tool '" + exposed + "'");
                }
            }
        }
        List<Object> hooks = extractMapList(adopter.get("hooks"), "hooks", source);
        recordHookCollisions(hooks, source, seenHookKeys);
    }

    private void recordHookCollisions(List<?> hooks, Path source, Set<String> seenHookKeys) {
        for (Object item : hooks) {
            Map<?, ?> hm = (Map<?, ?>) item;
            String hookKey = hookCollisionKey(hm, source);
            if (!seenHookKeys.add(hookKey)) {
                throw new ConfigException(source + ": duplicate hook '" + hookKey + "'");
            }
        }
    }

    private String hookCollisionKey(Map<?, ?> hm, Path source) {
        if (hm.get("id") instanceof String id && !id.isBlank()) {
            return "id:" + id;
        }
        if (hm.get("name") instanceof String name && !name.isBlank()) {
            return "name:" + name;
        }
        if (hm.get("settings") instanceof Map<?, ?> settings
                && settings.get("name") instanceof String settingsName && !settingsName.isBlank()) {
            return "settings.name:" + settingsName;
        }
        if (hm.get("type") instanceof String type && !type.isBlank()) {
            Object methods = hm.get("methods");
            Object ops = hm.get("operations");
            return "type:" + type + "|ops:" + ops + "|methods:" + methods;
        }
        throw new ConfigException(source + ": malformed hook entry missing 'type', 'id', or 'name'");
    }

    /** Ensures adopter-defined hooks are scoped to their own source unless explicitly set otherwise. */
    private Map<String, Object> bindHookToSource(Map<?, ?> hookMap, String sourceId, Path origin) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : hookMap.entrySet()) {
            copy.put(String.valueOf(e.getKey()), e.getValue());
        }
        Object sourcesObj = copy.get("sources");
        if (sourcesObj == null) {
            copy.put("sources", List.of(sourceId));
        } else if (sourcesObj instanceof List<?> list) {
            for (Object s : list) {
                if (!sourceId.equals(String.valueOf(s))) {
                    throw new ConfigException(origin + ": adopter hook cannot target external source '" + s + "'");
                }
            }
        } else {
            throw new ConfigException(origin + ": hook 'sources' must be a list");
        }
        return copy;
    }

    private List<Object> extractMapList(Object val, String fieldName, Path source) {
        if (val == null) {
            return new ArrayList<>();
        }
        if (!(val instanceof List<?> list)) {
            throw new ConfigException(source + ": '" + fieldName + "' must be a list");
        }
        List<Object> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?>)) {
                throw new ConfigException(source + ": '" + fieldName + "[" + i + "]' must be a mapping");
            }
            out.add(item);
        }
        return out;
    }

    private Object resolvePaths(Object node, Path baseDir, Path outputDir) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object val = entry.getValue();
                if (val instanceof String strVal && FILESYSTEM_PATH_KEYS.contains(key) && !strVal.isBlank() && baseDir != null) {
                    sorted.put(key, resolveFilesystemPath(strVal, baseDir, outputDir));
                } else if ("artifacts".equals(key) && val instanceof List<?> artifacts && baseDir != null) {
                    sorted.put(key, resolveArtifactPaths(artifacts, baseDir, outputDir));
                } else if ("command".equals(key) && val instanceof List<?> cmd && baseDir != null) {
                    sorted.put(key, resolveCommandPaths(cmd, baseDir, outputDir));
                } else {
                    sorted.put(key, resolvePaths(val, baseDir, outputDir));
                }
            }
            return sorted;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(resolvePaths(item, baseDir, outputDir));
            }
            return out;
        }
        return node;
    }

    private String resolveFilesystemPath(String rawPath, Path baseDir, Path outputDir) {
        if (hasUriScheme(rawPath)) {
            return rawPath;
        }
        Path p = Path.of(rawPath);
        if (p.isAbsolute()) {
            return p.normalize().toString().replace('\\', '/');
        }
        Path resolved = baseDir.resolve(p).normalize();
        if (outputDir != null && resolved.getRoot() != null
                && java.util.Objects.equals(resolved.getRoot(), outputDir.getRoot())) {
            String rel = outputDir.relativize(resolved).toString().replace('\\', '/');
            return rel.isEmpty() ? "." : rel;
        }
        return resolved.toString().replace('\\', '/');
    }

    private boolean hasUriScheme(String value) {
        int colon = value.indexOf(':');
        return colon > 1 && value.startsWith("://", colon);
    }

    private List<Object> resolveArtifactPaths(List<?> artifacts, Path baseDir, Path outputDir) {
        List<Object> out = new ArrayList<>(artifacts.size());
        for (Object item : artifacts) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> copy = new TreeMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    Object v = e.getValue();
                    if ("path".equals(k) && v instanceof String s && !s.isBlank()) {
                        copy.put(k, resolveFilesystemPath(s, baseDir, outputDir));
                    } else {
                        copy.put(k, resolvePaths(v, baseDir, outputDir));
                    }
                }
                out.add(copy);
            } else {
                out.add(resolvePaths(item, baseDir, outputDir));
            }
        }
        return out;
    }

    private List<Object> resolveCommandPaths(List<?> cmd, Path baseDir, Path outputDir) {
        List<Object> out = new ArrayList<>(cmd.size());
        for (Object item : cmd) {
            if (item instanceof String s && (s.startsWith("./") || s.startsWith("../"))) {
                out.add(resolveFilesystemPath(s, baseDir, outputDir));
            } else {
                out.add(item);
            }
        }
        return out;
    }

    private String renderCanonicalYaml(Map<String, Object> map) {
        try {
            Class.forName("org.yaml.snakeyaml.Yaml");
        } catch (ClassNotFoundException e) {
            throw new ConfigException("YAML serialization requires snakeyaml on the classpath");
        }
        return YamlDumper.dump(map);
    }

    private static final class YamlDumper {
        static String dump(Map<String, Object> map) {
            org.yaml.snakeyaml.DumperOptions opts = new org.yaml.snakeyaml.DumperOptions();
            opts.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
            opts.setPrettyFlow(true);
            opts.setIndent(2);
            return new org.yaml.snakeyaml.Yaml(opts).dump(map);
        }
    }
}