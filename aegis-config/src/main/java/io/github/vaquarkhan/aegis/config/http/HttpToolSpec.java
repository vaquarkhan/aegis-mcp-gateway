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

import io.github.vaquarkhan.aegis.engine.json.JsonPointer;
import java.util.Map;

/**
 * One declarative REST-backed tool: how MCP arguments map onto an HTTP request. This is what lets
 * an Apache project with a REST API (Flink, Spark, Iceberg REST catalog, Kafka Connect, ...) get a
 * governed MCP surface with configuration only.
 *
 * <p>Templating is deliberately narrow and injection-safe:
 * <ul>
 *   <li>{@code path}: {@code {arg}} placeholders become ONE percent-encoded path segment each;
 *       values that are empty, {@code .} or {@code ..} are refused, so an argument cannot change the
 *       route or climb the path.</li>
 *   <li>{@code path}: {@code {arg/}} is a <b>path-typed</b> value of several segments, for APIs whose
 *       identifier is itself a path (HDFS files, ZooKeeper znodes, object-store keys). Each segment is
 *       validated and encoded separately, so {@code /user/hive/warehouse} is accepted and
 *       {@code /user/../etc} is refused. Use {@code {arg}} unless the API really needs a path.</li>
 *   <li>{@code query}: values are literal or exactly {@code {arg}}; absent optional args are omitted.</li>
 *   <li>{@code body}: a JSON template; a string that is exactly {@code {arg}} is replaced by the
 *       argument's JSON value (type preserved), {@code {*}} by the whole argument object. Text is
 *       never spliced into larger strings.</li>
 * </ul>
 *
 * @param method      HTTP method
 * @param path        path template appended to the source base URL
 * @param query       query parameter templates
 * @param body        JSON body template, or null for no body
 * @param headers     static request headers (no templating; credentials come from the credential provider)
 * @param responsePointer optional JSON pointer selecting the part of a JSON response returned to the model
 * @param responseStatusCheck optional raw-response integral success assertion, evaluated before responsePointer projection
 * @param maxResponseBytes response bound
 */
public record HttpToolSpec(
        String method,
        String path,
        Map<String, String> query,
        Object body,
        Map<String, String> headers,
        JsonPointer responsePointer,
        ResponseStatusCheck responseStatusCheck,
        int maxResponseBytes) {

    /** An exact integral application-status assertion in an otherwise successful HTTP JSON response. */
    public record ResponseStatusCheck(JsonPointer pointer, long equals) {}

    public HttpToolSpec {
        // Insertion order is preserved (configuration order), so the rendered URL is deterministic.
        query = query == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(query));
        headers = headers == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(headers));
        maxResponseBytes = maxResponseBytes <= 0 ? 1 << 20 : maxResponseBytes;
    }
}
