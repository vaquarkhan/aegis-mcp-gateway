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

import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rug-pull defence. Computes a stable digest over the governance relevant parts of a tool and
 * compares it against a pin.
 *
 * <p>The digest deliberately covers name, class, description and input schema. Description is
 * included because a tool poisoning attack often changes only the description, which is the part
 * the model actually reads.
 *
 * @author Viquar Khan
 */
public final class ToolCatalogIntegrity {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCatalogIntegrity.class);
    private static final String PREFIX = "sha256:";

    private final DigestRegistry registry;

    public ToolCatalogIntegrity(DigestRegistry registry) {
        this.registry = registry == null ? new DigestRegistry() : registry;
    }

    public DigestRegistry registry() {
        return registry;
    }

    /** Stable digest of one tool definition, formatted as {@code sha256:<hex>}. */
    public static String digestOf(ToolDef tool) {
        String canonical = tool.name() + "\n"
                + tool.cls().name() + "\n"
                + tool.description() + "\n"
                + normaliseSchema(tool.inputSchemaJson());
        return PREFIX + sha256Hex(canonical);
    }

    /** Digest over an ordered catalog, used to detect additions and removals as well as edits. */
    public static String digestOfCatalog(Collection<ToolDef> tools) {
        List<String> parts = new ArrayList<>();
        for (ToolDef t : tools) {
            parts.add(digestOf(t));
        }
        parts.sort(String::compareTo);
        return PREFIX + sha256Hex(String.join("\n", parts));
    }

    /**
     * Pins a tool on first sight and verifies it thereafter.
     *
     * @return true when the tool may be registered
     */
    public boolean verifyAndPin(ToolDef tool) {
        String digest = digestOf(tool);
        if (!registry.isPinned(tool.name())) {
            registry.pinIfAbsent(tool.name(), digest);
            return true;
        }
        if (registry.changed(tool.name(), digest)) {
            LOG.error("tool catalog integrity violation for {}: pinned={} observed={} (possible rug-pull)",
                    tool.name(), registry.pinnedDigest(tool.name()).orElse("-"), digest);
            return false;
        }
        return true;
    }

    /** Verifies against an externally supplied pin, for example from {@code tools.yaml}. */
    public boolean matchesExpected(ToolDef tool, String expectedDigest) {
        if (expectedDigest == null || expectedDigest.isBlank()) {
            return true;
        }
        String observed = digestOf(tool);
        boolean ok = observed.equalsIgnoreCase(expectedDigest.trim());
        if (!ok) {
            LOG.error("tool {} schema digest mismatch: expected={} observed={}",
                    tool.name(), expectedDigest, observed);
        }
        return ok;
    }

    /** Collapses insignificant whitespace so formatting changes do not look like tampering. */
    private static String normaliseSchema(String schemaJson) {
        if (schemaJson == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(schemaJson.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < schemaJson.length(); i++) {
            char c = schemaJson.charAt(i);
            if (inString) {
                sb.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                sb.append(c);
            } else if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
