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
package io.github.vaquarkhan.aegis.config.spi;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;

/**
 * Pluggable tool-source type for backends that have neither an MCP server nor a REST API (for
 * example a Java admin client). Discovered with {@link java.util.ServiceLoader}; selected in
 * configuration by {@code type}. The factory contributes capability only: classification,
 * resources, approval and every other control still come from the source's {@code tools:} section.
 *
 * <p>Register in {@code META-INF/services/io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory}.
 */
public interface ToolSourceFactory {

    /** Value of {@code type} in configuration, e.g. {@code kafka-admin}. Must not be mcp or http. */
    String type();

    /**
     * @param id       source id
     * @param settings the source's {@code settings:} object; read every key you support (unknown keys fail startup)
     */
    ToolSource create(String id, ConfigNode settings) throws Exception;

    /**
     * Context-aware construction for providers that resolve names of credentials or other host services.
     * The default retains source and binary compatibility for existing providers: they receive the same
     * two-argument call until they deliberately opt into context-aware behavior.
     */
    default ToolSource create(String id, ConfigNode settings, BuildContext context) throws Exception {
        return create(id, settings);
    }
}
