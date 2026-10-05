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
package io.github.vaquarkhan.aegis.state.postgres;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.config.spi.StateStoreFactory;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * {@code state.type: postgres}: shared governance state backed by PostgreSQL.
 *
 * <p>Settings deliberately name environment variables rather than carry credential values:
 * {@code jdbcUrl}, {@code usernameEnv}, {@code passwordEnv}, {@code namespace}, and optional
 * {@code connectTimeoutSeconds}. The namespace makes cross-process sharing an explicit operator choice;
 * two gateways share approvals/rate windows only when they use the same database and namespace.
 */
public final class PostgresStateStoreFactory implements StateStoreFactory {

    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    @Override
    public String type() {
        return "postgres";
    }

    @Override
    public StateStore create(ConfigNode settings, BuildContext context) throws Exception {
        String jdbcUrl = settings.requiredString("jdbcUrl");
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new ConfigException(settings.path() + ".jdbcUrl must be a PostgreSQL JDBC URL");
        }
        String usernameEnv = settings.requiredString("usernameEnv");
        String passwordEnv = settings.requiredString("passwordEnv");
        String namespace = settings.requiredString("namespace");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new ConfigException(settings.path() + ".namespace must be 1-128 letters, digits, dot, underscore or hyphen");
        }
        int timeout = (int) settings.integer("connectTimeoutSeconds", 10);
        if (timeout <= 0 || timeout > 120) {
            throw new ConfigException(settings.path() + ".connectTimeoutSeconds must be between 1 and 120");
        }
        String username = requiredEnv(context.env(), usernameEnv, settings.path() + ".usernameEnv");
        String password = requiredEnv(context.env(), passwordEnv, settings.path() + ".passwordEnv");
        settings.finish();
        return new PostgresStateStore(jdbcUrl, username, password, namespace, timeout, context.clock());
    }

    private static String requiredEnv(Function<String, String> env, String name, String path) {
        String value = env.apply(name);
        if (value == null || value.isBlank()) {
            throw new ConfigException(path + " names " + name + " which is not set");
        }
        return value;
    }
}
