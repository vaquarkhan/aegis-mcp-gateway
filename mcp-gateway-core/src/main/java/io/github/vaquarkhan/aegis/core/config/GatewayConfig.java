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
package io.github.vaquarkhan.aegis.core.config;

import io.github.vaquarkhan.aegis.core.yaml.YamlManifestLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Twelve-factor gateway configuration.
 *
 * <p>Resolution order, lowest priority first: built-in defaults, then the {@code gateway.yaml}
 * referenced by {@code MCP_GW_CONFIG}, then environment variables. Secrets are environment only,
 * so a mounted config file cannot leak an approval secret or a keystore password.
 *
 * <p>{@link #validate()} runs last and is deliberately fail-closed: an ambiguous or incomplete
 * security setting is a startup error rather than a warning.
 *
 * @author Viquar Khan
 */
public final class GatewayConfig {

    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_HTTP = "http";

    public static final String AUTH_OAUTH = "oauth";
    public static final String AUTH_CIMD = "cimd";
    public static final String AUTH_SPIFFE = "spiffe";
    public static final String AUTH_TOKENFILE = "tokenfile";

    public static final String PDP_BUILTIN = "builtin";
    public static final String PDP_CEDAR = "cedar";
    public static final String PDP_OPA = "opa";

    /**
     * Pre-LLD name for a remote decision point.
     *
     * @deprecated use {@link #PDP_OPA}; {@code external} is normalised to it on read.
     */
    @Deprecated
    public static final String PDP_EXTERNAL = "external";

    private static final Set<String> AUTH_MODES = Set.of(AUTH_OAUTH, AUTH_CIMD, AUTH_SPIFFE, AUTH_TOKENFILE);
    private static final Set<String> PDP_MODES = Set.of(PDP_BUILTIN, PDP_CEDAR, PDP_OPA);

    private final String configPath;
    private final String transport;
    private final String httpHost;
    private final int httpPort;
    private final boolean httpTlsEnabled;
    private final String httpTlsKeystore;
    private final String httpTlsKeystorePassword;
    private final String httpTlsKeystoreType;
    private final String httpBearerToken;
    private final boolean requireMcpHeaders;
    private final String authMode;
    private final String authTokensFile;
    private final String oauthIssuer;
    private final String oauthAudience;
    private final String oauthJwksUrl;
    private final String oauthRequiredScope;
    private final String oauthWriteScope;
    private final String cimdMetadataUrl;
    private final String spiffeTrustDomain;
    private final String spiffeWorkloadApi;
    private final String pdp;
    private final String policyFile;
    private final boolean writeEnabled;
    private final String approvalSecret;
    private final long approvalTtlMillis;
    private final int rps;
    private final int breakerFailures;
    private final long breakerResetMillis;
    private final long toolTimeoutMillis;
    private final int maxBytes;
    private final int maxSqlChars;
    private final boolean dlpEnabled;
    private final boolean promptInjectionEnabled;
    private final boolean vrpEnabled;
    private final long vrpReceiptTtlMillis;
    private final Set<String> egressAllowHosts;
    private final long tokenBudgetDaily;
    private final long semanticCacheTtlMillis;
    private final Set<String> adapters;
    private final Set<String> toolsAllowed;
    private final Set<String> resourceScopes;
    private final Set<String> jarUploadAllowDirs;
    private final boolean readonlyCaller;
    private final String toolsCatalogFile;
    private final long shutdownTimeoutMillis;
    private final String logLevel;
    private final String protocolVersion;
    private final Map<String, String> adapterProperties;

    private GatewayConfig(Builder b) {
        this.configPath = b.configPath;
        this.transport = b.transport;
        this.httpHost = b.httpHost;
        this.httpPort = b.httpPort;
        this.httpTlsEnabled = b.httpTlsEnabled;
        this.httpTlsKeystore = b.httpTlsKeystore;
        this.httpTlsKeystorePassword = b.httpTlsKeystorePassword;
        this.httpTlsKeystoreType = b.httpTlsKeystoreType;
        this.httpBearerToken = b.httpBearerToken;
        this.requireMcpHeaders = b.requireMcpHeaders;
        this.authMode = b.authMode;
        this.authTokensFile = b.authTokensFile;
        this.oauthIssuer = b.oauthIssuer;
        this.oauthAudience = b.oauthAudience;
        this.oauthJwksUrl = b.oauthJwksUrl;
        this.oauthRequiredScope = b.oauthRequiredScope;
        this.oauthWriteScope = b.oauthWriteScope;
        this.cimdMetadataUrl = b.cimdMetadataUrl;
        this.spiffeTrustDomain = b.spiffeTrustDomain;
        this.spiffeWorkloadApi = b.spiffeWorkloadApi;
        this.pdp = b.pdp;
        this.policyFile = b.policyFile;
        this.writeEnabled = b.writeEnabled;
        this.approvalSecret = b.approvalSecret;
        this.approvalTtlMillis = b.approvalTtlMillis;
        this.rps = b.rps;
        this.breakerFailures = b.breakerFailures;
        this.breakerResetMillis = b.breakerResetMillis;
        this.toolTimeoutMillis = b.toolTimeoutMillis;
        this.maxBytes = b.maxBytes;
        this.maxSqlChars = b.maxSqlChars;
        this.dlpEnabled = b.dlpEnabled;
        this.promptInjectionEnabled = b.promptInjectionEnabled;
        this.vrpEnabled = b.vrpEnabled;
        this.vrpReceiptTtlMillis = b.vrpReceiptTtlMillis;
        this.egressAllowHosts = frozen(b.egressAllowHosts);
        this.tokenBudgetDaily = b.tokenBudgetDaily;
        this.semanticCacheTtlMillis = b.semanticCacheTtlMillis;
        this.adapters = frozen(b.adapters);
        this.toolsAllowed = frozen(b.toolsAllowed);
        this.resourceScopes = frozen(b.resourceScopes);
        this.jarUploadAllowDirs = frozen(b.jarUploadAllowDirs);
        this.readonlyCaller = b.readonlyCaller;
        this.toolsCatalogFile = b.toolsCatalogFile;
        this.shutdownTimeoutMillis = b.shutdownTimeoutMillis;
        this.logLevel = b.logLevel;
        this.protocolVersion = b.protocolVersion;
        this.adapterProperties = Collections.unmodifiableMap(new LinkedHashMap<>(b.adapterProperties));
    }

    /**
     * Builds from the environment, layering {@code MCP_GW_CONFIG} YAML underneath, and validates.
     */
    public static GatewayConfig fromEnv() {
        Builder b = builder();
        String configPath = blankToNull(System.getenv("MCP_GW_CONFIG"));
        if (configPath != null) {
            b.configPath = configPath;
            applyYaml(b, loadYaml(configPath));
        }
        applyEnv(b);
        GatewayConfig cfg = b.build();
        cfg.validate();
        return cfg;
    }

    /** Builds from a YAML file only, without environment overrides. Used by tests and tooling. */
    public static GatewayConfig fromYaml(String path) {
        Builder b = builder();
        b.configPath = path;
        applyYaml(b, loadYaml(path));
        GatewayConfig cfg = b.build();
        cfg.validate();
        return cfg;
    }

    /** Builds from an already parsed flat settings map. */
    public static GatewayConfig fromSettings(Map<String, Object> flatSettings) {
        Builder b = builder();
        applyYaml(b, flatSettings);
        GatewayConfig cfg = b.build();
        cfg.validate();
        return cfg;
    }

    private static Map<String, Object> loadYaml(String path) {
        try {
            return YamlManifestLoader.loadGateway(Path.of(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("MCP_GW_CONFIG unreadable: " + path + " (" + e.getMessage() + ")", e);
        }
    }

    private static void applyYaml(Builder b, Map<String, Object> y) {
        b.transport = lower(yStr(y, "gateway.transport", b.transport));
        b.logLevel = upper(yStr(y, "gateway.logLevel", b.logLevel));
        b.httpHost = yStr(y, "http.host", b.httpHost);
        b.httpPort = yInt(y, "http.port", b.httpPort);
        b.httpTlsEnabled = yBool(y, "http.tls.enabled", b.httpTlsEnabled);
        b.httpTlsKeystore = yStr(y, "http.tls.keystore", b.httpTlsKeystore);
        b.httpTlsKeystoreType = yStr(y, "http.tls.keystoreType", b.httpTlsKeystoreType);
        b.requireMcpHeaders = yBool(y, "http.requireMcpHeaders", b.requireMcpHeaders);
        b.authMode = lower(yStr(y, "auth.mode", b.authMode));
        b.authTokensFile = yStr(y, "auth.tokensFile", b.authTokensFile);
        b.oauthIssuer = yStr(y, "auth.oauth.issuer", b.oauthIssuer);
        b.oauthAudience = yStr(y, "auth.oauth.audience", b.oauthAudience);
        b.oauthJwksUrl = yStr(y, "auth.oauth.jwksUrl", b.oauthJwksUrl);
        b.oauthRequiredScope = yStr(y, "auth.oauth.requiredScope", b.oauthRequiredScope);
        b.oauthWriteScope = yStr(y, "auth.oauth.writeScope", b.oauthWriteScope);
        b.cimdMetadataUrl = yStr(y, "auth.cimd.metadataUrl", b.cimdMetadataUrl);
        b.spiffeTrustDomain = yStr(y, "auth.spiffe.trustDomain", b.spiffeTrustDomain);
        b.spiffeWorkloadApi = yStr(y, "auth.spiffe.socket",
                yStr(y, "auth.spiffe.workloadApi", b.spiffeWorkloadApi));
        b.pdp = normalizePdp(yStr(y, "authz.pdp", b.pdp));
        b.policyFile = yStr(y, "authz.policyFile", b.policyFile);
        b.writeEnabled = yBool(y, "governance.writeEnabled", b.writeEnabled);
        b.approvalTtlMillis = yLong(y, "governance.approvalTtlMs", b.approvalTtlMillis);
        b.rps = yInt(y, "governance.rps", b.rps);
        b.breakerFailures = yInt(y, "governance.breakerFailures", b.breakerFailures);
        b.breakerResetMillis = yLong(y, "governance.breakerResetMs", b.breakerResetMillis);
        b.toolTimeoutMillis = yLong(y, "governance.toolTimeoutMs", b.toolTimeoutMillis);
        b.maxBytes = yInt(y, "governance.maxBytes", b.maxBytes);
        b.maxSqlChars = yInt(y, "governance.maxSqlChars", b.maxSqlChars);
        b.dlpEnabled = yBool(y, "governance.dlpEnabled", b.dlpEnabled);
        b.promptInjectionEnabled = yBool(y, "governance.promptInjectionEnabled", b.promptInjectionEnabled);
        b.vrpEnabled = yBool(y, "governance.vrpEnabled", b.vrpEnabled);
        b.vrpReceiptTtlMillis = yLong(y, "governance.vrpReceiptTtlMs", b.vrpReceiptTtlMillis);
        b.readonlyCaller = yBool(y, "governance.readonlyCaller", b.readonlyCaller);
        b.shutdownTimeoutMillis = yLong(y, "governance.shutdownTimeoutMs", b.shutdownTimeoutMillis);
        b.tokenBudgetDaily = yLong(y, "finops.tokenBudgetDaily", b.tokenBudgetDaily);
        b.semanticCacheTtlMillis = yLong(y, "finops.semanticCacheTtlMs", b.semanticCacheTtlMillis);
        b.toolsCatalogFile = yStr(y, "tools.catalogFile", b.toolsCatalogFile);
        ySet(y, "governance.egressAllowHosts", b.egressAllowHosts);
        ySet(y, "governance.jarUploadAllowDirs", b.jarUploadAllowDirs);
        ySet(y, "adapters.enabled", b.adapters);
        ySet(y, "tools.allowed", b.toolsAllowed);
        Set<String> scopes = new LinkedHashSet<>();
        ySet(y, "governance.resourceScopes", scopes);
        if (!scopes.isEmpty()) {
            b.resourceScopes.clear();
            b.resourceScopes.addAll(scopes);
        }
        // Every scalar setting is also visible to adapters, so an engine plugin can read its own
        // keys (for example adapters.flink.restUrl) without the core knowing they exist.
        for (Map.Entry<String, Object> e : y.entrySet()) {
            Object v = e.getValue();
            if (v != null && !(v instanceof Iterable<?>)) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    b.adapterProperties.put(e.getKey(), s);
                }
            }
        }
    }

    private static void applyEnv(Builder b) {
        try {
            b.transport = lower(env("MCP_GW_TRANSPORT", b.transport));
            b.httpHost = env("MCP_GW_HTTP_HOST", b.httpHost);
            b.httpPort = envInt("MCP_GW_HTTP_PORT", b.httpPort);
            b.httpTlsEnabled = envBool("MCP_GW_HTTP_TLS_ENABLED", b.httpTlsEnabled);
            b.httpTlsKeystore = env("MCP_GW_HTTP_TLS_KEYSTORE", b.httpTlsKeystore);
            b.httpTlsKeystorePassword = env("MCP_GW_HTTP_TLS_KEYSTORE_PASSWORD", b.httpTlsKeystorePassword);
            b.httpTlsKeystoreType = env("MCP_GW_HTTP_TLS_KEYSTORE_TYPE", b.httpTlsKeystoreType);
            b.httpBearerToken = env("MCP_GW_HTTP_BEARER_TOKEN", b.httpBearerToken);
            b.requireMcpHeaders = envBool("MCP_GW_REQUIRE_MCP_HEADERS", b.requireMcpHeaders);
            b.authMode = lower(env("MCP_GW_AUTH_MODE", b.authMode));
            b.authTokensFile = env("MCP_GW_AUTH_TOKENS_FILE", b.authTokensFile);
            b.oauthIssuer = env("MCP_GW_OAUTH_ISSUER", b.oauthIssuer);
            b.oauthAudience = env("MCP_GW_OAUTH_AUDIENCE", b.oauthAudience);
            b.oauthJwksUrl = env("MCP_GW_OAUTH_JWKS_URL", b.oauthJwksUrl);
            b.oauthRequiredScope = env("MCP_GW_OAUTH_REQUIRED_SCOPE", b.oauthRequiredScope);
            b.oauthWriteScope = env("MCP_GW_OAUTH_WRITE_SCOPE", b.oauthWriteScope);
            b.cimdMetadataUrl = env("MCP_GW_CIMD_METADATA_URL", b.cimdMetadataUrl);
            b.spiffeTrustDomain = env("MCP_GW_SPIFFE_TRUST_DOMAIN", b.spiffeTrustDomain);
            // MCP_GW_SPIFFE_SOCKET is the LLD name; the older MCP_GW_SPIFFE_WORKLOAD_API still works.
            b.spiffeWorkloadApi = env("MCP_GW_SPIFFE_SOCKET",
                    env("MCP_GW_SPIFFE_WORKLOAD_API", b.spiffeWorkloadApi));
            b.pdp = normalizePdp(env("MCP_GW_PDP", b.pdp));
            b.policyFile = env("MCP_GW_POLICY_FILE", b.policyFile);
            b.writeEnabled = envBool("MCP_GW_WRITE_ENABLED", b.writeEnabled);
            b.approvalSecret = env("MCP_GW_APPROVAL_SECRET", b.approvalSecret);
            b.approvalTtlMillis = envLong("MCP_GW_APPROVAL_TTL_MS", b.approvalTtlMillis);
            b.rps = envInt("MCP_GW_RPS", b.rps);
            b.breakerFailures = envInt("MCP_GW_BREAKER_FAILURES", b.breakerFailures);
            b.breakerResetMillis = envLong("MCP_GW_BREAKER_RESET_MS", b.breakerResetMillis);
            b.toolTimeoutMillis = envLong("MCP_GW_TOOL_TIMEOUT_MS", b.toolTimeoutMillis);
            b.maxBytes = envInt("MCP_GW_MAX_BYTES", b.maxBytes);
            b.maxSqlChars = envInt("MCP_GW_MAX_SQL_CHARS", b.maxSqlChars);
            b.dlpEnabled = envBool("MCP_GW_DLP_ENABLED", b.dlpEnabled);
            b.promptInjectionEnabled = envBool("MCP_GW_PROMPT_INJECTION_ENABLED", b.promptInjectionEnabled);
            b.vrpEnabled = envBool("MCP_GW_VRP_ENABLED", b.vrpEnabled);
            b.vrpReceiptTtlMillis = envLong("MCP_GW_VRP_RECEIPT_TTL_MS", b.vrpReceiptTtlMillis);
            b.tokenBudgetDaily = envLong("MCP_GW_TOKEN_BUDGET_DAILY", b.tokenBudgetDaily);
            b.semanticCacheTtlMillis = envLong("MCP_GW_SEMANTIC_CACHE_TTL_MS", b.semanticCacheTtlMillis);
            b.readonlyCaller = envBool("MCP_GW_READONLY_CALLER", b.readonlyCaller);
            b.toolsCatalogFile = env("MCP_GW_TOOLS_CATALOG", b.toolsCatalogFile);
            b.shutdownTimeoutMillis = envLong("MCP_GW_SHUTDOWN_TIMEOUT_MS", b.shutdownTimeoutMillis);
            b.logLevel = upper(env("MCP_GW_LOG_LEVEL", b.logLevel));
            b.protocolVersion = env("MCP_GW_PROTOCOL_VERSION", b.protocolVersion);
            envSet("MCP_GW_EGRESS_ALLOW_HOSTS", b.egressAllowHosts);
            envSet("MCP_GW_JAR_UPLOAD_ALLOW_DIRS", b.jarUploadAllowDirs);
            envSet("MCP_GW_ADAPTERS", b.adapters);
            envSet("MCP_GW_TOOLS_ALLOWED", b.toolsAllowed);
            String scopes = blankToNull(System.getenv("MCP_GW_SCOPE_RESOURCES_ALLOW"));
            if (scopes != null) {
                b.resourceScopes.clear();
                b.resourceScopes.addAll(parseCsv(scopes));
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid numeric configuration: " + e.getMessage(), e);
        }
    }

    /** Fail-closed startup checks. Throws {@link IllegalArgumentException} on the first problem. */
    public void validate() {
        if (!TRANSPORT_STDIO.equals(transport) && !TRANSPORT_HTTP.equals(transport)) {
            throw new IllegalArgumentException("MCP_GW_TRANSPORT must be stdio or http, got: " + transport);
        }
        if (!AUTH_MODES.contains(authMode)) {
            throw new IllegalArgumentException(
                    "MCP_GW_AUTH_MODE must be one of " + AUTH_MODES + ", got: " + authMode);
        }
        if (!PDP_MODES.contains(pdp)) {
            throw new IllegalArgumentException("MCP_GW_PDP must be one of " + PDP_MODES + ", got: " + pdp);
        }
        requireHttpUrl("MCP_GW_OAUTH_ISSUER", oauthIssuer);
        requireHttpUrl("MCP_GW_OAUTH_JWKS_URL", oauthJwksUrl);
        requireHttpUrl("MCP_GW_CIMD_METADATA_URL", cimdMetadataUrl);
        if (httpPort < 1 || httpPort > 65535) {
            throw new IllegalArgumentException("MCP_GW_HTTP_PORT out of range: " + httpPort);
        }
        if (rps < 1 || rps > 100_000) {
            throw new IllegalArgumentException("MCP_GW_RPS out of range: " + rps);
        }
        if (breakerFailures < 1) {
            throw new IllegalArgumentException("MCP_GW_BREAKER_FAILURES must be >= 1");
        }
        if (maxBytes < 256) {
            throw new IllegalArgumentException("MCP_GW_MAX_BYTES must be >= 256");
        }
        if (maxSqlChars < 1) {
            throw new IllegalArgumentException("MCP_GW_MAX_SQL_CHARS must be >= 1");
        }
        if (toolTimeoutMillis < 50) {
            throw new IllegalArgumentException("MCP_GW_TOOL_TIMEOUT_MS must be >= 50");
        }
        if (approvalTtlMillis < 1000) {
            throw new IllegalArgumentException("MCP_GW_APPROVAL_TTL_MS must be >= 1000");
        }
        if (writeEnabled && (approvalSecret == null || approvalSecret.isBlank())) {
            throw new IllegalArgumentException(
                    "MCP_GW_WRITE_ENABLED=true requires MCP_GW_APPROVAL_SECRET (fail-closed)");
        }
        if (TRANSPORT_HTTP.equals(transport)) {
            if (!httpAuthConfigured()) {
                throw new IllegalArgumentException(
                        "MCP_GW_TRANSPORT=http requires an inbound credential: MCP_GW_HTTP_BEARER_TOKEN, "
                                + "MCP_GW_AUTH_TOKENS_FILE, or MCP_GW_AUTH_MODE=oauth with "
                                + "MCP_GW_OAUTH_ISSUER, MCP_GW_OAUTH_AUDIENCE and MCP_GW_OAUTH_JWKS_URL "
                                + "(fail-closed)");
            }
            if (httpTlsEnabled) {
                if (httpTlsKeystore == null || httpTlsKeystore.isBlank()) {
                    throw new IllegalArgumentException(
                            "MCP_GW_HTTP_TLS_ENABLED=true requires MCP_GW_HTTP_TLS_KEYSTORE (fail-closed)");
                }
                if (httpTlsKeystorePassword == null || httpTlsKeystorePassword.isBlank()) {
                    throw new IllegalArgumentException(
                            "MCP_GW_HTTP_TLS_ENABLED=true requires MCP_GW_HTTP_TLS_KEYSTORE_PASSWORD "
                                    + "(fail-closed)");
                }
            }
        }
    }

    /**
     * Rejects a configured URL that is not an absolute http or https URL with a host.
     *
     * <p>A blank value means "not configured" and is left to the mode specific checks. A malformed
     * value is fatal, because an issuer or metadata URL the gateway cannot resolve would otherwise
     * surface as an authentication failure long after startup.
     */
    private static void requireHttpUrl(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + " is not a valid URL: " + value, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!"http".equals(scheme) && !"https".equals(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    key + " must be an absolute http or https URL with a host, got: " + value);
        }
    }

    /** Accepts the LLD names and maps the pre-LLD {@code external} onto {@code opa}. */
    private static String normalizePdp(String value) {
        String v = lower(value);
        return PDP_EXTERNAL.equals(v) ? PDP_OPA : v;
    }

    /**
     * True when an inbound credential source is configured for the HTTP transport.
     *
     * <p>OAuth counts only once a JWKS URL is present as well. Issuer and audience alone describe
     * the tokens the gateway would like to see but give it no way to verify one, so accepting that
     * pairing would let a deployment start believing it is authenticated when it can admit nobody.
     */
    public boolean httpAuthConfigured() {
        if (httpBearerToken != null && !httpBearerToken.isBlank()) {
            return true;
        }
        if (authTokensFile != null && !authTokensFile.isBlank()) {
            return true;
        }
        return AUTH_OAUTH.equals(authMode)
                && oauthIssuer != null && !oauthIssuer.isBlank()
                && oauthAudience != null && !oauthAudience.isBlank()
                && oauthJwksUrl != null && !oauthJwksUrl.isBlank();
    }

    /** Writes are only unlocked when both the flag and the approval secret are present. */
    public boolean writesUnlocked() {
        return writeEnabled && approvalSecret != null && !approvalSecret.isBlank();
    }

    /** An empty allow list means every aggregated tool of an allowed class. */
    public boolean toolAllowed(String toolName) {
        return toolsAllowed.isEmpty() || toolsAllowed.contains(toolName);
    }

    /** An empty adapter list means every discovered adapter. */
    public boolean adapterEnabled(String engineId) {
        return adapters.isEmpty() || adapters.contains(engineId);
    }

    /**
     * Engine specific setting lookup for adapters.
     *
     * <p>Resolution order is explicit adapter properties and YAML settings first, then an
     * environment variable of the same name, then {@code def}. This lets an adapter accept either a
     * dotted YAML key such as {@code adapters.flink.restUrl} or a conventional environment variable
     * such as {@code FLINK_REST_URL} without the core having to know either name.
     */
    public String adapterProperty(String key, String def) {
        if (key == null || key.isBlank()) {
            return def;
        }
        String configured = adapterProperties.get(key);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return def;
    }

    public Map<String, String> adapterProperties() {
        return adapterProperties;
    }

    public boolean isHttp() {
        return TRANSPORT_HTTP.equals(transport);
    }

    /** True when the HTTP listener binds a non-loopback interface without TLS. */
    public boolean insecureExposure() {
        if (!isHttp() || httpTlsEnabled) {
            return false;
        }
        return httpHost != null
                && !"127.0.0.1".equals(httpHost)
                && !"localhost".equalsIgnoreCase(httpHost)
                && !"::1".equals(httpHost);
    }

    public String configPath() {
        return configPath;
    }

    public String transport() {
        return transport;
    }

    public String httpHost() {
        return httpHost;
    }

    public int httpPort() {
        return httpPort;
    }

    public boolean httpTlsEnabled() {
        return httpTlsEnabled;
    }

    public String httpTlsKeystore() {
        return httpTlsKeystore;
    }

    public String httpTlsKeystorePassword() {
        return httpTlsKeystorePassword;
    }

    public String httpTlsKeystoreType() {
        return httpTlsKeystoreType;
    }

    public String httpBearerToken() {
        return httpBearerToken;
    }

    /**
     * True when a POST to the MCP endpoint must carry an {@code Mcp-Method} header.
     *
     * <p>Off by default because not every client emits the streamable HTTP hint headers yet, and
     * rejecting those clients would break interoperability rather than improve security.
     */
    public boolean requireMcpHeaders() {
        return requireMcpHeaders;
    }

    public String authMode() {
        return authMode;
    }

    public String authTokensFile() {
        return authTokensFile;
    }

    public String oauthIssuer() {
        return oauthIssuer;
    }

    public String oauthAudience() {
        return oauthAudience;
    }

    public String oauthJwksUrl() {
        return oauthJwksUrl;
    }

    public String oauthRequiredScope() {
        return oauthRequiredScope;
    }

    /** Scope that promotes an OAuth caller out of read-only, {@code MCP_GW_OAUTH_WRITE_SCOPE}. */
    public String oauthWriteScope() {
        return oauthWriteScope;
    }

    public String cimdMetadataUrl() {
        return cimdMetadataUrl;
    }

    public String spiffeTrustDomain() {
        return spiffeTrustDomain;
    }

    public String spiffeWorkloadApi() {
        return spiffeWorkloadApi;
    }

    /** LLD name for {@code MCP_GW_SPIFFE_SOCKET}, the workload API socket path. */
    public String spiffeSocket() {
        return spiffeWorkloadApi;
    }

    public String pdp() {
        return pdp;
    }

    public String policyFile() {
        return policyFile;
    }

    public boolean writeEnabled() {
        return writeEnabled;
    }

    public String approvalSecret() {
        return approvalSecret;
    }

    public long approvalTtlMillis() {
        return approvalTtlMillis;
    }

    public int rps() {
        return rps;
    }

    public int breakerFailures() {
        return breakerFailures;
    }

    public long breakerResetMillis() {
        return breakerResetMillis;
    }

    public long toolTimeoutMillis() {
        return toolTimeoutMillis;
    }

    public int maxBytes() {
        return maxBytes;
    }

    public int maxSqlChars() {
        return maxSqlChars;
    }

    public boolean dlpEnabled() {
        return dlpEnabled;
    }

    public boolean promptInjectionEnabled() {
        return promptInjectionEnabled;
    }

    public boolean vrpEnabled() {
        return vrpEnabled;
    }

    public long vrpReceiptTtlMillis() {
        return vrpReceiptTtlMillis;
    }

    public Set<String> egressAllowHosts() {
        return egressAllowHosts;
    }

    public long tokenBudgetDaily() {
        return tokenBudgetDaily;
    }

    public long semanticCacheTtlMillis() {
        return semanticCacheTtlMillis;
    }

    public Set<String> adapters() {
        return adapters;
    }

    public Set<String> toolsAllowed() {
        return toolsAllowed;
    }

    public Set<String> resourceScopes() {
        return resourceScopes;
    }

    public Set<String> jarUploadAllowDirs() {
        return jarUploadAllowDirs;
    }

    public boolean readonlyCaller() {
        return readonlyCaller;
    }

    public String toolsCatalogFile() {
        return toolsCatalogFile;
    }

    public long shutdownTimeoutMillis() {
        return shutdownTimeoutMillis;
    }

    public String logLevel() {
        return logLevel;
    }

    public String protocolVersion() {
        return protocolVersion;
    }

    /** Never render secrets. */
    @Override
    public String toString() {
        return "GatewayConfig[transport=" + transport
                + ", authMode=" + authMode
                + ", pdp=" + pdp
                + ", writesUnlocked=" + writesUnlocked()
                + ", adapters=" + adapters
                + ", toolsAllowed=" + toolsAllowed
                + ", rps=" + rps
                + ", maxBytes=" + maxBytes + "]";
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder carrying the built-in defaults.
     *
     * @author Viquar Khan
     */
    public static final class Builder {
        private String configPath;
        private String transport = TRANSPORT_STDIO;
        private String httpHost = "127.0.0.1";
        private int httpPort = 8090;
        private boolean httpTlsEnabled = false;
        private String httpTlsKeystore;
        private String httpTlsKeystorePassword;
        private String httpTlsKeystoreType = "PKCS12";
        private String httpBearerToken;
        private boolean requireMcpHeaders = false;
        private String authMode = AUTH_TOKENFILE;
        private String authTokensFile;
        private String oauthIssuer;
        private String oauthAudience;
        private String oauthJwksUrl;
        private String oauthRequiredScope;
        private String oauthWriteScope;
        private String cimdMetadataUrl;
        private String spiffeTrustDomain;
        private String spiffeWorkloadApi;
        private String pdp = PDP_BUILTIN;
        private String policyFile;
        private boolean writeEnabled = false;
        private String approvalSecret;
        private long approvalTtlMillis = 300_000L;
        private int rps = 5;
        private int breakerFailures = 5;
        private long breakerResetMillis = 30_000L;
        private long toolTimeoutMillis = 30_000L;
        private int maxBytes = 65_536;
        private int maxSqlChars = 32_768;
        private boolean dlpEnabled = true;
        private boolean promptInjectionEnabled = true;
        private boolean vrpEnabled = true;
        private long vrpReceiptTtlMillis = 900_000L;
        private final Set<String> egressAllowHosts = new LinkedHashSet<>();
        private long tokenBudgetDaily = 0L;
        private long semanticCacheTtlMillis = 0L;
        private final Set<String> adapters = new LinkedHashSet<>();
        private final Set<String> toolsAllowed = new LinkedHashSet<>();
        private final Set<String> resourceScopes = new LinkedHashSet<>(Set.of("*"));
        private final Set<String> jarUploadAllowDirs = new LinkedHashSet<>();
        private boolean readonlyCaller = false;
        private String toolsCatalogFile;
        private long shutdownTimeoutMillis = 15_000L;
        private String logLevel = "INFO";
        private String protocolVersion = "2024-11-05";
        private final Map<String, String> adapterProperties = new LinkedHashMap<>();

        /** Explicit no-op that reads as "start from the secure defaults". */
        public Builder defaults() {
            return this;
        }

        /** Engine specific settings an adapter can read through {@link #adapterProperty}. */
        public Builder adapterProperties(Map<String, String> v) {
            if (v != null) {
                v.forEach((k, value) -> {
                    if (k != null && value != null) {
                        adapterProperties.put(k, value);
                    }
                });
            }
            return this;
        }

        public Builder transport(String v) {
            this.transport = lower(v);
            return this;
        }

        public Builder httpHost(String v) {
            this.httpHost = v;
            return this;
        }

        public Builder httpPort(int v) {
            this.httpPort = v;
            return this;
        }

        public Builder httpTlsEnabled(boolean v) {
            this.httpTlsEnabled = v;
            return this;
        }

        public Builder httpTlsKeystore(String v) {
            this.httpTlsKeystore = v;
            return this;
        }

        public Builder httpTlsKeystorePassword(String v) {
            this.httpTlsKeystorePassword = v;
            return this;
        }

        public Builder httpBearerToken(String v) {
            this.httpBearerToken = v;
            return this;
        }

        public Builder requireMcpHeaders(boolean v) {
            this.requireMcpHeaders = v;
            return this;
        }

        public Builder authMode(String v) {
            this.authMode = lower(v);
            return this;
        }

        public Builder authTokensFile(String v) {
            this.authTokensFile = v;
            return this;
        }

        public Builder oauthIssuer(String v) {
            this.oauthIssuer = v;
            return this;
        }

        public Builder oauthAudience(String v) {
            this.oauthAudience = v;
            return this;
        }

        public Builder oauthJwksUrl(String v) {
            this.oauthJwksUrl = v;
            return this;
        }

        public Builder oauthRequiredScope(String v) {
            this.oauthRequiredScope = v;
            return this;
        }

        public Builder oauthWriteScope(String v) {
            this.oauthWriteScope = v;
            return this;
        }

        public Builder cimdMetadataUrl(String v) {
            this.cimdMetadataUrl = v;
            return this;
        }

        public Builder spiffeTrustDomain(String v) {
            this.spiffeTrustDomain = v;
            return this;
        }

        /** SPIFFE workload API socket, {@code MCP_GW_SPIFFE_SOCKET}. */
        public Builder spiffeSocket(String v) {
            this.spiffeWorkloadApi = v;
            return this;
        }

        public Builder pdp(String v) {
            this.pdp = normalizePdp(v);
            return this;
        }

        public Builder policyFile(String v) {
            this.policyFile = v;
            return this;
        }

        public Builder writeEnabled(boolean v) {
            this.writeEnabled = v;
            return this;
        }

        public Builder approvalSecret(String v) {
            this.approvalSecret = v;
            return this;
        }

        public Builder approvalTtlMillis(long v) {
            this.approvalTtlMillis = v;
            return this;
        }

        public Builder rps(int v) {
            this.rps = v;
            return this;
        }

        public Builder breakerFailures(int v) {
            this.breakerFailures = v;
            return this;
        }

        public Builder breakerResetMillis(long v) {
            this.breakerResetMillis = v;
            return this;
        }

        public Builder toolTimeoutMillis(long v) {
            this.toolTimeoutMillis = v;
            return this;
        }

        public Builder maxBytes(int v) {
            this.maxBytes = v;
            return this;
        }

        public Builder maxSqlChars(int v) {
            this.maxSqlChars = v;
            return this;
        }

        public Builder dlpEnabled(boolean v) {
            this.dlpEnabled = v;
            return this;
        }

        public Builder promptInjectionEnabled(boolean v) {
            this.promptInjectionEnabled = v;
            return this;
        }

        public Builder vrpEnabled(boolean v) {
            this.vrpEnabled = v;
            return this;
        }

        public Builder readonlyCaller(boolean v) {
            this.readonlyCaller = v;
            return this;
        }

        public Builder tokenBudgetDaily(long v) {
            this.tokenBudgetDaily = v;
            return this;
        }

        public Builder semanticCacheTtlMillis(long v) {
            this.semanticCacheTtlMillis = v;
            return this;
        }

        public Builder egressAllowHosts(Set<String> v) {
            this.egressAllowHosts.clear();
            if (v != null) {
                this.egressAllowHosts.addAll(v);
            }
            return this;
        }

        public Builder adapters(Set<String> v) {
            this.adapters.clear();
            if (v != null) {
                this.adapters.addAll(v);
            }
            return this;
        }

        public Builder toolsAllowed(Set<String> v) {
            this.toolsAllowed.clear();
            if (v != null) {
                this.toolsAllowed.addAll(v);
            }
            return this;
        }

        public Builder resourceScopes(Set<String> v) {
            this.resourceScopes.clear();
            if (v != null) {
                this.resourceScopes.addAll(v);
            }
            return this;
        }

        public Builder jarUploadAllowDirs(Set<String> v) {
            this.jarUploadAllowDirs.clear();
            if (v != null) {
                this.jarUploadAllowDirs.addAll(v);
            }
            return this;
        }

        public Builder toolsCatalogFile(String v) {
            this.toolsCatalogFile = v;
            return this;
        }

        public Builder shutdownTimeoutMillis(long v) {
            this.shutdownTimeoutMillis = v;
            return this;
        }

        public Builder logLevel(String v) {
            this.logLevel = upper(v);
            return this;
        }

        public GatewayConfig build() {
            return new GatewayConfig(this);
        }

        /** Builds and validates in one step. */
        public GatewayConfig buildValidated() {
            GatewayConfig cfg = build();
            cfg.validate();
            return cfg;
        }
    }

    private static Set<String> frozen(Set<String> in) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(in));
    }

    private static Set<String> parseCsv(String csv) {
        Set<String> set = new LinkedHashSet<>();
        if (csv == null) {
            return set;
        }
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return set;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static boolean envBool(String key, boolean def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : Boolean.parseBoolean(v.trim());
    }

    private static int envInt(String key, int def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : Integer.parseInt(v.trim());
    }

    private static long envLong(String key, long def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : Long.parseLong(v.trim());
    }

    private static void envSet(String key, Set<String> target) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) {
            target.clear();
            target.addAll(parseCsv(v));
        }
    }

    private static String yStr(Map<String, Object> y, String key, String def) {
        Object v = y.get(key);
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static boolean yBool(Map<String, Object> y, String key, boolean def) {
        Object v = y.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private static int yInt(Map<String, Object> y, String key, int def) {
        Object v = y.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(v).trim());
    }

    private static long yLong(Map<String, Object> y, String key, long def) {
        Object v = y.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(v).trim());
    }

    private static void ySet(Map<String, Object> y, String key, Set<String> target) {
        Set<String> values = YamlManifestLoader.stringSet(y, key);
        if (!values.isEmpty()) {
            target.clear();
            target.addAll(values);
        }
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String lower(String v) {
        return v == null ? null : v.trim().toLowerCase(Locale.ROOT);
    }

    private static String upper(String v) {
        return v == null ? null : v.trim().toUpperCase(Locale.ROOT);
    }
}
