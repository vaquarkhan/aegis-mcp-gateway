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
package io.github.vaquarkhan.aegis.engine.spi;

import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import java.net.URI;
import java.util.List;

/**
 * A backend that offers tools: an existing MCP server (proxied), a declarative HTTP API, or a Java
 * plugin. This is the adoption seam: a project hooks in by configuring a source, not by writing
 * governance code.
 */
public interface ToolSource extends AutoCloseable {

    /** Operator-assigned, unique source id (appears in audit and policy). */
    String id();

    /**
     * Lists the tools this source currently offers. Called at startup and on catalog refresh; the
     * engine pins and compares their definition digests.
     */
    List<SourcedTool> discover() throws Exception;

    /**
     * Network endpoints this source connects to that come from its configuration, not from call
     * arguments. A plugin that speaks its own protocol (a Kafka Admin client, a gRPC client) reports
     * them here, for example {@code tcp://broker-1:9092}. The configuration loader treats them like
     * the {@code baseUrl} of an HTTP source: each host joins the egress host list and each scheme
     * joins the allowed schemes. The source's executors report the same endpoints from
     * {@link ToolExecutor#egressTargets}, so the egress control checks the configured host and every
     * DNS answer on every call. Protocols that learn additional peers after connecting (Kafka broker
     * metadata, for example) need their listener configuration and network policy to keep that later
     * topology hop inside the same boundary. The default is none.
     */
    default List<URI> configuredEndpoints() {
        return List.of();
    }

    @Override
    default void close() {}

    /** A tool as offered by a source, with the executor that runs it. */
    record SourcedTool(ToolDescriptor descriptor, ToolExecutor executor) {}
}
