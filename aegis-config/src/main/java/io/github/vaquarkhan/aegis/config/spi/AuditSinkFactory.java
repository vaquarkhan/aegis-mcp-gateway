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
import io.github.vaquarkhan.aegis.engine.spi.AuditSink;

/**
 * Pluggable additional audit destination (a SIEM, a message bus, a cloud log service), selected in
 * configuration as {@code audit.sinks: [{type: <type>, settings: {...}}]}. Every configured sink
 * receives every record AFTER the hash-chained journal accepted it, and a failure of any sink fails
 * the call closed (the same rule as the journal: no unrecorded governed call). The journal stays the
 * source of truth; a sink never replaces it.
 *
 * <p>Network sinks should send through {@link BuildContext#outbound} so their connections are pinned to
 * addresses the egress control approved. In complete control a provider JAR must be listed in
 * {@code completeControl.artifacts} with role {@code plugin}.
 *
 * <p>Register in {@code META-INF/services/io.github.vaquarkhan.aegis.config.spi.AuditSinkFactory}.
 */
public interface AuditSinkFactory {
    /** Value of {@code type} in configuration. */
    String type();

    /**
     * @param settings the sink's {@code settings:} object; read every key you support (unknown keys fail startup)
     * @param context  host services (secrets via {@code context.env()}, pinned transport via {@code context.outbound})
     */
    AuditSink create(ConfigNode settings, BuildContext context) throws Exception;
}
