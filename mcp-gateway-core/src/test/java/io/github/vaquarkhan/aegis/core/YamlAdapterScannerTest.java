package io.github.vaquarkhan.aegis.core;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YamlAdapterScannerTest {

    private YamlAdapterScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new YamlAdapterScanner();
    }

    @Test
    @DisplayName("Should scan classpath and locate mock-storage.yaml")
    void testScanClasspathFindsAdapter() {
        List<DeclarativeAdapterSpec> specs = scanner.scanClasspathSpecs();

        assertNotNull(specs, "Scanned spec list should not be null");
        assertFalse(specs.isEmpty(), "Scanner should find at least one adapter spec on test classpath");

        boolean foundMock = specs.stream()
                .anyMatch(spec -> "mock-storage".equals(spec.engineId()));

        assertTrue(foundMock, "Scanner should discover 'mock-storage' from test resources");
    }

    @Test
    @DisplayName("Should correctly map all fields from mock-storage.yaml into DeclarativeAdapterSpec")
    void testAdapterSpecFieldMapping() {
        List<DeclarativeAdapterSpec> specs = scanner.scanClasspathSpecs();

        DeclarativeAdapterSpec mockSpec = specs.stream()
                .filter(spec -> "mock-storage".equals(spec.engineId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Mock storage spec not found"));


        assertEquals("1.0", mockSpec.version());
        assertEquals("mock-storage", mockSpec.engineId());
        assertEquals("storage", mockSpec.taxonomyClass());
        assertEquals("Mock storage engine adapter for unit testing scanner", mockSpec.description());


        assertNotNull(mockSpec.configuration());
        assertNotNull(mockSpec.configuration().baseUrl());
        assertEquals("http://localhost:8080", mockSpec.configuration().baseUrl().defaultValue());
        assertEquals(List.of("mock.url", "MOCK_STORAGE_URL"), mockSpec.configuration().baseUrl().propertyCascade());


        assertEquals(1, mockSpec.tools().size());
        DeclarativeAdapterSpec.ToolSpec tool = mockSpec.tools().get(0);
        assertEquals("list_files", tool.name());
        assertEquals("Lists files in directory", tool.description());
        assertEquals("GET", tool.endpoint().method());
        assertEquals("/list${input.path}", tool.endpoint().path());


        assertEquals(1, mockSpec.resources().size());
        DeclarativeAdapterSpec.ResourceSpec res = mockSpec.resources().get(0);
        assertEquals("mock://status", res.uri());
        assertEquals("Mock Status", res.name());
        assertEquals("application/json", res.mimeType());
        assertTrue(res.directRead());
        assertEquals("GET", res.endpoint().method());
        assertEquals("/status", res.endpoint().path());
    }
}