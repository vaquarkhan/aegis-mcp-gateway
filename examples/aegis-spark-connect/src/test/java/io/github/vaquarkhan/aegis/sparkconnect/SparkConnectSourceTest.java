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
package io.github.vaquarkhan.aegis.sparkconnect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Everything that can be checked without a Spark server: settings, identifier safety, governance. */
class SparkConnectSourceTest {

    @TempDir
    Path dir;

    static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "spark-connect-test-key-0123456789abcdef");

    @Test
    void settingsNeverLeakOrWeakenTheConnection() {
        SparkConnectSettings s = new SparkConnectSettings("spark.internal", 15002, true, "t0ken-secret", null, 100, 4);
        assertEquals("sc://spark.internal:15002/;user_id=aegis-gateway;use_ssl=true;token=t0ken-secret", s.connectionString());
        assertFalse(s.toString().contains("t0ken-secret"));
        assertEquals("https://spark.internal:15002", s.egressUri().toString());
        assertThrows(IllegalArgumentException.class, () -> new SparkConnectSettings("h", 15002, false, "tok", null, 10, 1),
                "a token is never sent in clear text");
        assertThrows(IllegalArgumentException.class, () -> new SparkConnectSettings("h", 15002, true, "a;use_ssl=false", null, 10, 1),
                "a token cannot rewrite the connection string");
        assertThrows(IllegalArgumentException.class, () -> new SparkConnectSettings("h/;x=y", 15002, false, null, null, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new SparkConnectSettings("h", 15002, false, null, "a;b", 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new SparkConnectSettings("h", 0, false, null, null, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new SparkConnectSettings("h", 1, false, null, null, 0, 1));
    }

    @Test
    void identifiersAreValidatedAndBackQuoted() {
        assertEquals("`global_temp`.`orders`", SparkConnectToolSource.quote("global_temp.orders", SparkConnectToolSource.TABLE, "table"));
        assertEquals("`c`.`db`.`t_1`", SparkConnectToolSource.quote("c.db.t_1", SparkConnectToolSource.TABLE, "table"));
        for (String bad : List.of("orders; DROP TABLE x", "a`b", "a.b.c.d", "1abc", "", "a..b", "a b", "x' OR '1'='1")) {
            assertThrows(SparkConnectToolSource.RejectedArgument.class,
                    () -> SparkConnectToolSource.quote(bad, SparkConnectToolSource.TABLE, "table"), bad);
        }
    }

    private Gateway gateway(String host) throws Exception {
        String yaml = Files.readString(Path.of("..", "..", "adopters", "configurations", "spark-connect", "spark-connect.yaml"))
                .replace("host: spark-connect", "host: " + host);
        Path cfg = dir.resolve("spark-connect.yaml");
        Files.writeString(cfg, yaml);
        return GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
    }

    @Test
    void shippedConfigLoadsAndGovernsWithoutTouchingSpark() throws Exception {
        // The host does not resolve: every call that passes governance is stopped at egress, and
        // everything else is decided before Spark could be contacted.
        try (Gateway g = gateway("spark-connect.invalid")) {
            CallerIdentity ana = CallerIdentity.of("ana", Set.of("spark-users"), "idp", "jwt");
            CallerIdentity oscar = CallerIdentity.of("oscar", Set.of("spark-operators"), "idp", "jwt");
            List<String> anaTools = g.engine().listTools(ana).stream().map(m -> (String) m.get("name")).toList();
            assertEquals(List.of("spark_describe_table", "spark_explain_sql", "spark_list_databases", "spark_list_tables",
                    "spark_preview_table"), anaTools, "DESTRUCTIVE tools are hidden from readers");
            assertEquals(DenyCodes.READONLY_CALLER, g.engine().call(ana, new ToolCall("1", "spark_sql",
                    Map.of("query", "DROP TABLE x"), Map.of(), "")).decision().code());
            assertEquals(DenyCodes.POLICY_DENIED, g.engine().call(oscar, new ToolCall("2", "spark_drop_table",
                    Map.of("table", "prod.orders"), Map.of(), "")).decision().code());
            assertEquals(DenyCodes.EGRESS_DENIED, g.engine().call(ana, new ToolCall("3", "spark_list_databases",
                    Map.of(), Map.of(), "")).decision().code());
        }
    }

    @Test
    void missingTokenEnvironmentFailsStartup() throws Exception {
        String yaml = Files.readString(Path.of("..", "..", "adopters", "configurations", "spark-connect", "spark-connect.yaml"))
                .replace("\r\n", "\n")
                .replace("port: 15002", "port: 15002\n      useSsl: true\n      tokenEnv: NOT_SET_TOKEN");
        Path cfg = dir.resolve("t.yaml");
        Files.writeString(cfg, yaml);
        Exception e = assertThrows(Exception.class, () -> GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get)));
        assertTrue(e instanceof ConfigException && e.getMessage().contains("NOT_SET_TOKEN"), e.toString());
    }
}
