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
package io.github.vaquarkhan.aegis.examples.kafka;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;

/**
 * {@code type: kafka-admin} source. Settings:
 * <pre>
 * settings:
 *   bootstrapServers: kafka-1:9093,kafka-2:9093
 *   clientPropertiesFile: kafka-admin.properties   # optional: SASL/SSL settings, kept out of the Aegis file
 *   requestTimeoutMs: 15000
 * </pre>
 */
public final class KafkaAdminToolSourceFactory implements ToolSourceFactory {

    @Override
    public String type() {
        return "kafka-admin";
    }

    @Override
    public ToolSource create(String id, ConfigNode settings) throws IOException {
        String bootstrap = settings.requiredString("bootstrapServers");
        String propsFile = settings.string("clientPropertiesFile", null);
        long timeout = settings.integer("requestTimeoutMs", 15_000);
        Properties p = new Properties();
        if (propsFile != null) {
            try (InputStream in = Files.newInputStream(Path.of(propsFile))) {
                p.load(in);
            }
        }
        for (Map.Entry<Object, Object> e : p.entrySet()) {
            if (String.valueOf(e.getKey()).equals(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG)) {
                throw new ConfigException(settings.path() + ".clientPropertiesFile must not set bootstrap.servers (use bootstrapServers)");
            }
        }
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeout);
        p.putIfAbsent(AdminClientConfig.CLIENT_ID_CONFIG, "aegis-" + id);
        return new KafkaAdminToolSource(id, () -> Admin.create(p));
    }
}
