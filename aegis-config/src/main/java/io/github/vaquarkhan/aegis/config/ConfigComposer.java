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
 * ownership and collision rules, resolves relative paths against each document's directory of
 * origin, and materializes a deterministic single-file configuration for {@code --config}.
 */
public final class ConfigComposer {

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

    /** Keys whose string values hold file/directory paths resolved relative to their file of origin. */
    private static final Set<String> PATH_KEYS = Set.of(
            "file", "signatureFile", "publicKeyFile", "keyFile", "pinFile",
            "directory", "tokenFile", "caFile", "signingKeyFile", "workingDir", "path"
    );

    public record ComposedConfig(
            Map<String, Object> effectiveMap,
            String effectiveYaml,
            String effectiveConfigHash
    ) {}

    public ComposedConfig compose(Path gatewayFile, List<Path> adopterFiles) throws IOException {
        if (gatewayFile == null || !Files.isRegularFile(gatewayFile)) {
            throw new ConfigException("gateway config file does not exist: " + gatewayFile);
        }
        if (adopterFiles == null || adopterFiles.isEmpty()) {
            throw new ConfigException("at least one adopter config file is required");
        }

        Path normalizedGateway = gatewayFile.toAbsolutePath().normalize();
        Path gatewayBaseDir = normalizedGateway.getParent();
        Map<String, Object> rawGateway = ConfigLoader.load(normalizedGateway);

        if (rawGateway.containsKey("sources")) {
            throw new ConfigException(normalizedGateway
                    + ": core gateway config must not define 'sources'; declare backends in adopter files");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> resolvedGateway =
                (Map<String, Object>) resolvePaths(rawGateway, gatewayBaseDir);

        List<Object> composedSources = new ArrayList<>();
        List<Object> composedScopes = copyList(resolvedGateway.get("scopes"));
        List<Object> composedHooks = copyList(resolvedGateway.get("hooks"));

        Set<String> seenSourceIds = new LinkedHashSet<>();
        Set<String> seenExposedTools = new LinkedHashSet<>();
        Set<String> seenHookNames = collectHookNames(composedHooks, normalizedGateway.toString());

        for (Path adopterFile : adopterFiles) {
            if (adopterFile == null || !Files.isRegularFile(adopterFile)) {
                throw new ConfigException("adopter config file does not exist: " + adopterFile);
            }
            Path normalizedAdopter = adopterFile.toAbsolutePath().normalize();
            Path adopterBaseDir = normalizedAdopter.getParent();
            Map<String, Object> rawAdopter = ConfigLoader.load(normalizedAdopter);

            validateAdopterSchema(rawAdopter, normalizedAdopter);
            checkCollisions(rawAdopter, normalizedAdopter, seenSourceIds, seenExposedTools, seenHookNames);

            @SuppressWarnings("unchecked")
            Map<String, Object> resolvedAdopter =
                    (Map<String, Object>) resolvePaths(rawAdopter, adopterBaseDir);

            // Lift adopter-declared scopes and hooks into top-level gateway collections
            Object adopterScopes = resolvedAdopter.remove("scopes");
            if (adopterScopes instanceof List<?> list) {
                composedScopes.addAll(list);
            }
            Object adopterHooks = resolvedAdopter.remove("hooks");
            if (adopterHooks instanceof List<?> list) {
                for (Object h : list) {
                    if (h instanceof Map<?, ?> hookMap) {
                        composedHooks.add(bindHookToSource(hookMap, (String) resolvedAdopter.get("id"), normalizedAdopter));
                    } else {
                        composedHooks.add(h);
                    }
                }
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
        String header = "# Generated by Aegis ConfigComposer\n"
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
            Set<String> seenHookNames
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
        if (adopter.get("hooks") instanceof List<?> hooks) {
            seenHookNames.addAll(collectHookNames(hooks, source.toString()));
        }
    }

    private Set<String> collectHookNames(List<?> hooks, String source) {
        Set<String> names = new LinkedHashSet<>();
        for (Object item : hooks) {
            if (item instanceof Map<?, ?> hm && hm.get("settings") instanceof Map<?, ?> settings
                    && settings.get("name") instanceof String name && !name.isBlank()) {
                if (!names.add(name)) {
                    throw new ConfigException(source + ": duplicate hook name '" + name + "'");
                }
            }
        }
        return names;
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
        }
        return copy;
    }

    private List<Object> copyList(Object val) {
        return val instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    private Object resolvePaths(Object node, Path baseDir) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object val = entry.getValue();
                if (val instanceof String strVal && PATH_KEYS.contains(key) && !strVal.isBlank() && baseDir != null) {
                    Path p = Path.of(strVal);
                    sorted.put(key, p.isAbsolute() ? p.normalize().toString() : baseDir.resolve(p).normalize().toString());
                } else if ("artifacts".equals(key) && val instanceof List<?> artifacts && baseDir != null) {
                    sorted.put(key, resolveArtifactPaths(artifacts, baseDir));
                } else if ("command".equals(key) && val instanceof List<?> cmd && baseDir != null) {
                    sorted.put(key, resolveCommandPaths(cmd, baseDir));
                } else {
                    sorted.put(key, resolvePaths(val, baseDir));
                }
            }
            return sorted;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(resolvePaths(item, baseDir));
            }
            return out;
        }
        return node;
    }

    private List<Object> resolveArtifactPaths(List<?> artifacts, Path baseDir) {
        List<Object> out = new ArrayList<>(artifacts.size());
        for (Object item : artifacts) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> copy = new TreeMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    Object v = e.getValue();
                    if ("path".equals(k) && v instanceof String s && !s.isBlank()) {
                        Path p = Path.of(s);
                        copy.put(k, p.isAbsolute() ? p.normalize().toString() : baseDir.resolve(p).normalize().toString());
                    } else {
                        copy.put(k, resolvePaths(v, baseDir));
                    }
                }
                out.add(copy);
            } else {
                out.add(resolvePaths(item, baseDir));
            }
        }
        return out;
    }

    private List<Object> resolveCommandPaths(List<?> cmd, Path baseDir) {
        List<Object> out = new ArrayList<>(cmd.size());
        for (Object item : cmd) {
            if (item instanceof String s && (s.startsWith("./") || s.startsWith("../"))) {
                out.add(baseDir.resolve(s).normalize().toString());
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