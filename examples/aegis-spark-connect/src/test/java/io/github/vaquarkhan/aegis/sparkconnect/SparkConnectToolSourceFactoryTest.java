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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies that Spark Connect's gateway credential is resolved by BuildContext, not process-global state. */
class SparkConnectToolSourceFactoryTest {

    @Test
    void contextSuppliesTheNamedTokenWithoutOpeningASparkConnection() throws Exception {
        SparkConnectToolSourceFactory factory = new SparkConnectToolSourceFactory();
        ConfigNode settings = new ConfigNode("source.settings", Map.of(
                "host", "spark.example", "port", 15002L, "useSsl", true, "tokenEnv", "SPARK_TOKEN"));
        SparkConnectToolSource source = (SparkConnectToolSource) factory.create("spark", settings,
                BuildContext.defaults(Path.of(".")).withEnv(Map.of("SPARK_TOKEN", "context-token")::get));
        assertEquals("context-token", source.settings().token());

        ConfigNode missing = new ConfigNode("source.settings", Map.of(
                "host", "spark.example", "port", 15002L, "useSsl", true, "tokenEnv", "MISSING"));
        ConfigException e = assertThrows(ConfigException.class, () -> factory.create("spark", missing,
                BuildContext.defaults(Path.of(".")).withEnv(name -> null)));
        assertTrue(e.getMessage().contains("MISSING"), e.getMessage());
    }
}
