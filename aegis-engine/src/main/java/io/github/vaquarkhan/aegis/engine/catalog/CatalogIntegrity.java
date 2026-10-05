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

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tool-definition pinning (rug-pull / tool-poisoning defence, OWASP MCP Top 10 MCP03).
 *
 * <p>Pins come from two places: operator pins in configuration (authoritative) and, in TOFU mode,
 * pins recorded the first time a tool is seen, persisted to an optional pin file so a restart does
 * not silently re-trust a changed definition. In REQUIRE_PINS mode an unpinned tool is quarantined.
 */
public final class CatalogIntegrity {

    public enum Mode { TOFU, REQUIRE_PINS }

    public enum Verdict { MATCH, PINNED_NOW, MISMATCH, UNPINNED }

    private final Mode mode;
    private final Map<String, String> configPins;
    private final Path pinFile;
    private final TreeMap<String, String> learned = new TreeMap<>();

    public CatalogIntegrity(Mode mode, Map<String, String> configPins, Path pinFile) throws IOException {
        this.mode = mode == null ? Mode.TOFU : mode;
        this.configPins = configPins == null ? Map.of() : Map.copyOf(configPins);
        this.pinFile = pinFile;
        if (pinFile != null && Files.exists(pinFile)) {
            Map<String, Object> m = Json.parseObject(Files.readAllBytes(pinFile), JsonLimits.CONFIG);
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!(e.getValue() instanceof String s)) {
                    throw new IOException("pin file " + pinFile + " has a non-string pin for " + e.getKey());
                }
                learned.put(e.getKey(), s);
            }
        }
    }

    public Mode mode() {
        return mode;
    }

    /**
     * @param firstSightAllowed whether a never-seen tool may be pinned now (true at startup in TOFU
     *                          mode; false for tools that appear later unless the operator opts in)
     */
    public synchronized Verdict verify(String toolName, String digest, boolean firstSightAllowed) throws IOException {
        String pinned = configPins.get(toolName);
        if (pinned == null) {
            pinned = learned.get(toolName);
        }
        if (pinned != null) {
            return pinned.equals(digest) ? Verdict.MATCH : Verdict.MISMATCH;
        }
        if (mode == Mode.REQUIRE_PINS || !firstSightAllowed) {
            return Verdict.UNPINNED;
        }
        learned.put(toolName, digest);
        persist();
        return Verdict.PINNED_NOW;
    }

    /** Operator acceptance of a changed or new definition (admin API). Config pins cannot be overridden. */
    public synchronized boolean accept(String toolName, String digest) throws IOException {
        if (configPins.containsKey(toolName)) {
            return false;
        }
        learned.put(toolName, digest);
        persist();
        return true;
    }

    public synchronized String pinFor(String toolName) {
        String p = configPins.get(toolName);
        return p != null ? p : learned.get(toolName);
    }

    private void persist() throws IOException {
        if (pinFile == null) {
            return;
        }
        Path parent = pinFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = pinFile.resolveSibling(pinFile.getFileName() + ".tmp");
        Files.write(tmp, Json.write(learned).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, pinFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, pinFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
