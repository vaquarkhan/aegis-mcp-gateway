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

import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;

/**
 * Builds an in-process extension control from configuration:
 * {@code hooks: [{type: "pii-detector", mode: AUDIT, operations: [...], settings: {...}}]}. Discovered
 * with ServiceLoader
 * ({@code META-INF/services/io.github.vaquarkhan.aegis.config.spi.GovernanceHookFactory}).
 *
 * <p>A factory only builds the control. When it runs ({@code operations}, {@code methods},
 * {@code groups}, {@code sources}) and how strongly ({@code mode}) are the operator's choice, applied
 * by the gateway; see {@code io.github.vaquarkhan.aegis.engine.control.HookBinding}. So write the hook
 * as if it were always enforcing, and do not read those keys here.
 *
 * <p>If the control can live in another process, the built-in {@code type: remote} hook needs no Java
 * at all: it POSTs the operation to an HTTP endpoint that answers allow or deny.
 */
public interface GovernanceHookFactory {

    /** Value matched against the configured {@code type}. Must not be {@code remote} (built in). */
    String type();

    /** @return the control, never null; throwing fails startup, which is the right answer for a misconfigured control */
    GovernanceHook create(ConfigNode settings) throws Exception;
}
