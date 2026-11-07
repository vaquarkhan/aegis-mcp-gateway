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
package io.github.vaquarkhan.aegis.config;

import io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal plugin source, test scope only, standing in for a converter such as
 * {@code aegis-source-zookeeper}: it builds one executor per tool and does <b>not</b> dispatch by
 * upstream tool name, which is the shape {@code dryRun.previewTool} has to work for.
 *
 * <p>It counts how often each tool ran, so a test can assert that a dry run executed the preview and
 * not the destructive operation. Registered through
 * {@code META-INF/services/io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory} under
 * {@code src/test/resources}, so it exists only while this module's tests run.
 */
public final class PreviewPluginToolSourceFactory implements ToolSourceFactory {

    /** Calls per tool name, across every instance, so a test can read them after the gateway closes. */
    public static final Map<String, AtomicInteger> CALLS = new java.util.concurrent.ConcurrentHashMap<>();

    public static int calls(String tool) {
        AtomicInteger c = CALLS.get(tool);
        return c == null ? 0 : c.get();
    }

    public static void reset() {
        CALLS.clear();
    }

    @Override
    public String type() {
        return "preview-plugin";
    }

    @Override
    public ToolSource create(String id, ConfigNode settings) {
        boolean failDiscovery = settings.bool("failDiscovery", false);
        return new ToolSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<SourcedTool> discover() throws Exception {
                if (failDiscovery) {
                    throw new IllegalStateException("this source cannot list its tools");
                }
                return List.of(tool("peek", "Read the thing."), tool("wipe", "Destroy the thing."));
            }
        };
    }

    private static ToolSource.SourcedTool tool(String name, String description) {
        ToolDescriptor d = new ToolDescriptor(name, null, description,
                Json.parseObject("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"), null, null);
        return new ToolSource.SourcedTool(d, new ToolExecutor() {
            @Override
            public List<URI> egressTargets(Map<String, Object> backendArguments) {
                return List.of();
            }

            @Override
            public ToolResult execute(ExecutionContext ctx) {
                CALLS.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                return ToolResult.text("ran " + name, false);
            }
        });
    }
}
