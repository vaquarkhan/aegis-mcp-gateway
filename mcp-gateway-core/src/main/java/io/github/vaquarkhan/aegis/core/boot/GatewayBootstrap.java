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
package io.github.vaquarkhan.aegis.core.boot;

import io.github.vaquarkhan.aegis.core.auth.BearerAuthFilter;
import io.github.vaquarkhan.aegis.core.auth.CallerContext;
import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.auth.NonceStore;
import io.github.vaquarkhan.aegis.core.auth.OAuthResourceFilter;
import io.github.vaquarkhan.aegis.core.auth.SpiffeMtls;
import io.github.vaquarkhan.aegis.core.auth.TokenRegistry;
import io.github.vaquarkhan.aegis.core.authz.BuiltinPolicyEngine;
import io.github.vaquarkhan.aegis.core.authz.CedarPdp;
import io.github.vaquarkhan.aegis.core.authz.OpaPdp;
import io.github.vaquarkhan.aegis.core.authz.PolicyDecisionPoint;
import io.github.vaquarkhan.aegis.core.config.AdapterRegistry;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.finops.SemanticCache;
import io.github.vaquarkhan.aegis.core.finops.TokenBudget;
import io.github.vaquarkhan.aegis.core.governance.Approval;
import io.github.vaquarkhan.aegis.core.governance.CircuitBreaker;
import io.github.vaquarkhan.aegis.core.governance.EgressGuard;
import io.github.vaquarkhan.aegis.core.governance.Exposure;
import io.github.vaquarkhan.aegis.core.governance.OutputControls;
import io.github.vaquarkhan.aegis.core.governance.PromptInjectionGuard;
import io.github.vaquarkhan.aegis.core.governance.RateLimiter;
import io.github.vaquarkhan.aegis.core.governance.Scope;
import io.github.vaquarkhan.aegis.core.governance.TimeoutExecutor;
import io.github.vaquarkhan.aegis.core.integrity.DigestRegistry;
import io.github.vaquarkhan.aegis.core.integrity.ToolCatalogIntegrity;
import io.github.vaquarkhan.aegis.core.integrity.VrpValidator;
import io.github.vaquarkhan.aegis.core.interceptor.ArgumentSanitizeMutator;
import io.github.vaquarkhan.aegis.core.interceptor.InterceptorChain;
import io.github.vaquarkhan.aegis.core.observability.AuditLog;
import io.github.vaquarkhan.aegis.core.observability.Metrics;
import io.github.vaquarkhan.aegis.core.observability.Trace;
import io.github.vaquarkhan.aegis.core.router.TaxonomyRouter;
import io.github.vaquarkhan.aegis.core.router.ToolManifestAggregator;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.CredentialResolver;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.OutboundCredential;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.transport.OpsServlet;
import io.github.vaquarkhan.aegis.core.transport.StdioBoot;
import io.github.vaquarkhan.aegis.core.transport.StreamableHttpTransport;
import io.github.vaquarkhan.aegis.core.transport.TlsSettings;
import io.github.vaquarkhan.aegis.core.yaml.YamlManifestLoader;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Filter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires configuration, adapters, governance and transport into a running MCP server.
 *
 * <p>Bootstrap order matters: configuration is validated before anything is constructed, adapters
 * are aggregated before tools are registered, and unexposed tools are filtered out at registration
 * so a model never sees a tool this deployment will refuse to run.
 *
 * @author Viquar Khan
 */
public final class GatewayBootstrap {

    public static final String VERSION = "0.1.0";
    public static final String SERVER_NAME = "aegis-mcp-gateway";
    public static final String MCP_ENDPOINT = "/mcp";

    private static final Logger LOG = LoggerFactory.getLogger(GatewayBootstrap.class);

