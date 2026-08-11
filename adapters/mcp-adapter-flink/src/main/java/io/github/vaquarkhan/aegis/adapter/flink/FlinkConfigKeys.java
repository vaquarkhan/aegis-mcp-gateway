package io.github.vaquarkhan.aegis.adapter.flink;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Adapter-scoped configuration keys for Flink. Gateway-wide settings live under
 * {@code MCP_GW_*} in {@link GatewayConfig}; endpoints and credentials for a single engine stay
 * adapter-scoped and are carried in the gateway adapter properties map.
 *
 * <p>Every key is readable either by its environment name (for example {@code FLINK_REST_URL})
 * or by its dotted YAML name (for example {@code flink.rest.url}).
 *
 * @author Viquar Khan
 */
public final class FlinkConfigKeys {

    public static final String REST_URL = "FLINK_REST_URL";
    public static final String GATEWAY_URL = "MCP_FLINK_GATEWAY_URL";
    public static final String REST_AUTH_HEADER = "MCP_FLINK_REST_AUTH_HEADER";
    public static final String GATEWAY_AUTH_HEADER = "MCP_FLINK_GATEWAY_AUTH_HEADER";
    public static final String JAR_UPLOAD_ALLOW_DIRS = "MCP_FLINK_JAR_UPLOAD_ALLOW_DIRS";

    public static final String REST_URL_YAML = "flink.rest.url";
    public static final String GATEWAY_URL_YAML = "flink.gateway.url";
    public static final String REST_AUTH_HEADER_YAML = "flink.rest.auth.header";
    public static final String GATEWAY_AUTH_HEADER_YAML = "flink.gateway.auth.header";
    public static final String JAR_UPLOAD_ALLOW_DIRS_YAML = "flink.jar.upload.allow.dirs";

    public static final String DEFAULT_REST_URL = "http://localhost:8081";
    public static final String DEFAULT_GATEWAY_URL = "http://localhost:8083";

    private FlinkConfigKeys() {}

    /**
     * Collects the Flink environment variables that are present into a map suitable for
     * {@code GatewayConfig.builder().adapterProperties(...)}. Absent or blank values are omitted
     * so that defaults stay in one place.
     */
    public static Map<String, String> fromEnv() {
        Map<String, String> props = new LinkedHashMap<>();
        putIfPresent(props, REST_URL, System.getenv(REST_URL));
        putIfPresent(props, GATEWAY_URL, System.getenv(GATEWAY_URL));
        putIfPresent(props, REST_AUTH_HEADER, System.getenv(REST_AUTH_HEADER));
        putIfPresent(props, GATEWAY_AUTH_HEADER, System.getenv(GATEWAY_AUTH_HEADER));
        putIfPresent(props, JAR_UPLOAD_ALLOW_DIRS, System.getenv(JAR_UPLOAD_ALLOW_DIRS));
        return props;
    }

    public static String restUrl(GatewayConfig cfg) {
        return property(cfg, REST_URL_YAML, REST_URL, DEFAULT_REST_URL);
    }

    public static String gatewayUrl(GatewayConfig cfg) {
        return property(cfg, GATEWAY_URL_YAML, GATEWAY_URL, DEFAULT_GATEWAY_URL);
    }

    public static String restAuthHeader(GatewayConfig cfg) {
        return blankToNull(property(cfg, REST_AUTH_HEADER_YAML, REST_AUTH_HEADER, null));
    }

    public static String gatewayAuthHeader(GatewayConfig cfg) {
        return blankToNull(property(cfg, GATEWAY_AUTH_HEADER_YAML, GATEWAY_AUTH_HEADER, null));
    }

    /**
     * Allow-listed directories for {@code upload_jar}. An empty set rejects all uploads, which is
     * the secure default when the operator has not configured any directory.
     */
    public static Set<String> jarUploadAllowDirs(GatewayConfig cfg) {
        return parseCsv(property(cfg, JAR_UPLOAD_ALLOW_DIRS_YAML, JAR_UPLOAD_ALLOW_DIRS, null));
    }

    /** Hosts this adapter is allowed to reach, for the core egress guard. */
    public static Set<String> egressHosts(GatewayConfig cfg) {
        Set<String> hosts = new LinkedHashSet<>();
        addHost(hosts, restUrl(cfg));
        addHost(hosts, gatewayUrl(cfg));
        return hosts;
    }

    private static String property(GatewayConfig cfg, String yamlKey, String envKey, String def) {
        if (cfg == null) {
            return def;
        }
        String yamlValue = cfg.adapterProperty(yamlKey, null);
        if (yamlValue != null && !yamlValue.isBlank()) {
            return yamlValue;
        }
        return cfg.adapterProperty(envKey, def);
    }

    private static void addHost(Set<String> hosts, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            String host = URI.create(url).getHost();
            if (host != null && !host.isBlank()) {
                hosts.add(host);
            }
        } catch (IllegalArgumentException e) {
            // an unparsable endpoint contributes no allowed host, so egress stays closed
        }
    }

    private static void putIfPresent(Map<String, String> props, String key, String value) {
        if (value != null && !value.isBlank()) {
            props.put(key, value);
        }
    }

    private static Set<String> parseCsv(String csv) {
        Set<String> set = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return set;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }
}
