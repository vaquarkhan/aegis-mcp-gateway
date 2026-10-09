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

import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Strict view over one configuration object. Every key read is recorded; {@link #finish()} fails
 * on any key that was never read. A typo such as {@code writeGrups} therefore stops startup
 * instead of silently leaving a control at its default (fail-closed configuration).
 */
public final class ConfigNode {

    private final String path;
    private final Map<String, Object> map;
    private final Set<String> read = new LinkedHashSet<>();
    private final List<ConfigNode> children = new ArrayList<>();

    public ConfigNode(String path, Map<String, Object> map) {
        this.path = path;
        this.map = map == null ? Map.of() : map;
    }

    public String path() {
        return path;
    }

    public boolean has(String key) {
        read.add(key);
        return map.containsKey(key) && map.get(key) != null;
    }

    public String string(String key, String def) {
        read.add(key);
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof String s)) {
            throw error(key, "must be a string");
        }
        return s;
    }

    public String requiredString(String key) {
        String s = string(key, null);
        if (s == null || s.isBlank()) {
            throw error(key, "is required");
        }
        return s;
    }

    public long integer(String key, long def) {
        read.add(key);
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        Long l = JsonValues.integral(v);
        if (l == null) {
            throw error(key, "must be an integer");
        }
        return l;
    }

    public boolean bool(String key, boolean def) {
        read.add(key);
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Boolean b)) {
            throw error(key, "must be true or false");
        }
        return b;
    }

    public List<String> strings(String key) {
        read.add(key);
        Object v = map.get(key);
        if (v == null) {
            return List.of();
        }
        List<Object> l = JsonValues.asList(v);
        if (l == null) {
            throw error(key, "must be a list of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof String s)) {
                throw error(key, "must be a list of strings");
            }
            out.add(s);
        }
        return out;
    }

    public Set<String> stringSet(String key) {
        return new TreeSet<>(strings(key));
    }

    public ConfigNode child(String key) {
        read.add(key);
        Object v = map.get(key);
        if (v != null && !(v instanceof Map)) {
            throw error(key, "must be an object");
        }
        ConfigNode c = new ConfigNode(path + "." + key, JsonValues.asObject(v));
        children.add(c);
        return c;
    }

    public List<ConfigNode> children(String key) {
        read.add(key);
        Object v = map.get(key);
        if (v == null) {
            return List.of();
        }
        List<Object> l = JsonValues.asList(v);
        if (l == null) {
            throw error(key, "must be a list of objects");
        }
        List<ConfigNode> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            if (!(l.get(i) instanceof Map)) {
                throw error(key + "[" + i + "]", "must be an object");
            }
            ConfigNode c = new ConfigNode(path + "." + key + "[" + i + "]", JsonValues.asObject(l.get(i)));
            children.add(c);
            out.add(c);
        }
        return out;
    }

    /** Map whose values are objects, e.g. {@code tools: {name: {...}}}. */
    public Map<String, ConfigNode> childMap(String key) {
        read.add(key);
        Object v = map.get(key);
        if (v == null) {
            return Map.of();
        }
        Map<String, Object> m = JsonValues.asObject(v);
        if (m == null) {
            throw error(key, "must be an object");
        }
        Map<String, ConfigNode> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                throw error(key + "." + e.getKey(), "must be an object");
            }
            ConfigNode c = new ConfigNode(path + "." + key + "." + e.getKey(), JsonValues.asObject(e.getValue()));
            children.add(c);
            out.put(e.getKey(), c);
        }
        return out;
    }

    /** Raw value, for JSON-typed fields such as inputSchema or a body template. */
    public Object raw(String key) {
        read.add(key);
        return map.get(key);
    }

    /** Map of string to string. */
    public Map<String, String> stringMap(String key) {
        read.add(key);
        Object v = map.get(key);
        if (v == null) {
            return Map.of();
        }
        Map<String, Object> m = JsonValues.asObject(v);
        if (m == null) {
            throw error(key, "must be an object of strings");
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!(e.getValue() instanceof String s)) {
                throw error(key + "." + e.getKey(), "must be a string");
            }
            out.put(e.getKey(), s);
        }
        return out;
    }

    /** Read-only view of this node's raw values, for digesting a reviewed block; marks nothing as read. */
    public Map<String, Object> values() {
        return java.util.Collections.unmodifiableMap(map);
    }

    /** Marks every key of this node as intentionally ignored (a block that is digested but never used). */
    public void skipAll() {
        read.addAll(map.keySet());
    }

    /** Marks a key as intentionally ignored by this reader (host-specific sections). */
    public void skip(String key) {
        read.add(key);
    }

    /** Fails on unknown keys in this node and every child created from it. */
    public void finish() {
        Set<String> unknown = new TreeSet<>(map.keySet());
        unknown.removeAll(read);
        if (!unknown.isEmpty()) {
            throw new ConfigException(path + ": unknown key(s) " + unknown);
        }
        for (ConfigNode c : children) {
            c.finish();
        }
    }

    public ConfigException error(String key, String what) {
        return new ConfigException(path + "." + key + " " + what);
    }
}