    private GatewayBootstrap() {}

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                LoggerFactory.getLogger("uncaught").error("uncaught in {}", t.getName(), e));
        try {
            run();
        } catch (IllegalArgumentException e) {
            LOG.error("configuration error: {}", e.getMessage());
            System.exit(2);
        } catch (Throwable t) {
            LOG.error("fatal startup error", t);
            System.exit(1);
        }
    }

    static void run() throws Exception {
        GatewayConfig cfg = GatewayConfig.fromEnv();
        applyLogLevel(cfg.logLevel());
        LOG.info("starting {} version={} transport={} authMode={} writesUnlocked={}",
                SERVER_NAME, VERSION, cfg.transport(), cfg.authMode(), cfg.writesUnlocked());

        List<EngineAdapter> adapters = AdapterRegistry.discover(cfg);

        Map<String, YamlManifestLoader.ToolOverlay> overlay = loadOverlay(cfg);
        ToolCatalogIntegrity integrity = new ToolCatalogIntegrity(new DigestRegistry());
        ToolManifestAggregator.Aggregation aggregation =
                new ToolManifestAggregator(integrity, overlay).aggregate(adapters, cfg);

        Metrics metrics = new Metrics();
        AuditLog audit = new AuditLog();
        OutputControls output = new OutputControls(cfg.maxBytes(), cfg.dlpEnabled());
        TimeoutExecutor executor = new TimeoutExecutor();
        Exposure exposure = new Exposure(cfg);
        PolicyDecisionPoint pdp = buildPdp(cfg);
        Approval approval = new Approval(cfg.approvalSecret(), new NonceStore());
        EgressGuard egressGuard = new EgressGuard(aggregation.egressAllowHosts());
        RateLimiter rateLimiter = new RateLimiter(cfg.rps());
        CircuitBreaker breaker = new CircuitBreaker(cfg.breakerFailures(), cfg.breakerResetMillis());
        PromptInjectionGuard injectionGuard = new PromptInjectionGuard(cfg.promptInjectionEnabled());
        VrpValidator vrp = new VrpValidator(cfg.vrpEnabled(), cfg.vrpReceiptTtlMillis());
        TokenBudget budget = new TokenBudget(cfg.tokenBudgetDaily());
        SemanticCache cache = new SemanticCache(cfg.semanticCacheTtlMillis());

        InterceptorChain chain = new InterceptorChain(
                cfg, exposure, pdp, approval, egressGuard, rateLimiter, breaker,
                injectionGuard, vrp, executor, output, budget, cache)
                .addMutator(new ArgumentSanitizeMutator())
                .addObserver(new Trace())
                .addObserver(metrics)
                .addObserver(audit);

        McpJsonMapper json = new JacksonMcpJsonMapperSupplier().get();
        CallerIdentity defaultCaller = defaultCaller(cfg);
        Map<String, CredentialResolver> resolvers =
                credentialResolvers(adapters, aggregation.router());

        List<McpServerFeatures.SyncToolSpecification> toolSpecs =
                buildToolSpecs(aggregation.tools(), exposure, chain, json, defaultCaller, resolvers);
        List<McpServerFeatures.SyncResourceSpecification> resourceSpecs =
                buildResourceSpecs(aggregation.resources(), output, audit, defaultCaller);

        McpSchema.ServerCapabilities caps = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .resources(false, false)
                .build();

        AtomicReference<Server> httpServer = new AtomicReference<>();
        OpsServlet ops = new OpsServlet(metrics, () -> true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutdown: draining backend pool and http listener");
            // Fail the probes first so the load balancer stops sending work before the pool drains.
            ops.markNotLive();
            executor.shutdown(cfg.shutdownTimeoutMillis());
            StreamableHttpTransport.stopQuietly(httpServer.get(), cfg.shutdownTimeoutMillis());
        }, "aegis-shutdown"));

        LOG.info("registered tools={} resources={} readOnlyProfile={}",
                toolSpecs.size(), resourceSpecs.size(), !cfg.writesUnlocked());

        if (cfg.isHttp()) {
            if (cfg.insecureExposure()) {
                LOG.warn("HTTP bind host {} is not loopback and TLS is disabled; "
                        + "prefer MCP_GW_HTTP_TLS_ENABLED=true", cfg.httpHost());
            }
            Filter authFilter = buildAuthFilter(cfg);
            HttpServletStreamableServerTransportProvider httpTransport =
                    HttpServletStreamableServerTransportProvider.builder()
                            .jsonMapper(json)
                            .mcpEndpoint(MCP_ENDPOINT)
                            .build();
            McpServer.sync(httpTransport)
                    .serverInfo(SERVER_NAME, VERSION)
                    .capabilities(caps)
                    .tools(toolSpecs)
                    .resources(resourceSpecs)
                    .build();
            Server server = StreamableHttpTransport.start(
                    cfg.httpHost(),
                    cfg.httpPort(),
                    MCP_ENDPOINT + "/*",
                    httpTransport,
                    authFilter,
                    ops,
                    TlsSettings.from(cfg),
                    cfg.requireMcpHeaders());
            httpServer.set(server);
            server.join();
        } else {
            StdioServerTransportProvider stdio = StdioBoot.create(json);
            McpServer.sync(stdio)
                    .serverInfo(SERVER_NAME, VERSION)
                    .capabilities(caps)
                    .tools(toolSpecs)
                    .resources(resourceSpecs)
                    .build();
            StdioBoot.await();
        }
    }

    /**
     * Maps each registered tool name to the credential resolver of the adapter that contributed it,
     * so the call handler can exchange the caller identity for an outbound credential.
     */
    static Map<String, CredentialResolver> credentialResolvers(
            List<EngineAdapter> adapters, TaxonomyRouter router) {

        Map<String, CredentialResolver> byEngine = new LinkedHashMap<>();
        for (EngineAdapter adapter : adapters) {
            adapter.credentialResolver().ifPresent(r -> byEngine.put(adapter.engineId(), r));
        }
        if (byEngine.isEmpty()) {
            return Map.of();
        }
        Map<String, CredentialResolver> byTool = new LinkedHashMap<>();
        router.snapshot().forEach((toolName, engineId) -> {
            CredentialResolver resolver = byEngine.get(engineId);
            if (resolver != null) {
                byTool.put(toolName, resolver);
            }
        });
        LOG.info("credential resolvers wired for tools={} engines={}", byTool.size(), byEngine.keySet());
        return Map.copyOf(byTool);
    }

    static List<McpServerFeatures.SyncToolSpecification> buildToolSpecs(
            Map<String, ToolDef> tools,
            Exposure exposure,
            InterceptorChain chain,
            McpJsonMapper json,
            CallerIdentity defaultCaller) {
        return buildToolSpecs(tools, exposure, chain, json, defaultCaller, Map.of());
    }

    /** Builds MCP tool specifications for every tool this deployment exposes. */
    static List<McpServerFeatures.SyncToolSpecification> buildToolSpecs(
            Map<String, ToolDef> tools,
            Exposure exposure,
            InterceptorChain chain,
            McpJsonMapper json,
            CallerIdentity defaultCaller,
            Map<String, CredentialResolver> credentialResolvers) {

        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (Map.Entry<String, ToolDef> entry : tools.entrySet()) {
            ToolDef def = entry.getValue();
            if (!exposure.isExposed(def)) {
                LOG.info("withholding tool {} ({})", def.name(), exposure.reason(def.name(), def.cls()));
                continue;
            }
            CredentialResolver resolver = credentialResolvers.get(entry.getKey());
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(def.name())
                    .description(def.description())
                    .inputSchema(json, def.inputSchemaJson())
                    .build();
            specs.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler((exchange, request) -> {
                        String traceId = Trace.newId();
                        try {
                            CallerIdentity caller = CallerContext.current().orElse(defaultCaller);
                            CallContext ctx = new CallContext(
                                    def.name(),
                                    def.cls(),
                                    request.arguments() == null ? Map.of() : request.arguments(),
                                    caller,
                                    traceId,
                                    Optional.empty());
                            ctx = withOutboundCredential(ctx, resolver);
                            InterceptorChain.Result result = chain.execute(def, ctx);
                            return McpSchema.CallToolResult.builder()
                                    .isError(result.error())
                                    .addTextContent(result.body())
                                    .build();
                        } finally {
                            Trace.clear();
                        }
                    })
                    .build());
        }
        return specs;
    }

    /**
     * Exchanges the caller identity for an outbound credential, if the owning adapter offers one.
     *
     * <p>An empty resolution is not a denial: the adapter simply falls back to whatever shared
     * credential it was configured with. A resolver that throws is treated the same way, because a
     * secret store outage must not be able to turn into a governance bypass in either direction.
     */
    static CallContext withOutboundCredential(CallContext ctx, CredentialResolver resolver) {
        if (resolver == null) {
            return ctx;
        }
        try {
            Optional<OutboundCredential> credential =
                    resolver.resolve(ctx.caller(), Scope.resourceOf(ctx));
            return credential == null || credential.isEmpty() ? ctx : ctx.withOutboundCredential(credential);
        } catch (RuntimeException e) {
            LOG.warn("credential resolution failed for tool {}: {}", ctx.toolName(), e.getMessage());
            return ctx;
        }
    }

    /** Builds MCP resource specifications. Resources are read-only and always redacted on request. */
    static List<McpServerFeatures.SyncResourceSpecification> buildResourceSpecs(
            List<ResourceDef> resources,
            OutputControls output,
            AuditLog audit,
            CallerIdentity defaultCaller) {

        List<McpServerFeatures.SyncResourceSpecification> specs = new ArrayList<>();
        for (ResourceDef def : resources) {
            specs.add(new McpServerFeatures.SyncResourceSpecification(
                    McpSchema.Resource.builder()
                            .uri(def.uri())
                            .name(def.name())
                            .mimeType(def.mimeType())
                            .build(),
                    (exchange, request) -> {
                        CallerIdentity caller = CallerContext.current().orElse(defaultCaller);
                        String traceId = Trace.newId();
                        try {
                            CallContext ctx = CallContext.of(
                                    def.name(), ToolClass.READ, Map.of(), caller, traceId);
                            String body = def.read().apply(ctx);
                            String safe = def.redact() ? output.boundAndRedact(body) : output.bound(body);
                            audit.append(caller.callerId(), def.uri(), "ALLOWED");
                            return new McpSchema.ReadResourceResult(List.of(
                                    new McpSchema.TextResourceContents(def.uri(), def.mimeType(), safe)));
                        } catch (RuntimeException e) {
                            audit.append(caller.callerId(), def.uri(), "DENIED:BACKEND_ERROR:step10");
                            LOG.warn("resource {} error: {}", def.uri(), e.getMessage());
                            String msg = output.boundAndRedact(
                                    e.getMessage() == null ? e.getClass().getName() : e.getMessage());
                            return new McpSchema.ReadResourceResult(List.of(
                                    new McpSchema.TextResourceContents(def.uri(), def.mimeType(), msg)));
                        } finally {
                            Trace.clear();
                        }
                    }));
        }
        return specs;
    }

    static PolicyDecisionPoint buildPdp(GatewayConfig cfg) {
        switch (cfg.pdp()) {
            case GatewayConfig.PDP_CEDAR -> {
                return new CedarPdp(cfg.policyFile());
            }
            case GatewayConfig.PDP_OPA -> {
                return new OpaPdp(cfg.policyFile());
            }
            default -> {
                BuiltinPolicyEngine engine = BuiltinPolicyEngine.load(cfg.policyFile());
                LOG.info("builtin policy engine loaded rules={} failClosed={}",
                        engine.ruleCount(), engine.failClosed());
                return engine;
            }
        }
    }

    static Filter buildAuthFilter(GatewayConfig cfg) throws Exception {
        switch (cfg.authMode()) {
            case GatewayConfig.AUTH_OAUTH -> {
                return new OAuthResourceFilter(
                        cfg.oauthIssuer(), cfg.oauthAudience(), cfg.oauthJwksUrl(),
                        cfg.oauthRequiredScope(), cfg.oauthWriteScope());
            }
            case GatewayConfig.AUTH_CIMD -> {
                // The document verifier exists and is unit tested, but a client identity metadata
                // document only names a client; it does not authenticate the request that presents
                // it. Until the authorization server flow lands, starting here would mean shipping
                // an HTTP listener with no way to reject a forged caller.
                throw new IllegalArgumentException(
                        "MCP_GW_AUTH_MODE=cimd is not implemented in " + VERSION + "; see DESIGN section 10");
            }
            case GatewayConfig.AUTH_SPIFFE -> {
                new SpiffeMtls(cfg.spiffeTrustDomain(), cfg.spiffeWorkloadApi());
                throw new IllegalArgumentException(
                        "MCP_GW_AUTH_MODE=spiffe is not implemented in " + VERSION + "; see DESIGN section 10");
            }
            default -> {
                if (cfg.authTokensFile() != null && !cfg.authTokensFile().isBlank()) {
                    TokenRegistry registry = TokenRegistry.load(Path.of(cfg.authTokensFile()));
                    LOG.info("loaded auth token registry entries={}", registry.size());
                    return new BearerAuthFilter(registry);
                }
                return new BearerAuthFilter(
                        cfg.httpBearerToken(), "http", cfg.resourceScopes(), cfg.readonlyCaller());
            }
        }
    }

    static CallerIdentity defaultCaller(GatewayConfig cfg) {
        String label = cfg.isHttp() ? "http" : CallerIdentity.STDIO_CALLER;
        return new CallerIdentity(label, cfg.resourceScopes(), cfg.readonlyCaller());
    }

    private static Map<String, YamlManifestLoader.ToolOverlay> loadOverlay(GatewayConfig cfg) {
        String path = cfg.toolsCatalogFile();
        if (path == null || path.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, YamlManifestLoader.ToolOverlay> overlay =
                    YamlManifestLoader.index(YamlManifestLoader.loadToolCatalog(Path.of(path)));
            LOG.info("loaded tools catalog overlay entries={} from {}", overlay.size(), path);
            return overlay;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "MCP_GW_TOOLS_CATALOG unreadable: " + path + " (" + e.getMessage() + ")", e);
        }
    }

    /** Applies the configured log level reflectively so the module does not compile against Logback. */
    private static void applyLogLevel(String level) {
        try {
            Object factory = LoggerFactory.getILoggerFactory();
            if (!factory.getClass().getName().contains("LoggerContext")) {
                return;
            }
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
            Object lv = levelClass.getMethod("toLevel", String.class, levelClass)
                    .invoke(null, level, levelClass.getField("INFO").get(null));
            Object root = factory.getClass().getMethod("getLogger", String.class)
                    .invoke(factory, Logger.ROOT_LOGGER_NAME);
            root.getClass().getMethod("setLevel", levelClass).invoke(root, lv);
            Object app = factory.getClass().getMethod("getLogger", String.class)
                    .invoke(factory, "io.github.vaquarkhan.aegis");
            app.getClass().getMethod("setLevel", levelClass).invoke(app, lv);
        } catch (Exception e) {
            // The binder may not be Logback, for example in tests. Leave the level alone.
        }
    }
}
