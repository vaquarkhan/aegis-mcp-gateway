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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        List<URI> endpoints;
        try {
            endpoints = bootstrapEndpoints(bootstrap);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(settings.path() + ".bootstrapServers " + e.getMessage());
        }
        return new KafkaAdminToolSource(id, endpoints, () -> Admin.create(p));
    }

    /**
     * Parses a Kafka bootstrap list into {@code tcp://host:port} endpoints for the egress control.
     * Accepts {@code host:port}, {@code [ipv6]:port} and an optional listener prefix such as
     * {@code SASL_SSL://host:port}, the forms the Kafka client accepts.
     */
    public static List<URI> bootstrapEndpoints(String bootstrap) {
        List<URI> out = new ArrayList<>();
        for (String raw : bootstrap.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int prefix = entry.indexOf("://");
            if (prefix >= 0) {
                entry = entry.substring(prefix + 3);
            }
            int colon = entry.lastIndexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                throw new IllegalArgumentException("entry must be host:port: " + raw.trim());
            }
            String host = entry.substring(0, colon);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            if (host.isEmpty() || host.contains("@") || host.contains("/")) {
                throw new IllegalArgumentException("entry must be host:port: " + raw.trim());
            }
            int port;
            try {
                port = Integer.parseInt(entry.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("entry has a bad port: " + raw.trim());
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("entry has a bad port: " + raw.trim());
            }
            try {
                URI u = new URI("tcp", null, host, port, null, null, null);
                if (u.getHost() == null) {
                    throw new IllegalArgumentException("entry has a bad host: " + raw.trim());
                }
                out.add(u);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("entry has a bad host: " + raw.trim());
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("must name at least one host:port");
        }
        return List.copyOf(out);
    }
}
