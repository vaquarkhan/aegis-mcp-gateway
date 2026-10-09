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
package io.github.vaquarkhan.aegis.source.a2a;

import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code type: a2a} source: one A2A 1.0 agent as one governed MCP tool. Settings:
 * <pre>
 * settings:
 *   cardUrl: https://agents.internal.example/inventory/.well-known/agent-card.json   # required
 *   endpointUrl: https://agents.internal.example/inventory/rpc   # optional; pins the JSON-RPC URL the card must name
 *   toolName: inventory_agent      # optional; default is the source id with '-' as '_'
 *   maxTextChars: 16000            # bound on the message text (1..1000000)
 *   maxResponseBytes: 1048576      # bound on one reply (1 KiB..16 MiB)
 *   cardTimeoutMs: 5000            # bound on reading the agent card
 * </pre>
 * Classify the tool in the source's {@code tools:} section by the strongest thing the agent can do;
 * unclassified it is DESTRUCTIVE. The agent's host must be on {@code egress.allowHosts}: the call is
 * checked like any other backend call. Credentials for the agent go in the source's
 * {@code credentials:} section and are sent on calls only, never when reading the public card.
 */
public final class A2aToolSourceFactory implements ToolSourceFactory {

    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    @Override
    public String type() {
        return "a2a";
    }

    @Override
    public ToolSource create(String id, ConfigNode settings) {
        URI card = url(settings, "cardUrl", true);
        URI pinned = url(settings, "endpointUrl", false);
        String toolName = settings.string("toolName", id.replace('-', '_'));
        if (!TOOL_NAME.matcher(toolName).matches()) {
            throw settings.error("toolName", "must be 1 to 64 letters, digits, '_', '.' or '-'");
        }
        long maxText = settings.integer("maxTextChars", 16_000);
        if (maxText < 1 || maxText > 1_000_000) {
            throw settings.error("maxTextChars", "must be between 1 and 1000000");
        }
        long maxBytes = settings.integer("maxResponseBytes", 1 << 20);
        if (maxBytes < 1024 || maxBytes > 16L << 20) {
            throw settings.error("maxResponseBytes", "must be between 1024 and 16777216");
        }
        long cardTimeout = settings.integer("cardTimeoutMs", 5_000);
        if (cardTimeout < 100 || cardTimeout > 60_000) {
            throw settings.error("cardTimeoutMs", "must be between 100 and 60000");
        }
        return new A2aToolSource(id, card, pinned, toolName, (int) maxText, (int) maxBytes, cardTimeout);
    }

    private static URI url(ConfigNode settings, String key, boolean required) {
        String raw = required ? settings.requiredString(key) : settings.string(key, null);
        if (raw == null) {
            return null;
        }
        URI u;
        try {
            u = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw settings.error(key, "is not a valid URL");
        }
        if (u.getScheme() == null || u.getHost() == null
                || !(u.getScheme().toLowerCase(Locale.ROOT).equals("http") || u.getScheme().toLowerCase(Locale.ROOT).equals("https"))) {
            throw settings.error(key, "must be an absolute http or https URL");
        }
        if (u.getRawUserInfo() != null) {
            throw settings.error(key, "must not carry credentials; use the source's credentials section");
        }
        return u;
    }
}
