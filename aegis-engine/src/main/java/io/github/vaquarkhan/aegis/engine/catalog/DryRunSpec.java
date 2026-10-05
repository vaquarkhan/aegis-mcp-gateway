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

import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;

/**
 * How a dry run (the "validate" phase of validate-run-promote) is performed for a tool.
 *
 * <ul>
 *   <li>{@link Mode#GATEWAY}: the full governance chain runs and nothing reaches the backend. The
 *       receipt proves this exact action passed preflight; it does not prove the backend would
 *       accept it. Works for every backend, which is why it is the default.</li>
 *   <li>{@link Mode#ARGUMENT}: the backend has a native preview flag (for example Kafka's
 *       {@code validateOnly}); the engine sets {@code argument} to {@code true} and executes.</li>
 *   <li>{@link Mode#TOOL}: the backend exposes a separate preview tool/endpoint, run with the same
 *       arguments through {@code previewExecutor}.</li>
 * </ul>
 */
public record DryRunSpec(Mode mode, String argument, ToolExecutor previewExecutor, String previewToolName) {

    public enum Mode { GATEWAY, ARGUMENT, TOOL }

    public static final DryRunSpec GATEWAY = new DryRunSpec(Mode.GATEWAY, null, null, null);

    public DryRunSpec {
        if (mode == null) {
            throw new IllegalArgumentException("dry-run mode must be set");
        }
        if (mode == Mode.ARGUMENT && (argument == null || argument.isBlank())) {
            throw new IllegalArgumentException("dry-run mode ARGUMENT needs the backend argument name");
        }
        if (mode == Mode.TOOL && previewExecutor == null) {
            throw new IllegalArgumentException("dry-run mode TOOL needs a preview executor");
        }
    }

    public static DryRunSpec argument(String name) {
        return new DryRunSpec(Mode.ARGUMENT, name, null, null);
    }

    public static DryRunSpec tool(ToolExecutor executor, String upstreamName) {
        return new DryRunSpec(Mode.TOOL, null, executor, upstreamName);
    }
}
