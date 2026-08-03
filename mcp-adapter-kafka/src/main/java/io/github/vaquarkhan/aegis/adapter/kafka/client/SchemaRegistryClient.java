/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.aegis.adapter.kafka.client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confluent compatible Schema Registry client over the JDK HTTP client. No registry client
 * dependency is pulled in, so the adapter stays free of the Confluent artifact.
 *
 * @author Viquar Khan
 */
public final class SchemaRegistryClient implements SchemaRegistryOps {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaRegistryClient.class);

    private final String baseUrl;
    private final HttpClient http;

    public SchemaRegistryClient(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.isEmpty()) {
            throw new IllegalArgumentException("schema registry url required");
        }
        this.baseUrl = u;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public String latestVersion(String subject) {
        String path = "/subjects/" + URLEncoder.encode(subject, StandardCharsets.UTF_8) + "/versions/latest";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.schemaregistry.v1+json, application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            return resp.body() == null ? "" : resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("schema registry request interrupted", e);
        } catch (Exception e) {
            LOG.warn("schema registry lookup failed subject={} msg={}", subject, e.getMessage());
            throw new IllegalStateException("schema registry error: " + e.getMessage(), e);
        }
    }
}
