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

import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.util.Optional;

/**
 * Joins an upstream tool with operator governance settings. The configuration module provides the
 * implementation; this is where "config-driven, no Java per project" happens.
 *
 * <p>Contract: the returned class must be at least the operator-configured class and at least any
 * class implied by upstream hints (hints may only RAISE authority). Aegis 0.1's overlay could
 * downgrade DESTRUCTIVE to READ; implementations must not.
 */
@FunctionalInterface
public interface ToolBinder {

    /** @return the governed tool, or empty to hide the tool entirely */
    Optional<GovernedTool> bind(String sourceId, ToolSource.SourcedTool tool);
}
