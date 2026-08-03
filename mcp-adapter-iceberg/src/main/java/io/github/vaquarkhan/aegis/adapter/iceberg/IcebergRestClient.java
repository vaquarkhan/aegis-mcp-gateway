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
package io.github.vaquarkhan.aegis.adapter.iceberg;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iceberg REST catalog client (JDK HTTP). Never returns vended storage credentials.
 *
 * @author Viquar Khan
 */
public final class IcebergRestClient {

    private static final Logger LOG = LoggerFactory.getLogger(IcebergRestClient.class);

    private final String baseUrl;
    private final HttpClient http;

    public IcebergRestClient(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        this.baseUrl = u;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String get(String path) {
        return send("GET", path, null);
    }

    public String post(String path, String jsonBody) {
        return send("POST", path, jsonBody == null ? "{}" : jsonBody);
    }

    public String delete(String path) {
        return send("DELETE", path, null);
    }

    private String send(String method, String path, String jsonBody) {
        String p = path == null || path.isEmpty() ? "/" : (path.startsWith("/") ? path : "/" + path);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + p))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            switch (method) {
                case "GET" -> builder.GET();
                case "DELETE" -> builder.DELETE();
                case "POST" -> {
                    builder.header("Content-Type", "application/json");
                    builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
                }
                default -> throw new IllegalStateException("unsupported method " + method);
            }
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            return redactCredentials(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("iceberg backend request interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.warn("iceberg http {} failed path={} msg={}", method, p, reason);
            throw new IllegalStateException("iceberg backend error: " + reason, e);
        }
    }

    static String redactCredentials(String body) {
        if (body == null) {
            return "";
        }
        return body
                .replaceAll("(?i)\"credential\"\\s*:\\s*\"[^\"]*\"", "\"credential\":\"\u003credacted\u003e\"")
                .replaceAll("(?i)\"token\"\\s*:\\s*\"[^\"]*\"", "\"token\":\"\u003credacted\u003e\"")
                .replaceAll("(?i)\"access-key-id\"\\s*:\\s*\"[^\"]*\"", "\"access-key-id\":\"\u003credacted\u003e\"")
                .replaceAll("(?i)\"secret-access-key\"\\s*:\\s*\"[^\"]*\"", "\"secret-access-key\":\"\u003credacted\u003e\"");
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300);
    }
}
