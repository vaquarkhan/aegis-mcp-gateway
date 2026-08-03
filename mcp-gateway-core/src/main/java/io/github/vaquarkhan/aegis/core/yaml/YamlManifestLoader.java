/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core.yaml;

import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads the two YAML manifests: {@code gateway.yaml} settings and the {@code tools.yaml} catalog
 * overlay.
 *
 * <p>Parsing uses SnakeYAML's {@link SafeConstructor} with a bounded alias count, so a config file
 * cannot instantiate arbitrary classes or expand a billion-laughs alias bomb.
 *
 * <p>Gateway settings are returned flattened to dotted keys such as {@code http.port} so callers do
 * not have to walk nested maps.
 *
 * @author Viquar Khan
 */
public final class YamlManifestLoader {

    private static final Logger LOG = LoggerFactory.getLogger(YamlManifestLoader.class);
    private static final int MAX_ALIASES = 64;

    /**
     * One entry of a {@code tools.yaml} catalog overlay. An overlay may only reduce authority.
     *
     * @author Viquar Khan
     */
    public record ToolOverlay(
            String name,
            String engine,
            ToolClass cls,
            String description,
            boolean enabled,
            String schemaDigest,
            boolean requiresDryRun) {

        public ToolOverlay {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tools.yaml entry requires a name");
            }
        }
    }

    private YamlManifestLoader() {}

    /** Loads {@code gateway.yaml} and returns its settings flattened to dotted keys. */
    public static Map<String, Object> loadGateway(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("gateway config file missing or not a file: " + file);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return flatten(asMap(newYaml().load(reader)));
        }
    }

    /** Parses gateway settings from a string. Exposed for tests. */
    public static Map<String, Object> parseGateway(String yamlText) {
        return flatten(asMap(newYaml().load(new StringReader(yamlText))));
    }

    /** Loads a {@code tools.yaml} catalog overlay. */
    public static List<ToolOverlay> loadToolCatalog(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("tools catalog file missing or not a file: " + file);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parseCatalog(asMap(newYaml().load(reader)));
        }
    }

    /** Parses a catalog overlay from a string. Exposed for tests. */
    public static List<ToolOverlay> parseToolCatalog(String yamlText) {
        return parseCatalog(asMap(newYaml().load(new StringReader(yamlText))));
    }

    /** Indexes an overlay list by tool name, rejecting duplicates. */
    public static Map<String, ToolOverlay> index(List<ToolOverlay> overlays) {
        Map<String, ToolOverlay> byName = new LinkedHashMap<>();
        for (ToolOverlay o : overlays) {
            if (byName.put(o.name(), o) != null) {
                throw new IllegalArgumentException("duplicate tools.yaml entry: " + o.name());
            }
        }
        return Collections.unmodifiableMap(byName);
    }

    private static List<ToolOverlay> parseCatalog(Map<String, Object> root) {
        Object catalog = root.get("catalog");
        if (catalog == null) {
            return List.of();
        }
        if (!(catalog instanceof List<?> list)) {
            throw new IllegalArgumentException("tools.yaml 'catalog' must be a list");
        }
        List<ToolOverlay> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("tools.yaml catalog entries must be mappings");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            raw.forEach((k, v) -> entry.put(String.valueOf(k), v));
            out.add(new ToolOverlay(
                    str(entry.get("name")),
                    str(entry.get("engine")),
                    toolClass(entry.get("class")),
                    str(entry.get("description")),
                    bool(entry.get("enabled"), true),
                    str(entry.get("schemaDigest")),
                    bool(entry.get("requiresDryRun"), false)));
        }
        return Collections.unmodifiableList(out);
    }

    /** Flattens nested mappings into dotted keys. Lists are preserved as list values. */
    public static Map<String, Object> flatten(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        flattenInto("", source, out);
        return Collections.unmodifiableMap(out);
    }

    private static void flattenInto(String prefix, Map<String, Object> source, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : source.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> typed = new LinkedHashMap<>();
                nested.forEach((k, v) -> typed.put(String.valueOf(k), v));
                flattenInto(key, typed, out);
            } else {
                out.put(key, value);
            }
        }
    }

    /** Reads a dotted key as a comma or list valued string set. */
    public static Set<String> stringSet(Map<String, Object> flat, String key) {
        Object v = flat.get(key);
        Set<String> out = new LinkedHashSet<>();
        if (v == null) {
            return out;
        }
        if (v instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
            return out;
        }
        for (String part : String.valueOf(v).split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        return new Yaml(new SafeConstructor(options));
    }

    private static Map<String, Object> asMap(Object loaded) {
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("expected a YAML mapping at the document root");
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        map.forEach((k, v) -> typed.put(String.valueOf(k), v));
        return typed;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean bool(Object v, boolean fallback) {
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private static ToolClass toolClass(Object v) {
        String s = str(v);
        if (s == null) {
            return null;
        }
        try {
            return ToolClass.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOG.warn("unknown tool class in tools.yaml: {}", s);
            return null;
        }
    }
}
