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

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.util.function.Function;

/**
 * {@code type: spark-connect} source. Settings:
 * <pre>
 * settings:
 *   host: spark-connect.internal       # Spark Connect server (start-connect-server.sh)
 *   port: 15002
 *   useSsl: true                       # required when a token is used
 *   tokenEnv: SPARK_CONNECT_TOKEN      # optional bearer token, read from the environment
 *   userId: aegis-gateway              # the gateway's service identity towards Spark
 *   maxRows: 100                       # hard cap for preview_table and spark_sql results
 *   threads: 8                         # concurrent Spark Connect operations
 * </pre>
 * The caller's own credentials never reach Spark: Spark sees the gateway's service identity, and
 * who asked is in the Aegis audit record (correlated by trace id).
 */
public final class SparkConnectToolSourceFactory implements ToolSourceFactory {

    @Override
    public String type() {
        return "spark-connect";
    }

    @Override
    public ToolSource create(String id, ConfigNode s) {
        // Compatibility path for a direct factory caller. GatewayFactory uses the BuildContext overload.
        return create(id, s, System::getenv);
    }

    @Override
    public ToolSource create(String id, ConfigNode s, BuildContext context) {
        return create(id, s, context.env());
    }

    private ToolSource create(String id, ConfigNode s, Function<String, String> environment) {
        String token = null;
        String tokenEnv = s.string("tokenEnv", null);
        if (tokenEnv != null) {
            token = environment.apply(tokenEnv);
            if (token == null || token.isEmpty()) {
                throw new ConfigException(s.path() + ".tokenEnv names " + tokenEnv + " which is not set");
            }
        }
        try {
            return new SparkConnectToolSource(id, new SparkConnectSettings(s.requiredString("host"), (int) s.integer("port", 15002),
                    s.bool("useSsl", false), token, s.string("userId", null), (int) s.integer("maxRows", 100),
                    (int) s.integer("threads", 8)));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(s.path() + ": " + e.getMessage());
        }
    }
}
