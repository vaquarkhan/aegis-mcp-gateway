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
package io.github.vaquarkhan.aegis.core.integrity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pinned tool digests. A pin records what a tool looked like the first time the gateway saw it, so
 * a later change can be detected instead of silently accepted.
 *
 * @author Viquar Khan
 */
public final class DigestRegistry {

    private final Map<String, String> pins = new ConcurrentHashMap<>();

    public DigestRegistry() {}

    public DigestRegistry(Map<String, String> initialPins) {
        if (initialPins != null) {
            initialPins.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    pins.put(k, v);
                }
            });
        }
    }

    /** Records a pin only if the tool has not been pinned before. Returns the effective pin. */
    public String pinIfAbsent(String toolName, String digest) {
        return pins.computeIfAbsent(toolName, k -> digest);
    }

    /** Overwrites an existing pin. Used when an operator explicitly accepts a catalog change. */
    public void pin(String toolName, String digest) {
        pins.put(toolName, digest);
    }

    public Optional<String> pinnedDigest(String toolName) {
        return Optional.ofNullable(pins.get(toolName));
    }

    public boolean isPinned(String toolName) {
        return pins.containsKey(toolName);
    }

    /** True when a previously pinned tool now presents a different digest. */
    public boolean changed(String toolName, String digest) {
        String pinned = pins.get(toolName);
        return pinned != null && !pinned.equals(digest);
    }

    public boolean matches(String toolName, String digest) {
        String pinned = pins.get(toolName);
        return pinned == null || pinned.equals(digest);
    }

    public int size() {
        return pins.size();
    }

    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pins));
    }

    public void clear() {
        pins.clear();
    }
}
