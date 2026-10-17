package io.github.vaquarkhan.aegis.core;


import io.github.vaquarkhan.aegis.core.spi.ToolClass;

import java.util.List;
import java.util.Map;

public final class DeclarativeAdapterSpec {

    private final String version;
    private final String engineId;
    private final String taxonomyClass;
    private final String description;
    private final ConfigSpec configuration;
    private final List<ToolSpec> tools;
    private final List<ResourceSpec> resources;

    public DeclarativeAdapterSpec(
            String version,
            String engineId,
            String taxonomyClass,
            String description,
            ConfigSpec configuration,
            List<ToolSpec> tools,
            List<ResourceSpec> resources) {
        this.version = version;
        this.engineId = engineId;
        this.taxonomyClass = taxonomyClass;
        this.description = description;
        this.configuration = configuration;
        this.tools = tools == null ? List.of() : tools;
        this.resources = resources == null ? List.of() : resources;
    }

    public String version() { return version; }
    public String engineId() { return engineId; }
    public String taxonomyClass() { return taxonomyClass; }
    public String description() { return description; }
    public ConfigSpec configuration() { return configuration; }
    public List<ToolSpec> tools() { return tools; }
    public List<ResourceSpec> resources() { return resources; }

    public static final class ConfigSpec {
        private final BaseUrlSpec baseUrl;

        public ConfigSpec(BaseUrlSpec baseUrl) {
            this.baseUrl = baseUrl;
        }

        public BaseUrlSpec baseUrl() { return baseUrl; }
    }

    public static final class BaseUrlSpec {
        private final List<String> propertyCascade;
        private final String defaultValue;

        public BaseUrlSpec(List<String> propertyCascade, String defaultValue) {
            this.propertyCascade = propertyCascade == null ? List.of() : propertyCascade;
            this.defaultValue = defaultValue == null ? "" : defaultValue;
        }

        public List<String> propertyCascade() { return propertyCascade; }
        public String defaultValue() { return defaultValue; }
    }

    public static final class ToolSpec {
        private final String name;
        private final ToolClass securityClass;
        private final String description;
        private final Map<String, Object> inputSchema;
        private final EndpointSpec endpoint;

        public ToolSpec(
                String name,
                ToolClass securityClass,
                String description,
                Map<String, Object> inputSchema,
                EndpointSpec endpoint) {
            this.name = name;
            this.securityClass = securityClass;
            this.description = description;
            this.inputSchema = inputSchema == null ? Map.of() : inputSchema;
            this.endpoint = endpoint;
        }

        public String name() { return name; }
        public ToolClass securityClass() { return securityClass; }
        public String description() { return description; }
        public Map<String, Object> inputSchema() { return inputSchema; }
        public EndpointSpec endpoint() { return endpoint; }
    }

    public static final class ResourceSpec {
        private final String uri;
        private final String name;
        private final String mimeType;
        private final boolean directRead;
        private final EndpointSpec endpoint;

        public ResourceSpec(
                String uri,
                String name,
                String mimeType,
                boolean directRead,
                EndpointSpec endpoint) {
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
            this.directRead = directRead;
            this.endpoint = endpoint;
        }

        public String uri() { return uri; }
        public String name() { return name; }
        public String mimeType() { return mimeType; }
        public boolean directRead() { return directRead; }
        public EndpointSpec endpoint() { return endpoint; }
    }

    public static final class EndpointSpec {
        private final String method;
        private final String path;
        private final String body;

        public EndpointSpec(String method, String path, String body) {
            this.method = (method == null || method.isBlank()) ? "GET" : method;
            this.path = (path == null || path.isBlank()) ? "/" : path;
            this.body = body;
        }

        public String method() { return method; }
        public String path() { return path; }
        public String body() { return body; }
    }
}