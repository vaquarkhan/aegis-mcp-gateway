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
package io.github.vaquarkhan.aegis.source.jdbc;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.function.Function;

/**
 * {@code type: jdbc} source. Settings:
 * <pre>
 * settings:
 *   url: jdbc:hive2://hiveserver2:10000/default   # required
 *   driverClass: org.apache.hive.jdbc.HiveDriver  # optional; JDBC 4 drivers are auto-discovered
 *   userEnv: HIVE_USER                            # optional, read from the environment
 *   passwordEnv: HIVE_PASSWORD                    # optional, read from the environment
 *   maxRows: 200                                  # hard cap on rows returned to the model
 *   maxCellChars: 4096                            # hard cap on one rendered value
 *   loginTimeoutSeconds: 15
 *   allowWrites: false                            # true also offers jdbc_execute_statement
 * </pre>
 *
 * <p>The password is read from the environment and never appears in configuration, audit records or
 * logs; {@link JdbcSettings#safeUrl()} is what gets reported.
 *
 * <p><b>Give this source a read-only database credential.</b> The engine enforces what a SQL keyword
 * check can only indicate, and it is the one control here that cannot be talked around by a
 * user-defined function. {@code allowWrites: true} plus a read-only credential is a sensible
 * combination: the tool exists, is classified {@code DESTRUCTIVE}, needs an approval, and then the
 * database refuses it anyway.
 */
public final class JdbcToolSourceFactory implements ToolSourceFactory {

    @Override
    public String type() {
        return "jdbc";
    }

    @Override
    public ToolSource create(String id, ConfigNode s) {
        // Direct callers without a BuildContext read the process environment. GatewayFactory always
        // calls the context-aware overload below, so normal builds use BuildContext.env.
        return create(id, s, System::getenv);
    }

    @Override
    public ToolSource create(String id, ConfigNode s, BuildContext context) {
        return create(id, s, context.env());
    }

    private ToolSource create(String id, ConfigNode s, Function<String, String> environment) {
        String url = s.requiredString("url");
        String driverClass = s.string("driverClass", null);
        String user = resolve(s, "userEnv", environment);
        String password = resolve(s, "passwordEnv", environment);
        JdbcSettings settings;
        try {
            settings = new JdbcSettings(url, driverClass, user, password,
                    (int) s.integer("maxRows", 200), (int) s.integer("maxCellChars", 4096),
                    (int) s.integer("loginTimeoutSeconds", 15), s.bool("allowWrites", false));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(s.path() + ": " + e.getMessage());
        }
        if (driverClass != null) {
            try {
                Class.forName(driverClass, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                // Fail at startup with the actionable message rather than on the first tool call.
                throw new ConfigException(s.path() + ".driverClass '" + driverClass
                        + "' is not on the classpath. Aegis ships no JDBC drivers; add your engine's own driver.");
            }
        }
        DriverManager.setLoginTimeout(settings.loginTimeoutSeconds());
        return new JdbcToolSource(id, settings, () -> {
            Properties p = new Properties();
            if (settings.user() != null) {
                p.setProperty("user", settings.user());
            }
            if (settings.password() != null) {
                p.setProperty("password", settings.password());
            }
            return DriverManager.getConnection(settings.url(), p);
        });
    }

    private static String resolve(ConfigNode s, String key, Function<String, String> environment) {
        String name = s.string(key, null);
        if (name == null) {
            return null;
        }
        String value = environment.apply(name);
        if (value == null || value.isEmpty()) {
            throw new ConfigException(s.path() + "." + key + " names " + name + " which is not set");
        }
        return value;
    }
}
