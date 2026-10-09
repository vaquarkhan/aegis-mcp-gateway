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
package io.github.vaquarkhan.aegis.security.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RFC 9728 resource metadata is a security primitive, tested outside any particular HTTP server. */
class ProtectedResourceMetadataTest {

    @Test
    void metadataAndChallengeBindTokensToTheCanonicalMcpResource() {
        ProtectedResourceMetadata metadata = new ProtectedResourceMetadata(
                URI.create("https://aegis.example.com/mcp"), List.of("https://idp.example.com"),
                List.of("mcp.read", "mcp.write"), "https://docs.example.com/aegis");
        assertEquals("/.well-known/oauth-protected-resource/mcp", metadata.metadataPath());
        assertEquals("https://aegis.example.com/.well-known/oauth-protected-resource/mcp", metadata.metadataUrl());
        assertTrue(metadata.challenge().contains("resource_metadata"));
        assertTrue(metadata.json().contains("authorization_servers"));
        assertTrue(metadata.json().contains("mcp.write"));

        ProtectedResourceMetadata root = new ProtectedResourceMetadata(
                URI.create("https://aegis.example.com/"), List.of("https://idp.example.com"), List.of(), null);
        assertEquals("/.well-known/oauth-protected-resource", root.metadataPath());
        assertFalse(root.json().contains("scopes_supported"));
    }

    @Test
    void invalidResourceAndAuthorizationServerValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(
                URI.create("http://aegis.example.com/mcp"), List.of("https://idp.example.com"), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(
                URI.create("https://aegis.example.com/mcp?x=1"), List.of("https://idp.example.com"), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(
                URI.create("https://aegis.example.com/mcp"), List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(
                URI.create("https://aegis.example.com/mcp"), List.of("not-absolute"), List.of(), null));
        assertEquals("/.well-known/oauth-protected-resource/mcp", new ProtectedResourceMetadata(
                URI.create("http://127.0.0.1:8080/mcp"), List.of("https://idp.example.com"), List.of(), null).metadataPath());
    }

    /**
     * MCP clients fetch or open the URLs this document publishes. A dangerous scheme or a remote
     * plain-http URL must never be published (MCP security best practices: OAuth authorization URL
     * validation and SSRF).
     */
    @Test
    void publishedUrlsAreHttpsOrLoopbackOnly() {
        URI resource = URI.create("https://aegis.example.com/mcp");
        for (String bad : List.of("javascript:alert(1)", "data:text/html,x", "file:///etc/passwd",
                "http://idp.example.com", "vbscript:msgbox")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProtectedResourceMetadata(resource, List.of(bad), List.of(), null), bad);
            assertThrows(IllegalArgumentException.class,
                    () -> new ProtectedResourceMetadata(resource, List.of("https://idp.example.com"), List.of(), bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(resource,
                List.of("https://idp.example.com?x=1"), List.of(), null), "an issuer identifier has no query (RFC 8414)");
        assertEquals(List.of("http://localhost:9000"),
                new ProtectedResourceMetadata(resource, List.of("http://localhost:9000"), List.of(), "http://127.0.0.1/doc")
                        .authorizationServers(), "loopback http stays available for local development");
    }
}
