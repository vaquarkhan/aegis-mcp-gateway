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

import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor.ExecutionException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A backend that offers MCP resources, resource templates, prompts and argument completion (an
 * existing MCP server). Resources and prompts are governed as READ actions: they cannot change
 * backend state, but they put data and instructions into the model's context, so they get the
 * same exposure, policy, egress, rate, audit, redaction and screening controls as tool calls.
 * Implementations are only ever called after governance allowed the request.
 */
public interface ContentSource {

    String id();

    /** HTTP endpoint for the egress check; null for a stdio subprocess. */
    URI endpoint();

    /**
     * Per-request backend context.
     *
     * @param credentials   headers resolved for this source after governance (never the caller's token)
     * @param timeoutMillis budget for the whole backend interaction
     * @param traceparent   W3C {@code traceparent} to forward (null during startup discovery)
     * @param roundTrip     MCP 2026-07-28 multi round-trip retry data to forward (never null)
     */
    record Request(Map<String, String> credentials, long timeoutMillis, String traceparent,
                   io.github.vaquarkhan.aegis.engine.model.RoundTrip roundTrip) {
        public Request(Map<String, String> credentials, long timeoutMillis, String traceparent) {
            this(credentials, timeoutMillis, traceparent, io.github.vaquarkhan.aegis.engine.model.RoundTrip.NONE);
        }

        public Request {
            credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
            roundTrip = roundTrip == null ? io.github.vaquarkhan.aegis.engine.model.RoundTrip.NONE : roundTrip;
        }
    }

    List<Map<String, Object>> listResources(Request request) throws ExecutionException;

    List<Map<String, Object>> listResourceTemplates(Request request) throws ExecutionException;

    /** @return the MCP {@code ReadResourceResult} ({@code contents[]}) */
    Map<String, Object> readResource(String uri, Request request) throws ExecutionException;

    List<Map<String, Object>> listPrompts(Request request) throws ExecutionException;

    /** @return the MCP {@code GetPromptResult} ({@code description}, {@code messages[]}) */
    Map<String, Object> getPrompt(String name, Map<String, Object> arguments, Request request) throws ExecutionException;

    /** @param params {@code completion/complete} params with the ref already mapped to upstream names */
    Map<String, Object> complete(Map<String, Object> params, Request request) throws ExecutionException;
}
