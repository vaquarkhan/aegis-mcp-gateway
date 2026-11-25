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
            # Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
            #
            # Licensed under the Apache License, Version 2.0 (the "License");
            # you may not use this file except in compliance with the License.
            # You may obtain a copy of the License at
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
            "policy", "scopes", "rateLimits", "breaker", "screening", "output",
            "audit", "catalog", "server", "metrics", "tracing",
            "state", "sources"
    );

    /**
     * Top-level keys allowed inside a single-adopter configuration file (Issue #38):
     * source integration fields (normalized into {@code sources[]}), plus optional
     * source-bound adapter {@code hooks}.
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
            // Source-bound adapter hooks (merged into top-level hooks with sources=[id])
            "hooks"
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
                resolveGatewayPaths(rawGateway, gatewayBaseDir, normalizedOutputDir);

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
                    resolveAdopterPaths(rawAdopter, adopterBaseDir, normalizedOutputDir);

            // Lift adopter-declared hooks into top-level gateway hooks bound to this source (fail-closed)
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
        Path validationBaseDir = normalizedOutputDir != null ? normalizedOutputDir : gatewayBaseDir;
        validateEffectiveRuntimeSchema(effective, validationBaseDir);
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

    /** Ensures adopter-defined hooks are bound strictly to their own source. */
    private Map<String, Object> bindHookToSource(Map<?, ?> hookMap, String sourceId, Path origin) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : hookMap.entrySet()) {
            copy.put(String.valueOf(e.getKey()), e.getValue());
        }
        Object sourcesObj = copy.get("sources");
        if (sourcesObj == null) {
            copy.put("sources", List.of(sourceId));
        } else if (sourcesObj instanceof List<?> list) {
            if (list.isEmpty()) {
                throw new ConfigException(origin + ": adopter hook 'sources' cannot be empty; must bind to '" + sourceId + "'");
            }
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

    private Map<String, Object> resolveGatewayPaths(Map<String, Object> rawGateway, Path baseDir, Path outputDir) {
        Map<String, Object> root = canonicalizeMap(rawGateway);
        if (baseDir == null) {
            return root;
        }
        resolveMapKeys(root, "approval", baseDir, outputDir, "keyFile");
        resolveMapKeys(root, "serverCatalog", baseDir, outputDir, "file", "signatureFile", "publicKeyFile");
        if (root.get("serverCatalog") instanceof Map<?, ?> sc) {
            @SuppressWarnings("unchecked")
            Map<String, Object> scMap = (Map<String, Object>) sc;
            resolveMapKeys(scMap, "breakGlass", baseDir, outputDir, "file", "signatureFile", "publicKeyFile");
        }
        resolveMapKeys(root, "audit", baseDir, outputDir, "file");
        if (root.get("audit") instanceof Map<?, ?> audit) {
            @SuppressWarnings("unchecked")
            Map<String, Object> auditMap = (Map<String, Object>) audit;
            resolveMapKeys(auditMap, "checkpoint", baseDir, outputDir, "file", "signingKeyFile");
        }
        resolveMapKeys(root, "catalog", baseDir, outputDir, "pinFile");
        if (root.get("catalog") instanceof Map<?, ?> catalog) {
            @SuppressWarnings("unchecked")
            Map<String, Object> catalogMap = (Map<String, Object>) catalog;
            resolveMapKeys(catalogMap, "manifest", baseDir, outputDir, "file", "signatureFile", "publicKeyFile");
        }
        if (root.get("secrets") instanceof Map<?, ?> secrets) {
            @SuppressWarnings("unchecked")
            Map<String, Object> secretsMap = (Map<String, Object>) secrets;
            Object sourceType = secretsMap.get("source");
            if ("file".equals(sourceType)) {
                resolveMapKeys(secretsMap, "settings", baseDir, outputDir, "directory");
            } else if ("vault".equals(sourceType)) {
                resolveMapKeys(secretsMap, "settings", baseDir, outputDir, "tokenFile", "caFile");
            }
        }
        if (root.get("state") instanceof Map<?, ?> state) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stateMap = (Map<String, Object>) state;
            Object storeType = stateMap.get("store");
            if ("derby".equals(storeType)) {
                resolveMapKeys(stateMap, "settings", baseDir, outputDir, "directory");
            } else if ("postgres".equals(storeType)) {
                resolveMapKeys(stateMap, "settings", baseDir, outputDir,
                        "clientPropertiesFile", "caFile", "keystoreFile", "truststoreFile");
            }
        }
        return root;
    }

    private Map<String, Object> resolveAdopterPaths(Map<String, Object> rawAdopter, Path baseDir, Path outputDir) {
        Map<String, Object> root = canonicalizeMap(rawAdopter);
        if (baseDir == null) {
            return root;
        }
        String workingDir = root.get("workingDir") instanceof String wd && !wd.isBlank() ? wd : null;
        if (workingDir != null) {
            root.put("workingDir", resolveFilesystemPath(workingDir, baseDir, outputDir));
        }
        if (root.get("artifacts") instanceof List<?> artifacts) {
            root.put("artifacts", resolveArtifactPaths(artifacts, baseDir, outputDir));
        }
        // When workingDir is set, GatewayFactory resolves relative command entries against workingDir
        // (which has already been rebased), so command[] must remain untouched. Only rebase relative
        // command[] entries when no workingDir is configured.
        if (workingDir == null && root.get("command") instanceof List<?> cmd) {
            root.put("command", resolveCommandPaths(cmd, baseDir, outputDir));
        }
        return root;
    }

    private void resolveMapKeys(Map<String, Object> parent, String sectionKey, Path baseDir, Path outputDir, String... pathKeys) {
        if (!(parent.get(sectionKey) instanceof Map<?, ?> section)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> sectionMap = (Map<String, Object>) section;
        for (String pathKey : pathKeys) {
            if (sectionMap.get(pathKey) instanceof String strVal && !strVal.isBlank()) {
                sectionMap.put(pathKey, resolveFilesystemPath(strVal, baseDir, outputDir));
            }
        }
    }

    private Map<String, Object> canonicalizeMap(Map<?, ?> map) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), canonicalizeValue(entry.getValue()));
        }
        return sorted;
    }

    private Object canonicalizeValue(Object node) {
        if (node instanceof Map<?, ?> map) {
            return canonicalizeMap(map);
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(canonicalizeValue(item));
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
        // Preserve plain runtime-relative paths (e.g. "audit/aegis-hive.jsonl") verbatim;
        // only rebase paths explicitly anchored to the authoring file ("./..." or "../...").
        if (!rawPath.startsWith("./") && !rawPath.startsWith("../")
                && !rawPath.startsWith(".\\") && !rawPath.startsWith("..\\")) {
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
                Map<String, Object> copy = canonicalizeMap(m);
                if (copy.get("path") instanceof String s && !s.isBlank()) {
                    copy.put("path", resolveFilesystemPath(s, baseDir, outputDir));
                }
                out.add(copy);
            } else {
                out.add(canonicalizeValue(item));
            }
        }
        return out;
    }

    private List<Object> resolveCommandPaths(List<?> cmd, Path baseDir, Path outputDir) {
        List<Object> out = new ArrayList<>(cmd.size());
        for (Object item : cmd) {
            if (item instanceof String s && (s.startsWith("./") || s.startsWith("../")
                    || s.startsWith(".\\") || s.startsWith("..\\"))) {
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
    @SuppressWarnings("unchecked")
    private void validateEffectiveRuntimeSchema(Map<String, Object> effective, Path baseDir) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("aegis-compose-validate");
            Map<String, Object> validationDoc = prepareValidationDoc(effective, tempDir);
            BuildContext ctx = BuildContext.defaults(baseDir)
                    .withEnv(name -> GatewayFactory.REQUIRED_CATALOG_KEY.equals(name)
                            || GatewayFactory.REQUIRED_ATTESTATION_KEY.equals(name)
                            ? null : "compose-schema-check-placeholder");
            try {
                try (Gateway ignored = GatewayFactory.build(validationDoc, ctx)) {
                    // Full runtime schema validated.
                }
            } catch (ConfigException e) {
                if (!isMissingPluginSourceFactoryOnClasspath(e)) {
                    throw e;
                }
                // When invoked from a module classpath that lacks optional SPI source plugins
                // (e.g. aegis-config unit tests without aegis-source-jdbc), still validate all
                // top-level gateway sections, hooks, policy rules, scopes, and unknown keys.
                Map<String, Object> coreValidationDoc = replaceUnloadedPluginSources(validationDoc);
                try (Gateway ignored = GatewayFactory.build(coreValidationDoc, ctx)) {
                    // Core gateway schema, hooks, policy, scopes, and unknown keys validated.
                }
            }
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new ConfigException("composed configuration failed runtime schema validation: " + detail, e);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> prepareValidationDoc(Map<String, Object> effective, Path tempDir) {
        Map<String, Object> validationDoc = new LinkedHashMap<>(effective);
        validationDoc.put("audit", Map.of("file", tempDir.resolve("validate-audit.jsonl").toString()));
        validationDoc.remove("state");
        validationDoc.remove("tracing");
        if (validationDoc.get("secrets") instanceof Map<?, ?> secrets
                && !"file".equals(secrets.get("source"))) {
            validationDoc.remove("secrets");
        }
        Map<String, Object> catalog = validationDoc.get("catalog") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>();
        catalog.put("failOnSourceError", false);
        catalog.remove("refreshSeconds");
        validationDoc.put("catalog", catalog);
        return validationDoc;
    }

    private boolean isMissingPluginSourceFactoryOnClasspath(ConfigException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("others need an admitted ToolSourceFactory on the classpath");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> replaceUnloadedPluginSources(Map<String, Object> validationDoc) {
        Map<String, Object> copy = new LinkedHashMap<>(validationDoc);
        if (!(copy.get("sources") instanceof List<?> sources)) {
            return copy;
        }
        List<Object> normalizedSources = new ArrayList<>(sources.size());
        for (Object item : sources) {
            if (item instanceof Map<?, ?> srcMap) {
                Object typeObj = srcMap.get("type");
                String type = typeObj instanceof String s ? s : "";
                if (!"mcp".equals(type) && !"http".equals(type) && srcMap.get("id") instanceof String id) {
                    Map<String, Object> stub = new LinkedHashMap<>();
                    stub.put("id", id);
                    stub.put("type", "http");
                    stub.put("baseUrl", "https://plugin-schema-placeholder.invalid");
                    stub.put("tools", List.of(Map.of(
                            "name", "placeholder",
                            "description", "Placeholder for unloaded SPI source plugin",
                            "class", "READ",
                            "request", Map.of("method", "GET", "path", "/")
                    )));
                    normalizedSources.add(stub);
                    continue;
                }
            }
            normalizedSources.add(item);
        }
        copy.put("sources", normalizedSources);
        return copy;
    }

    private void deleteTempDir(Path dir) {
        if (dir == null) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup
        }
    }
}