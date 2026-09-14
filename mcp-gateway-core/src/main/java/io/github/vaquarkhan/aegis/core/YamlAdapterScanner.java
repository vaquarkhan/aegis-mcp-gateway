package io.github.vaquarkhan.aegis.core;


import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class YamlAdapterScanner {

    private static final Logger LOG = LoggerFactory.getLogger(YamlAdapterScanner.class);
    private static final String RESOURCE_DIR = "META-INF/aegis/adapters/";
    private static final int MAX_ALIASES = 64;

    /**
     * Scans the classpath for YAML adapter specs and parses them into DeclarativeAdapterSpec objects.
     */
    public List<DeclarativeAdapterSpec> scanClasspathSpecs() {
        List<DeclarativeAdapterSpec> specs = new ArrayList<>();
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = classLoader.getResources(RESOURCE_DIR);

            while (resources.hasMoreElements()) {
                URL resourceUrl = resources.nextElement();
                String protocol = resourceUrl.getProtocol();

                if ("file".equals(protocol)) {
                    scanFileSystem(Paths.get(resourceUrl.toURI()), specs);
                } else if ("jar".equals(protocol)) {
                    scanJarFile(resourceUrl, specs);
                }
            }
        } catch (Exception e) {
            LOG.error("Error scanning YAML adapter specs from classpath", e);
        }
        return specs;
    }

    private void scanFileSystem(Path dirPath, List<DeclarativeAdapterSpec> specs) {
        if (!Files.isDirectory(dirPath)) return;
        try (Stream<Path> paths = Files.walk(dirPath, 1)) {
            paths.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .forEach(file -> {
                        try (InputStream is = Files.newInputStream(file)) {
                            parseAndAdd(is, file.getFileName().toString(), specs);
                        } catch (Exception e) {
                            LOG.error("Failed to read adapter file: {}", file, e);
                        }
                    });
        } catch (Exception e) {
            LOG.error("Failed to read adapter directory: {}", dirPath, e);
        }
    }

    private void scanJarFile(URL jarUrl, List<DeclarativeAdapterSpec> specs) {
        try {
            URLConnection conn = jarUrl.openConnection();
            if (conn instanceof JarURLConnection jarConn) {
                try (JarFile jarFile = jarConn.getJarFile()) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith(RESOURCE_DIR) && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
                            try (InputStream is = jarFile.getInputStream(entry)) {
                                parseAndAdd(is, name, specs);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to scan JAR file: {}", jarUrl, e);
        }
    }

    private void parseAndAdd(InputStream is, String sourceName, List<DeclarativeAdapterSpec> specs) {
        try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            Yaml yaml = newYaml();
            Object loaded = yaml.load(reader);
            if (!(loaded instanceof Map<?, ?> root)) {
                LOG.warn("Skipping {}: root is not a YAML mapping", sourceName);
                return;
            }

            DeclarativeAdapterSpec spec = parseAdapterSpec(asMap(root));
            specs.add(spec);
            LOG.info("Loaded declarative adapter spec [{}] from {}", spec.engineId(), sourceName);
        } catch (Exception e) {
            LOG.error("Failed to parse YAML spec from {}", sourceName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private DeclarativeAdapterSpec parseAdapterSpec(Map<String, Object> root) {
        String version = str(root.get("version"));
        String engineId = str(root.get("engineId"));
        String taxonomyClass = str(root.get("taxonomyClass"));
        String description = str(root.get("description"));

        // Configuration
        DeclarativeAdapterSpec.ConfigSpec configSpec = null;
        if (root.get("configuration") instanceof Map<?, ?> cfgMap) {
            if (cfgMap.get("baseUrl") instanceof Map<?, ?> urlMap) {
                List<String> cascade = (List<String>) urlMap.get("propertyCascade");
                String defaultUrl = str(urlMap.get("default"));
                configSpec = new DeclarativeAdapterSpec.ConfigSpec(
                        new DeclarativeAdapterSpec.BaseUrlSpec(cascade, defaultUrl)
                );
            }
        }

        // Tools
        List<DeclarativeAdapterSpec.ToolSpec> tools = new ArrayList<>();
        if (root.get("tools") instanceof List<?> toolList) {
            for (Object item : toolList) {
                if (item instanceof Map<?, ?> t) {
                    tools.add(new DeclarativeAdapterSpec.ToolSpec(
                            str(t.get("name")),
                            parseToolClass(t.get("securityClass")),
                            str(t.get("description")),
                            t.get("inputSchema") instanceof Map<?, ?> schema ? (Map<String, Object>) schema : Map.of(),
                            parseEndpoint(t.get("endpoint"))
                    ));
                }
            }
        }

        // Resources
        List<DeclarativeAdapterSpec.ResourceSpec> resources = new ArrayList<>();
        if (root.get("resources") instanceof List<?> resList) {
            for (Object item : resList) {
                if (item instanceof Map<?, ?> r) {
                    resources.add(new DeclarativeAdapterSpec.ResourceSpec(
                            str(r.get("uri")),
                            str(r.get("name")),
                            str(r.get("mimeType")),
                            bool(r.get("directRead"), false),
                            parseEndpoint(r.get("endpoint"))
                    ));
                }
            }
        }

        return new DeclarativeAdapterSpec(version, engineId, taxonomyClass, description, configSpec, tools, resources);
    }

    private DeclarativeAdapterSpec.EndpointSpec parseEndpoint(Object raw) {
        if (!(raw instanceof Map<?, ?> e)) {
            return new DeclarativeAdapterSpec.EndpointSpec("GET", "/", null);
        }
        return new DeclarativeAdapterSpec.EndpointSpec(
                str(e.get("method")),
                str(e.get("path")),
                str(e.get("body"))
        );
    }

    private Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        return new Yaml(new SafeConstructor(options));
    }

    private Map<String, Object> asMap(Map<?, ?> map) {
        Map<String, Object> typed = new LinkedHashMap<>();
        map.forEach((k, v) -> typed.put(String.valueOf(k), v));
        return typed;
    }

    private String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private boolean bool(Object v, boolean fallback) {
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private ToolClass parseToolClass(Object v) {
        String s = str(v);
        if (s == null) return null;
        try {
            return ToolClass.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOG.warn("Unknown ToolClass value: {}", s);
            return null;
        }
    }
}