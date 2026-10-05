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
package io.github.vaquarkhan.aegis.config.http;

import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.util.List;

/**
 * A REST API exposed as MCP tools purely from configuration. The tool definitions come from the
 * operator's file, so they are trusted and stable; they are still pinned like any other source,
 * which turns an unreviewed edit of the config into a visible catalog change.
 */
public final class HttpToolSource implements ToolSource {

    private final String id;
    private final List<SourcedTool> tools;

    public HttpToolSource(String id, List<SourcedTool> tools) {
        this.id = id;
        this.tools = List.copyOf(tools);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<SourcedTool> discover() {
        return tools;
    }
}
