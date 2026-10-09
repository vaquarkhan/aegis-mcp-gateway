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

import io.github.vaquarkhan.aegis.engine.control.ControlMode;
import io.github.vaquarkhan.aegis.engine.control.InjectionScreen;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The set of tools the gateway exposes, assembled from every configured {@link ToolSource}.
 *
 * <p>Integrity rules (all fail-closed):
 * <ul>
 *   <li>Two sources exposing the same name abort startup (Knox's ServiceLoader maps and 0.1's
 *       aggregator let the last registration win silently).</li>
 *   <li>A definition whose digest differs from its pin is QUARANTINED: hidden from
 *       {@code tools/list} and denied with {@code CATALOG_INTEGRITY} until an operator accepts it.</li>
 *   <li>A definition whose text matches a poisoning pattern is quarantined (screen mode ENFORCE).</li>
 *   <li>A tool that appears after startup is quarantined unless the operator opted in to TOFU for
 *       late tools.</li>
 * </ul>
 */
public final class ToolCatalog implements AutoCloseable {

    public enum Status { ACTIVE, QUARANTINED }

    /** One exposed tool and its integrity status. */
    public record Entry(GovernedTool tool, Status status, String reason, String digest) {
        public boolean active() {
            return status == Status.ACTIVE;
        }
    }

    private final List<ToolSource> sources;
    private final ToolBinder binder;
    private final CatalogIntegrity integrity;
    private final InjectionScreen descriptionScreen;
    private final boolean failOnSourceError;
    private final boolean acceptNewToolsAfterStart;
    private final Consumer<Map<String, Object>> events;
    private volatile Map<String, Entry> entries = Map.of();
    private volatile boolean loaded;

    public ToolCatalog(List<ToolSource> sources, ToolBinder binder, CatalogIntegrity integrity,
                       InjectionScreen descriptionScreen, boolean failOnSourceError,
                       boolean acceptNewToolsAfterStart, Consumer<Map<String, Object>> events) {
        this.sources = List.copyOf(sources);
        this.binder = binder;
        this.integrity = integrity;
        this.descriptionScreen = descriptionScreen;
        this.failOnSourceError = failOnSourceError;
        this.acceptNewToolsAfterStart = acceptNewToolsAfterStart;
        this.events = events == null ? e -> { } : events;
    }

    /** Startup load. Throws when a source fails (and failOnSourceError) or names collide. */
    public synchronized void load() throws Exception {
        entries = build(true);
        loaded = true;
    }

    /** Re-discovers every source and re-verifies pins; changed tools are quarantined. */
    public synchronized void refresh() throws Exception {
        if (!loaded) {
            load();
            return;
        }
        entries = build(false);
    }

    private Map<String, Entry> build(boolean startup) throws Exception {
        Map<String, Entry> previous = entries;
        Map<String, Entry> next = new TreeMap<>();
        for (ToolSource source : sources) {
            List<ToolSource.SourcedTool> offered;
            try {
                offered = source.discover();
            } catch (Exception e) {
                event("SOURCE_UNAVAILABLE", source.id(), null, e.getClass().getSimpleName() + ": " + e.getMessage());
                if (failOnSourceError && startup) {
                    throw new IllegalStateException("tool source '" + source.id() + "' failed discovery", e);
                }
                // Keep the previous entries of this source on refresh, so a transient outage does
                // not silently change the catalog; calls will fail at the backend and trip the breaker.
                for (Entry prev : previous.values()) {
                    if (prev.tool().sourceId().equals(source.id())) {
                        next.put(prev.tool().exposedName(), prev);
                    }
                }
                continue;
            }
            // Bind this source's tools first; only a consistent set is merged.
            Map<String, GovernedTool> mine = new LinkedHashMap<>();
            String conflict = null;
            for (ToolSource.SourcedTool st : offered) {
                Optional<GovernedTool> bound = binder.bind(source.id(), st);
                if (bound.isEmpty()) {
                    continue;
                }
                GovernedTool tool = bound.get();
                if (mine.containsKey(tool.exposedName()) || next.containsKey(tool.exposedName())) {
                    conflict = "duplicate tool name '" + tool.exposedName() + "' (source '" + source.id()
                            + "'); set a namePrefix or fix the source";
                    break;
                }
                mine.put(tool.exposedName(), tool);
            }
            if (conflict != null) {
                if (startup) {
                    throw new IllegalStateException(conflict);
                }
                // On refresh a bad listing must not freeze or partially apply: keep this source's
                // previous entries and report it.
                event("SOURCE_INVALID", source.id(), null, conflict);
                for (Entry prev : previous.values()) {
                    if (prev.tool().sourceId().equals(source.id()) && !next.containsKey(prev.tool().exposedName())) {
                        next.put(prev.tool().exposedName(), prev);
                    }
                }
                continue;
            }
            for (GovernedTool tool : mine.values()) {
                next.put(tool.exposedName(), classify(tool, startup || previous.containsKey(tool.exposedName()), previous));
            }
        }
        return Collections.unmodifiableMap(next);
    }

