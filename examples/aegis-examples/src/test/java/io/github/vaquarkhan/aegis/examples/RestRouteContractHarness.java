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
package io.github.vaquarkhan.aegis.examples;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Test-only route contract harness for a documented REST API: it answers reviewed method and path
 * shapes with representative bodies and records exactly what the gateway sent. It validates request
 * rendering and governance; it is not a live Flink, Spark, Iceberg or Kafka Connect cluster and makes
 * no target-version compatibility claim (see docs/VALIDATION.md).
 */
final class RestRouteContractHarness implements AutoCloseable {

    record Route(String method, Pattern path, int status, String body) {}

    private final HttpServer server;
    private final List<Route> routes = new ArrayList<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    RestRouteContractHarness() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String raw = ex.getRequestURI().getRawPath();
            String q = ex.getRequestURI().getRawQuery();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            requests.add(ex.getRequestMethod() + " " + raw + (q == null ? "" : "?" + q)
                    + (body.isEmpty() ? "" : " body=" + body) + (auth == null ? "" : " auth=" + auth));
            Route match = null;
            for (Route r : routes) {
                if (r.method().equals(ex.getRequestMethod()) && r.path().matcher(raw).matches()) {
                    match = r;
                    break;
                }
            }
            int status = match == null ? 404 : match.status();
            byte[] b = (match == null ? "{\"error\":\"no such route\"}" : match.body()).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            if (b.length == 0) {
                ex.sendResponseHeaders(status, -1);
            } else {
                ex.sendResponseHeaders(status, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            }
            ex.close();
        });
        server.start();
    }

    RestRouteContractHarness route(String method, String pathRegex, int status, String body) {
        routes.add(new Route(method, Pattern.compile(pathRegex), status, body));
        return this;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String last() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Flink REST API shapes (JobManager). */
    static RestRouteContractHarness flink() throws Exception {
        String job = ExampleConfigsTest.FLINK_JOB;
        return new RestRouteContractHarness()
                .route("GET", "/jobs/overview", 200, "{\"jobs\":[{\"jid\":\"" + job
                        + "\",\"name\":\"orders-etl\",\"state\":\"RUNNING\"}]}")
                // Sub-resources first: /jobs/<id> would otherwise not match them anyway, but keeping the
                // specific routes above the general one documents the intent.
                .route("GET", "/jobs/[0-9a-f]{32}/exceptions", 200, "{\"all-exceptions\":[],\"truncated\":false}")
                .route("GET", "/jobs/[0-9a-f]{32}/metrics", 200, "[{\"id\":\"numRecordsIn\"}]")
                .route("GET", "/jobs/[0-9a-f]{32}/checkpoints", 200, "{\"counts\":{\"completed\":7,\"failed\":0}}")
                .route("GET", "/jobs/[0-9a-f]{32}/config", 200, "{\"jid\":\"" + job + "\",\"execution-config\":{}}")
                .route("GET", "/jobs/[0-9a-f]{32}", 200, "{\"jid\":\"" + job + "\",\"state\":\"RUNNING\"}")
                .route("GET", "/overview", 200, "{\"flink-version\":\"2.0.0\",\"slots-total\":8,\"jobs-running\":1}")
                .route("GET", "/taskmanagers", 200, "{\"taskmanagers\":[{\"id\":\"tm-1\",\"slotsNumber\":4}]}")
                .route("GET", "/jobmanager/config", 200, "[{\"key\":\"parallelism.default\",\"value\":\"2\"}]")
                // Jar ids have the shape Flink really assigns on upload: "<uuid>_<file name>".
                .route("GET", "/jars", 200, "{\"files\":[{\"id\":\"" + ExampleConfigsTest.FLINK_APPROVED_JAR
                        + "\",\"name\":\"approved-etl.jar\"}]}")
                // The route is /rescaling, not /rescale. A real Flink 1.20 standalone cluster answers it
                // with 503 "Rescaling is temporarily disabled. See FLINK-12312."; this harness answers the
                // documented success shape, because what the test checks is the request Aegis builds.
                .route("PATCH", "/jobs/[0-9a-f]{32}/rescaling", 202, "{\"request-id\":\"rescale-1\"}")
                .route("GET", "/jobs/[0-9a-f]{32}/savepoints/[0-9a-f]{32}", 200,
                        "{\"status\":{\"id\":\"COMPLETED\"},\"operation\":{\"location\":\"s3://savepoints/orders/savepoint-012345-abcdef\"}}")
                .route("PATCH", "/jobs/[0-9a-f]{32}", 202, "")
                .route("POST", "/jobs/[0-9a-f]{32}/savepoints", 202, "{\"request-id\":\"" + ExampleConfigsTest.FLINK_TRIGGER + "\"}")
                .route("POST", "/jobs/[0-9a-f]{32}/stop", 202, "{\"request-id\":\"stop-1\"}")
                .route("POST", "/jars/[^/]+/run", 200, "{\"jobid\":\"" + job + "\"}");
    }

    /** Spark monitoring REST API (/api/v1) and standalone REST submission server shapes. */
    static RestRouteContractHarness spark() throws Exception {
        return new RestRouteContractHarness()
                .route("GET", "/api/v1/applications", 200, "[{\"id\":\"app-20260926-0001\",\"name\":\"daily-etl\"}]")
                .route("GET", "/api/v1/applications/[^/]+/jobs", 200, "[{\"jobId\":0,\"status\":\"SUCCEEDED\"}]")
                .route("GET", "/api/v1/applications/[^/]+/stages", 200, "[{\"stageId\":3,\"status\":\"COMPLETE\"}]")
                .route("GET", "/api/v1/applications/[^/]+/executors", 200, "[{\"id\":\"driver\",\"activeTasks\":0}]")
                .route("GET", "/api/v1/applications/[^/]+/environment", 200,
                        "{\"sparkProperties\":[[\"spark.sql.shuffle.partitions\",\"200\"]]}")
                .route("GET", "/api/v1/applications/[^/]+", 200,
                        "{\"id\":\"app-20260926-0001\",\"name\":\"daily-etl\",\"attempts\":[{\"completed\":true}]}")
                .route("GET", "/v1/submissions/status/[^/]+", 200,
                        "{\"action\":\"SubmissionStatusResponse\",\"driverState\":\"RUNNING\",\"success\":true}")
                .route("POST", "/v1/submissions/create", 200,
                        "{\"action\":\"CreateSubmissionResponse\",\"submissionId\":\"driver-2\",\"success\":true}")
                .route("POST", "/v1/submissions/kill/[^/]+", 200,
                        "{\"action\":\"KillSubmissionResponse\",\"success\":true,\"submissionId\":\"driver-1\"}");
    }

    /** Iceberg REST catalog API shapes. */
    static RestRouteContractHarness iceberg() throws Exception {
        return new RestRouteContractHarness()
                .route("POST", "/v1/namespaces/[^/]+/tables/[^/]+", 200,
                        "{\"metadata-location\":\"s3://wh/sales/orders/metadata/v4.json\",\"metadata\":{\"format-version\":2}}")
                .route("GET", "/v1/namespaces", 200, "{\"namespaces\":[[\"sales\"],[\"finance\"]]}")
                .route("GET", "/v1/namespaces/[^/]+/tables", 200,
                        "{\"identifiers\":[{\"namespace\":[\"sales\"],\"name\":\"orders\"}]}")
                .route("GET", "/v1/namespaces/[^/]+/tables/[^/]+", 200,
                        "{\"metadata-location\":\"s3://wh/sales/orders/metadata/v3.json\",\"metadata\":{\"format-version\":2,"
                                + "\"schemas\":[{\"schema-id\":0,\"type\":\"struct\",\"fields\":[{\"id\":1,\"name\":\"id\",\"required\":true,\"type\":\"long\"}]}]}}")
                .route("DELETE", "/v1/namespaces/[^/]+/tables/[^/]+", 204, "")
                .route("POST", "/v1/namespaces", 200, "{\"namespace\":[\"new_ns\"],\"properties\":{}}");
    }

    /** Kafka Connect REST API shapes. */
    static RestRouteContractHarness kafkaConnect() throws Exception {
        return new RestRouteContractHarness()
                .route("GET", "/connectors", 200, "[\"jdbc-sink\",\"s3-sink\"]")
                .route("GET", "/connectors/[^/]+/status", 200,
                        "{\"name\":\"jdbc-sink\",\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[{\"id\":0,\"state\":\"RUNNING\"}]}")
                .route("PUT", "/connectors/[^/]+/pause", 202, "")
                .route("DELETE", "/connectors/[^/]+", 204, "");
    }
}
