/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.yaml.YamlManifestLoader;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class GatewayConfigValidateTest {

    private static GatewayConfig.Builder stdio() {
        return GatewayConfig.builder().transport("stdio");
    }

    private static GatewayConfig.Builder http() {
        return GatewayConfig.builder().transport("http").httpBearerToken("t0ken");
    }

    @Test
    void defaultsAreValidAndReadOnly() {
        GatewayConfig cfg = stdio().buildValidated();
        assertEquals("stdio", cfg.transport());
        assertEquals(GatewayConfig.AUTH_TOKENFILE, cfg.authMode());
        assertEquals(GatewayConfig.PDP_BUILTIN, cfg.pdp());
        assertFalse(cfg.writesUnlocked());
        assertTrue(cfg.toolAllowed("anything"), "an empty allow list means every tool");
        assertTrue(cfg.adapterEnabled("flink"));
    }

    @Test
    void writeEnabledWithoutApprovalSecretIsFatal() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> stdio().writeEnabled(true).buildValidated());
        assertTrue(e.getMessage().contains("MCP_GW_APPROVAL_SECRET"));
    }

    @Test
    void writeEnabledWithApprovalSecretUnlocksWrites() {
        GatewayConfig cfg = stdio().writeEnabled(true).approvalSecret("secret").buildValidated();
        assertTrue(cfg.writesUnlocked());
    }

    @Test
    void httpWithoutAnyInboundCredentialIsFatal() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GatewayConfig.builder().transport("http").buildValidated());
        assertTrue(e.getMessage().contains("inbound credential"));
    }

    @Test
    void httpAcceptsBearerTokenTokensFileOrOauth() {
        assertTrue(http().buildValidated().httpAuthConfigured());
        assertTrue(GatewayConfig.builder().transport("http")
                .authTokensFile("/etc/aegis/auth-tokens.txt").buildValidated().httpAuthConfigured());
        assertTrue(GatewayConfig.builder().transport("http")
                .authMode("oauth").oauthIssuer("https://issuer.example.com").oauthAudience("aegis")
                .oauthJwksUrl("https://issuer.example.com/.well-known/jwks.json")
                .buildValidated().httpAuthConfigured());
    }

    @Test
    void oauthWithoutAudienceDoesNotCountAsConfigured() {
        assertThrows(IllegalArgumentException.class, () -> GatewayConfig.builder().transport("http")
                .authMode("oauth").oauthIssuer("https://issuer.example.com").buildValidated());
    }

    @Test
    void oauthWithoutAJwksUrlDoesNotCountAsConfigured() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GatewayConfig.builder().transport("http").authMode("oauth")
                        .oauthIssuer("https://issuer.example.com").oauthAudience("aegis")
                        .buildValidated());
        assertTrue(e.getMessage().contains("MCP_GW_OAUTH_JWKS_URL"),
                "issuer and audience alone give the gateway no way to verify a token");
    }

    @Test
    void mcpHeaderEnforcementDefaultsOff() {
        assertFalse(stdio().buildValidated().requireMcpHeaders());
        assertTrue(http().requireMcpHeaders(true).buildValidated().requireMcpHeaders());
    }

    @Test
    void oauthScopeSettingsAreReadable() {
        GatewayConfig cfg = stdio()
                .oauthRequiredScope("mcp.read")
                .oauthWriteScope("flink.write")
                .buildValidated();
        assertEquals("mcp.read", cfg.oauthRequiredScope());
        assertEquals("flink.write", cfg.oauthWriteScope());
    }

    @Test
    void tlsRequiresKeystoreAndPassword() {
        assertThrows(IllegalArgumentException.class,
                () -> http().httpTlsEnabled(true).buildValidated());
        assertThrows(IllegalArgumentException.class,
                () -> http().httpTlsEnabled(true).httpTlsKeystore("/etc/aegis/server.p12").buildValidated());
        GatewayConfig ok = http().httpTlsEnabled(true)
                .httpTlsKeystore("/etc/aegis/server.p12")
                .httpTlsKeystorePassword("unit-test-keystore")
                .buildValidated();
        assertTrue(ok.httpTlsEnabled());
    }

    @Test
    void rejectsUnknownTransportAuthModeAndPdp() {
        assertThrows(IllegalArgumentException.class, () -> GatewayConfig.builder().transport("grpc").buildValidated());
        assertThrows(IllegalArgumentException.class, () -> stdio().authMode("magic").buildValidated());
        assertThrows(IllegalArgumentException.class, () -> stdio().pdp("magic").buildValidated());
    }

    @Test
    void acceptsEveryLldPdpAndMapsExternalOntoOpa() {
        assertEquals(GatewayConfig.PDP_BUILTIN, stdio().pdp("builtin").buildValidated().pdp());
        assertEquals(GatewayConfig.PDP_CEDAR, stdio().pdp("cedar").buildValidated().pdp());
        assertEquals(GatewayConfig.PDP_OPA, stdio().pdp("opa").buildValidated().pdp());
        assertEquals(GatewayConfig.PDP_OPA, stdio().pdp("external").buildValidated().pdp(),
                "the pre-LLD name stays accepted so existing deployments keep starting");
    }

    @Test
    void rejectsMalformedAuthUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> stdio().oauthIssuer("issuer.example.com").buildValidated());
        assertThrows(IllegalArgumentException.class,
                () -> stdio().oauthJwksUrl("ftp://issuer.example.com/jwks").buildValidated());
        assertThrows(IllegalArgumentException.class,
                () -> stdio().cimdMetadataUrl("https:///no-host").buildValidated());
        GatewayConfig ok = stdio()
                .oauthIssuer("https://issuer.example.com")
                .oauthJwksUrl("https://issuer.example.com/.well-known/jwks.json")
                .cimdMetadataUrl("https://client.example.com/metadata.json")
                .buildValidated();
        assertEquals("https://issuer.example.com", ok.oauthIssuer());
    }

    @Test
    void spiffeSocketIsReadableUnderBothNames() {
        GatewayConfig cfg = stdio()
                .spiffeTrustDomain("example.org")
                .spiffeSocket("unix:///run/spire/sockets/agent.sock")
                .buildValidated();
        assertEquals("unix:///run/spire/sockets/agent.sock", cfg.spiffeSocket());
        assertEquals(cfg.spiffeSocket(), cfg.spiffeWorkloadApi());
    }

    @Test
    void rejectsOutOfRangeNumbers() {
        assertThrows(IllegalArgumentException.class, () -> http().httpPort(0).buildValidated());
        assertThrows(IllegalArgumentException.class, () -> http().httpPort(70_000).buildValidated());
        assertThrows(IllegalArgumentException.class, () -> stdio().rps(0).buildValidated());
        assertThrows(IllegalArgumentException.class, () -> stdio().breakerFailures(0).buildValidated());
        assertThrows(IllegalArgumentException.class, () -> stdio().maxBytes(10).buildValidated());
        assertThrows(IllegalArgumentException.class, () -> stdio().maxSqlChars(0).buildValidated());
        assertThrows(IllegalArgumentException.class, () -> stdio().toolTimeoutMillis(10).buildValidated());
        assertThrows(IllegalArgumentException.class, () -> stdio().approvalTtlMillis(10).buildValidated());
    }

    @Test
    void allowListsNarrowToolsAndAdapters() {
        GatewayConfig cfg = stdio()
                .toolsAllowed(Set.of("list_jobs"))
                .adapters(Set.of("flink"))
                .buildValidated();
        assertTrue(cfg.toolAllowed("list_jobs"));
        assertFalse(cfg.toolAllowed("cancel_job"));
        assertTrue(cfg.adapterEnabled("flink"));
        assertFalse(cfg.adapterEnabled("kafka"));
    }

    @Test
    void flagsNonLoopbackPlaintextExposure() {
        assertTrue(http().httpHost("0.0.0.0").buildValidated().insecureExposure());
        assertFalse(http().httpHost("127.0.0.1").buildValidated().insecureExposure());
        assertFalse(stdio().buildValidated().insecureExposure());
    }

    @Test
    void yamlSettingsPopulateTheConfig() {
        Map<String, Object> flat = YamlManifestLoader.parseGateway(String.join("\n",
                "gateway:",
                "  transport: http",
                "  logLevel: DEBUG",
                "http:",
                "  host: 127.0.0.1",
                "  port: 9443",
                "auth:",
                "  mode: tokenfile",
                "  tokensFile: /etc/aegis/auth-tokens.txt",
                "governance:",
                "  rps: 25",
                "  maxBytes: 4096",
                "  egressAllowHosts:",
                "    - flink.internal",
                "    - kafka.internal",
                "adapters:",
                "  enabled:",
                "    - flink"));

        GatewayConfig cfg = GatewayConfig.fromSettings(flat);
        assertEquals("http", cfg.transport());
        assertEquals("DEBUG", cfg.logLevel());
        assertEquals(9443, cfg.httpPort());
        assertEquals(25, cfg.rps());
        assertEquals(4096, cfg.maxBytes());
        assertEquals(Set.of("flink.internal", "kafka.internal"), cfg.egressAllowHosts());
        assertEquals(Set.of("flink"), cfg.adapters());
    }

    @Test
    void toStringNeverLeaksSecrets() {
        GatewayConfig cfg = stdio().writeEnabled(true).approvalSecret("top-secret-value").buildValidated();
        assertFalse(cfg.toString().contains("top-secret-value"));
    }
}