    private Entry classify(GovernedTool tool, boolean firstSightAllowed, Map<String, Entry> previous) throws Exception {
        String digest = tool.descriptor().definitionDigest();
        Entry prev = previous.get(tool.exposedName());
        boolean known = prev != null;
        CatalogIntegrity.Verdict v = integrity.verify(tool.exposedName(), digest,
                firstSightAllowed || (!known && acceptNewToolsAfterStart));
        Entry e;
        switch (v) {
            case MATCH, PINNED_NOW -> e = new Entry(tool, Status.ACTIVE, v == CatalogIntegrity.Verdict.PINNED_NOW ? "pinned on first sight" : "", digest);
            case MISMATCH -> e = new Entry(tool, Status.QUARANTINED, "definition changed since it was pinned", digest);
            default -> e = new Entry(tool, Status.QUARANTINED, "definition is not pinned", digest);
        }
        if (e.active() && descriptionScreen != null && descriptionScreen.mode() != ControlMode.OFF) {
            Optional<String> hit = descriptionScreen.firstMatch(tool.descriptor().toMcpMap());
            if (hit.isPresent()) {
                event("TOOL_DEFINITION_SUSPICIOUS", tool.sourceId(), tool.exposedName(), "matched: " + hit.get());
                if (descriptionScreen.mode() == ControlMode.ENFORCE) {
                    e = new Entry(tool, Status.QUARANTINED, "definition matches a tool-poisoning pattern", digest);
                }
            }
        }
        boolean changed = prev == null || !prev.digest().equals(digest) || prev.status() != e.status();
        if (changed) {
            event(e.active() ? "TOOL_ACTIVE" : "TOOL_QUARANTINED", tool.sourceId(), tool.exposedName(),
                    e.reason() + " digest=" + digest);
        }
        return e;
    }

    public Optional<Entry> lookup(String exposedName) {
        return Optional.ofNullable(entries.get(exposedName));
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    /**
     * Operator accepts a reviewed definition of a quarantined tool. The caller must supply the
     * exact digest it reviewed, so a refresh between review and acceptance cannot pin a different
     * upstream definition.
     */
    public synchronized boolean accept(String exposedName, String expectedDigest) throws Exception {
        Entry e = entries.get(exposedName);
        if (e == null || e.active() || expectedDigest == null || !expectedDigest.equals(e.digest())) {
            return false;
        }
        if (!integrity.accept(exposedName, expectedDigest)) {
            return false;
        }
        Map<String, Entry> copy = new LinkedHashMap<>(entries);
        copy.put(exposedName, new Entry(e.tool(), Status.ACTIVE, "accepted by operator", expectedDigest));
        entries = Collections.unmodifiableMap(new TreeMap<>(copy));
        event("TOOL_ACCEPTED", e.tool().sourceId(), exposedName, "digest=" + expectedDigest);
        return true;
    }

    private void event(String type, String source, String tool, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", "CATALOG");
        m.put("type", type);
        m.put("source", source);
        if (tool != null) {
            m.put("tool", tool);
        }
        m.put("detail", detail == null ? "" : detail);
        events.accept(m);
    }

    @Override
    public void close() {
        for (ToolSource s : sources) {
            try {
                s.close();
            } catch (Exception ignored) {
                // best effort on shutdown
            }
        }
    }
}
