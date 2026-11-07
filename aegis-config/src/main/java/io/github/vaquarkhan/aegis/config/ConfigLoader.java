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

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads a configuration file into the engine's JSON value model. JSON needs no extra library;
 * YAML uses snakeyaml if it is on the classpath (Apache Knox 3.0 ships snakeyaml 2.4) with the
 * safe constructor, duplicate keys rejected, and alias/nesting/size limits.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    public static Map<String, Object> load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return parseYaml(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), file.toString());
        }
        try {
            return Json.parseObject(bytes, JsonLimits.CONFIG);
        } catch (JsonException e) {
            throw new ConfigException(file + ": " + e.getMessage());
        }
    }

    public static Map<String, Object> parseYaml(String text, String source) {
        try {
            Class.forName("org.yaml.snakeyaml.Yaml");
        } catch (ClassNotFoundException e) {
            throw new ConfigException(source + ": YAML configuration needs snakeyaml on the classpath; use JSON otherwise");
        }
        Object root = YamlSupport.load(text);
        Object v = normalise(root, source, 0);
        if (!(v instanceof Map)) {
            throw new ConfigException(source + ": configuration root must be a mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    /** Converts snakeyaml values into the engine value model (Long/BigDecimal numbers, String keys). */
    private static Object normalise(Object v, String source, int depth) {
        if (depth > 64) {
            throw new ConfigException(source + ": configuration nesting too deep");
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
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) {
                    throw new ConfigException(source + ": configuration keys must be strings (got " + e.getKey() + ")");
                }
                out.put(k, normalise(e.getValue(), source, depth + 1));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                out.add(normalise(o, source, depth + 1));
            }
            return out;
        }
        throw new ConfigException(source + ": unsupported YAML value type " + v.getClass().getSimpleName()
                + " (quote dates and other special scalars)");
    }

    /** Isolated so the class only links against snakeyaml when YAML is actually used. */
    private static final class YamlSupport {
        static Object load(String text) {
            org.yaml.snakeyaml.LoaderOptions opts = new org.yaml.snakeyaml.LoaderOptions();
            opts.setAllowDuplicateKeys(false);
            opts.setMaxAliasesForCollections(20);
            opts.setAllowRecursiveKeys(false);
            opts.setNestingDepthLimit(64);
            opts.setCodePointLimit(8 * 1024 * 1024);
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(opts));
            try {
                return yaml.load(text);
            } catch (RuntimeException e) {
                throw new ConfigException("invalid YAML: " + e.getMessage(), e);
            }
        }
    }
}
