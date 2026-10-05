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
package io.github.vaquarkhan.aegis.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The canonical adopters/configurations/spark/spark.yaml read tools against a REAL Apache Spark 4.0 driver: a local
 * SparkContext whose UI serves the monitoring REST API (/api/v1). The standalone-master kill
 * endpoint is NOT exercised here (it needs a standalone master with spark.master.rest.enabled).
 */
class RealSparkTest {

    @TempDir
    Path dir;

    @Test
    void shippedSparkConfigReadsARealSparkDriver() throws Exception {
        SparkConf conf = new SparkConf().setMaster("local[1]").setAppName("aegis-validation-app")
                .set("spark.ui.enabled", "true").set("spark.ui.port", "0").set("spark.driver.host", "127.0.0.1")
                .set("spark.driver.bindAddress", "127.0.0.1").set("spark.ui.showConsoleProgress", "false");
        try (JavaSparkContext sc = new JavaSparkContext(conf)) {
            long n = sc.parallelize(List.of(1, 2, 3, 4, 5)).count();
            assertEquals(5, n);
            URI ui = URI.create(sc.sc().uiWebUrl().get());
            String appId = sc.sc().applicationId();

            String yaml = Files.readString(Path.of("..", "..", "adopters", "configurations", "spark", "spark.yaml"))
                    .replace("http://spark-history:18080", "http://127.0.0.1:" + ui.getPort())
                    .replace("http://spark-master:6066", "http://127.0.0.1:1")
                    .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]");
            Path cfg = dir.resolve("spark.yaml");
            Files.writeString(cfg, yaml);
            Map<String, String> env = Map.of("AEGIS_SIGNING_KEY", "real-spark-validation-key-0123456789abcdef");
            try (Gateway g = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(env::get))) {
                CallerIdentity user = CallerIdentity.of("ana", Set.of("spark-users"), "idp", "jwt");
                CallOutcome apps = g.engine().call(user, new ToolCall("1", "spark_list_applications",
                        Map.of("status", "running"), Map.of(), ""));
                String a = Json.write(apps.toMcpResult());
                assertTrue(apps.allowed() && a.contains(appId) && a.contains("aegis-validation-app"), a);
                CallOutcome jobs = g.engine().call(user, new ToolCall("2", "spark_list_jobs", Map.of("appId", appId), Map.of(), ""));
                String j = Json.write(jobs.toMcpResult());
                assertTrue(jobs.allowed() && j.contains("SUCCEEDED"), j);
                assertEquals(DenyCodes.READONLY_CALLER, g.engine().call(user,
                        new ToolCall("3", "spark_kill_submission", Map.of("submissionId", "driver-1"), Map.of(), "")).decision().code());
            }
        }
    }
}
