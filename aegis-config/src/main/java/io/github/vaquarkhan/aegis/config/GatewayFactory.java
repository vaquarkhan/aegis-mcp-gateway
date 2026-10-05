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
package io.github.vaquarkhan.aegis.config;

import io.github.vaquarkhan.aegis.config.credentials.StaticCredentialProvider;
import io.github.vaquarkhan.aegis.config.hooks.RemoteHook;
import io.github.vaquarkhan.aegis.config.hooks.SqlReadOnlyHook;
import io.github.vaquarkhan.aegis.config.http.HttpToolExecutor;
import io.github.vaquarkhan.aegis.observability.otlp.OtlpSpanExporter;
import io.github.vaquarkhan.aegis.config.http.HttpToolSource;
import io.github.vaquarkhan.aegis.config.http.HttpToolSpec;
import io.github.vaquarkhan.aegis.config.policy.OpaPolicy;
import io.github.vaquarkhan.aegis.config.spi.GovernanceHookFactory;
import io.github.vaquarkhan.aegis.config.spi.StateStoreFactory;
import io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory;
import io.github.vaquarkhan.aegis.engine.GovernanceEngine;
import io.github.vaquarkhan.aegis.engine.GovernanceSettings;
import io.github.vaquarkhan.aegis.engine.approval.ApprovalService;
import io.github.vaquarkhan.aegis.engine.approval.VrpService;
import io.github.vaquarkhan.aegis.engine.audit.AuditCheckpointPolicy;
import io.github.vaquarkhan.aegis.engine.audit.HashChainFileAuditSink;
import io.github.vaquarkhan.aegis.config.secrets.FileSecretProvider;
import io.github.vaquarkhan.aegis.config.secrets.VaultKvSecretProvider;
import io.github.vaquarkhan.aegis.config.spi.SecretProviderFactory;
import io.github.vaquarkhan.aegis.engine.catalog.CatalogIntegrity;
import io.github.vaquarkhan.aegis.engine.catalog.SignedCatalogManifest;
import io.github.vaquarkhan.aegis.engine.catalog.DryRunSpec;
import io.github.vaquarkhan.aegis.engine.catalog.GovernedTool;
import io.github.vaquarkhan.aegis.engine.catalog.ResourceRule;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.content.ContentGovernor;
import io.github.vaquarkhan.aegis.engine.content.ContentSettings;
import io.github.vaquarkhan.aegis.engine.spi.ContentSource;
import io.github.vaquarkhan.aegis.engine.control.CircuitBreakers;
import io.github.vaquarkhan.aegis.engine.control.ControlMode;
import io.github.vaquarkhan.aegis.engine.control.EgressGuard;
import io.github.vaquarkhan.aegis.engine.control.HookBinding;
import io.github.vaquarkhan.aegis.engine.control.InjectionScreen;
import io.github.vaquarkhan.aegis.engine.control.OutputGuard;
import io.github.vaquarkhan.aegis.engine.control.RateLimiter;
import io.github.vaquarkhan.aegis.engine.control.RulePolicy;
import io.github.vaquarkhan.aegis.engine.control.ScopeRule;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.observe.DecisionMetrics;
import io.github.vaquarkhan.aegis.engine.json.JsonPointer;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.spi.AuditSink;
import io.github.vaquarkhan.aegis.engine.spi.CredentialProvider;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import io.github.vaquarkhan.aegis.engine.spi.PolicyDecisionPoint;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore;
import io.github.vaquarkhan.aegis.mcp.admin.AdminApi;
import io.github.vaquarkhan.aegis.mcp.client.HttpMcpClient;
import io.github.vaquarkhan.aegis.mcp.client.McpClient;
import io.github.vaquarkhan.aegis.mcp.client.McpContentSource;
import io.github.vaquarkhan.aegis.mcp.client.McpProxyToolSource;
import io.github.vaquarkhan.aegis.mcp.client.StdioMcpClient;
import io.github.vaquarkhan.aegis.mcp.server.McpServerHandler;
import io.github.vaquarkhan.aegis.mcp.server.ServerOptions;
import io.github.vaquarkhan.aegis.mcp.server.SessionRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Builds a {@link Gateway} from one configuration document. This is the whole adoption surface for
 * a project: write a config file, pick a front door (the gateway's own server or Knox), done. See
 * docs/CONFIGURATION.md for every key.
 */
public final class GatewayFactory {

    private static final System.Logger LOG = System.getLogger(GatewayFactory.class.getName());
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    /** Built-in hook type: an out-of-process control reached over HTTP, no Java required. */
    private static final String REMOTE_HOOK = "remote";
    /** Built-in hook type: refuses a statement argument that is not a single read-only SQL statement. */
    private static final String SQL_READONLY_HOOK = "sql-readonly";
    private static final Set<String> BUILT_IN_HOOKS = Set.of(REMOTE_HOOK, SQL_READONLY_HOOK);
    public static final Set<String> HOST_SECTIONS = Set.of("host", "knox");

    private GatewayFactory() {}

    public static Gateway build(Path file, BuildContext ctx) throws Exception {
        Path base = file.toAbsolutePath().getParent();
        BuildContext c = new BuildContext(ctx.env(), base, ctx.clock(), ctx.resolver(), ctx.extraHooks(),
                ctx.extraAuditSink(), ctx.credentialOverride(), ctx.stateStore());
        return build(ConfigLoader.load(file), c);
    }

    public static Gateway build(Map<String, Object> raw, BuildContext callerContext) throws Exception {
        ConfigNode root = new ConfigNode("config", raw);
        Map<String, Object> hostSections = new LinkedHashMap<>();
        for (String h : HOST_SECTIONS) {
            root.skip(h);
            if (raw.get(h) != null) {
                hostSections.put(h, raw.get(h));
            }
        }

        // Secrets first: every later name lookup (keyEnv, tokenEnv, passwordEnv, ...) goes through the
        // one selected provider, including the host's own settings via Gateway.secrets().
        final BuildContext ctx = secrets(root.child("secrets"), raw, callerContext);

        ConfigNode gw = root.child("gateway");
        String gatewayName = gw.string("name", "aegis");

        ConfigNode access = root.child("access");
        Set<String> writeGroups = access.stringSet("writeGroups");
        Set<String> destructiveGroups = access.stringSet("destructiveGroups");
        Set<String> approverGroups = access.stringSet("approverGroups");
        Set<String> auditVerifierGroups = access.stringSet("auditVerifierGroups");

        ConfigNode approval = root.child("approval");
        byte[] signingKey = signingKey(approval, ctx);
        Set<ToolClass> approvalFor = approval.has("requiredFor")
                ? classes(approval.strings("requiredFor"), approval) : EnumSet.of(ToolClass.MUTATE, ToolClass.DESTRUCTIVE);
        if (approvalFor.contains(ToolClass.READ)) {
            // Rejected here, with the key path, before any state store or journal is opened.
            throw approval.error("requiredFor", "must not include READ; classify the tool as MUTATE instead");
        }
        long requestTtl = approval.integer("requestTtlSeconds", 1800);
        long tokenTtl = approval.integer("tokenTtlSeconds", 600);
        boolean advertiseControls = approval.bool("advertiseControlArguments", true);

        ConfigNode vrpNode = root.child("vrp");
        boolean vrpEnabled = vrpNode.bool("enabled", true);
        long vrpTtl = vrpNode.integer("ttlSeconds", 900);

        // ---- sources first (their hosts join the egress allow list)
        List<SourceBuild> sources = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ConfigNode s : root.children("sources")) {
            SourceBuild sb = source(s, ctx);
            if (!ids.add(sb.id)) {
                throw new ConfigException(s.path() + ".id duplicate source id '" + sb.id + "'");
            }
            sources.add(sb);
        }
        if (sources.isEmpty()) {
            throw new ConfigException("config.sources must list at least one tool source");
        }

        // ---- egress
        ConfigNode egress = root.child("egress");
        List<String> allowHosts = new ArrayList<>(egress.strings("allowHosts"));
        Set<String> schemes = new LinkedHashSet<>(egress.strings("schemes"));
        if (schemes.isEmpty()) {
            schemes.add("https");
        }
        for (SourceBuild sb : sources) {
            for (URI u : sb.declaredEndpoints) {
                allowHosts.add(u.getHost()); // union of source endpoints and operator list (0.1 rule)
                schemes.add(u.getScheme().toLowerCase(Locale.ROOT));
            }
        }
        EgressGuard egressGuard = new EgressGuard(allowHosts, egress.strings("allowCidrs"), schemes, ctx.resolver());
        boolean checkArgumentUrls = egress.bool("checkArgumentUrls", true);
        // A token endpoint receives every caller's access token, so it must be an allowed destination.
        for (SourceBuild sb : sources) {
            if (sb.exchange != null) {
                String why = egressGuard.check(sb.exchange.tokenEndpoint());
                if (why != null) {
                    throw new ConfigException("config.sources." + sb.id + ".credentials.tokenEndpoint is not allowed by config.egress: " + why);
                }
            }
        }

        // ---- policy
        ConfigNode policy = root.child("policy");
        List<PolicyDecisionPoint> pdps = new ArrayList<>();
        List<RulePolicy.Rule> rules = new ArrayList<>();
        for (ConfigNode r : policy.children("rules")) {
            rules.add(new RulePolicy.Rule(r.requiredString("id"),
                    choice(r, "effect", "ALLOW", RulePolicy.Effect.class),
                    r.stringSet("groups"), r.stringSet("actors"), r.strings("tools"),
                    r.has("classes") ? classes(r.strings("classes"), r) : Set.of(), r.strings("resources")));
        }
        if (!rules.isEmpty()) {
            pdps.add(new RulePolicy(rules));
        }
        ConfigNode opa = policy.child("opa");
        if (opa.has("url")) {
            Map<String, String> h = new LinkedHashMap<>();
            String tokenEnv = opa.string("tokenEnv", null);
            if (tokenEnv != null) {
                h.put("Authorization", "Bearer " + requireEnv(ctx, tokenEnv, opa.path() + ".tokenEnv"));
            }
            // The OPA call carries governance data off-host on every decision, so it passes the same
            // URL shape and egress checks as the remote hook and the OTLP exporter, once, at startup.
            URI opaUrl = endpoint(opa, "url");
            String why = egressGuard.check(opaUrl);
            if (why != null) {
                throw opa.error("url", "is not allowed by config.egress: " + why);
            }
            pdps.add(new OpaPolicy(opaUrl, Duration.ofMillis(opa.integer("timeoutMs", 2000)),
                    opa.bool("includeArguments", false), h));
        }
        if (pdps.isEmpty()) {
            throw new ConfigException("config.policy needs at least one rule or an opa endpoint (default is deny-all)");
        }
        PolicyDecisionPoint pdp = pdps.size() == 1 ? pdps.get(0) : RulePolicy.allOf(pdps);

        List<ScopeRule> scopes = new ArrayList<>();
        for (ConfigNode s : root.children("scopes")) {
            scopes.add(new ScopeRule(s.stringSet("groups"), s.strings("resources")));
        }

        ConfigNode rl = root.child("rateLimits");
        ConfigNode costs = rl.child("costs");
        RateLimiter rate = new RateLimiter(rl.integer("perCallerPerMinute", 0), rl.integer("perCallerPerToolPerMinute", 0),
                rl.integer("budgetPerHour", 0), costs.integer("READ", 1), costs.integer("MUTATE", 5), costs.integer("DESTRUCTIVE", 20));

        ConfigNode br = root.child("breaker");
        CircuitBreakers breakers = new CircuitBreakers((int) br.integer("failureThreshold", 5),
                br.integer("openSeconds", 30) * 1000, ctx.clock());

        ConfigNode scr = root.child("screening");
        List<String> phrases = scr.has("phrases") ? scr.strings("phrases") : InjectionScreen.DEFAULT_PHRASES;
        InjectionScreen argScreen = new InjectionScreen(controlMode(scr, "mode"), phrases,
                scr.bool("decodeBase64", true));
        InjectionScreen defScreen = new InjectionScreen(controlMode(scr, "toolDefinitions"), phrases, true);
        // Resources and prompts enter the model's context directly: screened like tool definitions.
        InjectionScreen contentScreen = new InjectionScreen(controlMode(scr, "content"), phrases, true);

        ConfigNode out = root.child("output");
        List<String> redact = new ArrayList<>();
        if (out.bool("useDefaultRedactions", true)) {
            redact.addAll(OutputGuard.DEFAULT_REDACTIONS);
        }
        redact.addAll(out.strings("redact"));
        // Binary tool output (image/audio data, blobs) is opaque to redaction and screening: off unless allowed.
        OutputGuard outputGuard = new OutputGuard((int) out.integer("maxBytes", 1 << 20), redact,
                out.bool("allowBinary", false));

        List<HookBinding> hooks = new ArrayList<>();
        for (ConfigNode h : root.children("hooks")) {
            String type = h.requiredString("type");
            GovernanceHook hook;
            if (BUILT_IN_HOOKS.contains(type)) {
                if (provider(GovernanceHookFactory.class, type, GovernanceHookFactory::type).isPresent()) {
                    // Refuse rather than silently prefer one of them: the operator would have no way to
                    // tell which control is actually running.
                    throw new ConfigException(h.path() + ".type '" + type
                            + "' is built in, but a GovernanceHookFactory on the classpath also claims it; rename that factory");
                }
                ConfigNode hs = h.child("settings");
                hook = REMOTE_HOOK.equals(type) ? remoteHook(ctx, hs, egressGuard) : sqlReadOnlyHook(hs);
            } else {
                GovernanceHookFactory f = provider(GovernanceHookFactory.class, type, GovernanceHookFactory::type)
                        .orElseThrow(() -> new ConfigException(h.path() + ".type unknown hook type '" + type + "'"));
                hook = f.create(h.child("settings"));
                if (hook == null) {
                    throw new ConfigException(h.path() + ".type hook factory '" + type + "' produced no hook");
                }
            }
            Set<GovernanceHook.Operation> operations = EnumSet.noneOf(GovernanceHook.Operation.class);
            for (String o : h.strings("operations")) {
                try {
                    operations.add(GovernanceHook.Operation.valueOf(o.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw h.error("operations", "must name TOOL_CALL, RESOURCE_READ, PROMPT_GET or COMPLETION");
                }
            }
            ControlMode hookMode = controlMode(h, "mode");
            hooks.add(new HookBinding(hook, hookMode, operations, h.strings("methods"), h.stringSet("groups"),
                    h.stringSet("sources")));
        }
        // Host-supplied hooks (a Knox provider, an embedding application) always enforce, for every
        // operation: a host that compiled a control in did not ask for it to be advisory.
        for (GovernanceHook extra : ctx.extraHooks()) {
            hooks.add(HookBinding.enforceAll(extra));
        }

        ConfigNode audit = root.child("audit");
        String auditFile = audit.requiredString("file");
        boolean auditFsync = audit.bool("fsync", true);
        boolean auditArgs = audit.bool("includeArguments", false);
        // Head export: absent means none. The file belongs on storage the journal writer cannot rewrite.
        AuditCheckpointPolicy auditCheckpoints = null;
        if (audit.has("checkpoint")) {
            ConfigNode cpNode = audit.child("checkpoint");
            String cpFile = cpNode.requiredString("file");
            long everyRecords = cpNode.integer("everyRecords", 100);
            long everySeconds = cpNode.integer("everySeconds", 60);
            if (everyRecords < 1 || everyRecords > 1_000_000) {
                throw cpNode.error("everyRecords", "must be between 1 and 1000000");
            }
            if (everySeconds < 0 || everySeconds > 86_400) {
                throw cpNode.error("everySeconds", "must be between 0 (no timer) and 86400");
            }
            Path cpPath = ctx.baseDir().resolve(cpFile).toAbsolutePath().normalize();
            if (cpPath.equals(ctx.baseDir().resolve(auditFile).toAbsolutePath().normalize())) {
                throw cpNode.error("file", "must not be the audit journal itself");
            }
            auditCheckpoints = new AuditCheckpointPolicy(cpPath, everyRecords, Duration.ofSeconds(everySeconds), ctx.clock());
        }

        ConfigNode cat = root.child("catalog");
        String pinFile = cat.string("pinFile", null);
        Map<String, String> pins = new TreeMap<>(cat.stringMap("pins"));
        if (cat.has("manifest")) {
            // Signed pins are authoritative like configuration pins: no run-time acceptance overrides them.
            ConfigNode mf = cat.child("manifest");
            Path manifestFile = ctx.baseDir().resolve(mf.requiredString("file"));
            Path signatureFile = ctx.baseDir().resolve(mf.string("signatureFile", mf.requiredString("file") + ".sig"));
            Path publicKeyFile = ctx.baseDir().resolve(mf.requiredString("publicKeyFile"));
            Map<String, String> signed;
            try {
                signed = SignedCatalogManifest.load(manifestFile, signatureFile, publicKeyFile, gatewayName);
            } catch (java.io.IOException e) {
                throw new ConfigException(mf.path() + ": " + e.getMessage(), e);
            }
            for (Map.Entry<String, String> e : signed.entrySet()) {
                String inline = pins.putIfAbsent(e.getKey(), e.getValue());
                if (inline != null && !inline.equals(e.getValue())) {
                    throw cat.error("pins", "pins '" + e.getKey() + "' differently from the signed manifest; remove one");
                }
            }
            LOG.log(System.Logger.Level.INFO, "catalog: " + signed.size() + " signed pins loaded from " + manifestFile);
        }
        CatalogIntegrity integrity = new CatalogIntegrity(
                choice(cat, "mode", "TOFU", CatalogIntegrity.Mode.class),
                pins, pinFile == null ? null : ctx.baseDir().resolve(pinFile));
        boolean acceptNew = cat.bool("acceptNewToolsAfterStart", false);
        boolean failOnSourceError = cat.bool("failOnSourceError", true);
        long refreshSeconds = cat.integer("refreshSeconds", 0);

        ConfigNode server = root.child("server");
        ServerOptions serverOptions;
        try {
            serverOptions = new ServerOptions(server.string("name", gatewayName), server.string("version", null),
                    new LinkedHashSet<>(server.strings("versions")), server.bool("legacySessions", true),
                    server.stringSet("allowedOrigins"), server.integer("toolsListTtlMs", 0), server.string("instructions", null),
                    JsonLimits.DEFAULT.withMaxBytes((int) server.integer("maxRequestBytes", 1 << 20)));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(server.path() + ": " + e.getMessage(), e);
        }
        long sessionIdle = server.integer("sessionIdleSeconds", 1800) * 1000;
        int maxSessions = (int) server.integer("maxSessions", 10_000);

        DecisionMetrics metrics = root.child("metrics").bool("enabled", true) ? new DecisionMetrics() : null;

        // Tracing is off unless configured: an absent section must never start a background exporter or
        // open a connection to anything.
        // The exporter starts a thread, so it is only constructed after the whole document validated.
        ConfigNode otlp = root.child("tracing").child("otlp");
        java.util.function.Supplier<OtlpSpanExporter> tracerFactory = null;
        if (otlp.has("endpoint")) {
            URI otlpUrl;
            try {
                otlpUrl = new URI(otlp.requiredString("endpoint"));
            } catch (java.net.URISyntaxException e) {
                throw otlp.error("endpoint", "must be a URI, for example http://collector:4318/v1/traces");
            }
            String why = egressGuard.check(otlpUrl);
            if (why != null) {
                throw otlp.error("endpoint", "is not allowed by config.egress: " + why);
            }
            Map<String, String> otlpHeaders = new LinkedHashMap<>(otlp.stringMap("headers"));
            String otlpTokenEnv = otlp.string("tokenEnv", null);
            if (otlpTokenEnv != null) {
                otlpHeaders.put("Authorization", "Bearer " + requireEnv(ctx, otlpTokenEnv, otlp.path() + ".tokenEnv"));
            }
            Duration otlpTimeout = Duration.ofMillis(otlp.integer("timeoutMs", 5000));
            String otlpService = otlp.string("serviceName", gatewayName);
            int otlpQueue = (int) otlp.integer("maxQueue", 2048);
            int otlpBatch = (int) otlp.integer("batchSize", 128);
            long otlpFlush = otlp.integer("flushMs", 2000);
            tracerFactory = () -> new OtlpSpanExporter(otlpUrl, otlpTimeout, otlpHeaders, otlpService, otlpQueue,
                    otlpBatch, otlpFlush);
        }

        // State is selected once for the whole gateway, after every other section has been read, so a
        // configuration error elsewhere never leaves a durable store open or locked. A BuildContext
        // override wins so Knox or an embedding host can provide its own shared store. The default stays
        // in-memory and one-replica; durable providers are optional ServiceLoader modules selected
        // explicitly by state.type.
        ConfigNode stateNode = root.child("state");
        StateStore state;
        AutoCloseable stateResource = null;
        if (ctx.stateStore() != null) {
            stateNode.skip("type");
            stateNode.skip("maxEntries");
            stateNode.skip("settings");
            state = ctx.stateStore();
        } else {
            String stateType = stateNode.string("type", "memory").toLowerCase(Locale.ROOT);
            int maxEntries = (int) stateNode.integer("maxEntries", 1_000_000);
            ConfigNode stateSettings = stateNode.child("settings");
            if ("memory".equals(stateType)) {
                stateSettings.finish();
                state = new InMemoryStateStore(ctx.clock(), maxEntries);
            } else {
                StateStoreFactory factory = provider(StateStoreFactory.class, stateType, StateStoreFactory::type)
                        .orElseThrow(() -> new ConfigException(stateNode.path() + ".type unknown state provider '" + stateType
                                + "' (built-in: memory; add a StateStoreFactory provider jar for durable state)"));
                try {
                    state = factory.create(stateSettings, ctx);
                } catch (ConfigException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ConfigException(stateNode.path() + ".settings could not open state provider '" + stateType
                            + "': " + e.getMessage(), e);
                }
                if (state == null) {
                    throw new ConfigException(stateNode.path() + ".type provider '" + stateType + "' produced no state store");
                }
                stateResource = state instanceof AutoCloseable c ? c : null;
            }
        }

        try {
            root.finish(); // unknown keys anywhere are fatal
        } catch (RuntimeException e) {
            closeStateAfterFailedBuild(stateResource, e);
            throw e;
        }

        // Opened only after the whole document validated, so a bad config leaves no open journal.
        HashChainFileAuditSink auditJournal;
        try {
            auditJournal = new HashChainFileAuditSink(ctx.baseDir().resolve(auditFile), auditFsync, auditCheckpoints);
        } catch (java.io.IOException | RuntimeException e) {
            closeStateAfterFailedBuild(stateResource, new ConfigException("the audit journal could not be opened", e));
            throw e;
        }
        OtlpSpanExporter tracer = tracerFactory == null ? null : tracerFactory.get();
        AuditSink sink = auditJournal;
        if (ctx.extraAuditSink() != null) {
            sink = tee(sink, ctx.extraAuditSink());
        }

        if (!approvalFor.isEmpty() && (signingKey == null || approverGroups.isEmpty())) {
            LOG.log(System.Logger.Level.WARNING, "approval is required for " + approvalFor
                    + " but the signing key or approver groups are missing: those calls will be denied (fail-closed)");
        }

        CredentialProvider creds = ctx.credentialOverride();
        if (creds == null) {
            Map<String, StaticCredentialProvider.Spec> specs = new LinkedHashMap<>();
            for (SourceBuild sb : sources) {
                specs.put(sb.id, sb.credentials);
            }
            StaticCredentialProvider statics = new StaticCredentialProvider(specs);
            Map<String, io.github.vaquarkhan.aegis.config.credentials.TokenExchangeCredentials> exchanges = new LinkedHashMap<>();
            for (SourceBuild sb : sources) {
                if (sb.exchange != null) {
                    exchanges.put(sb.id, sb.exchange);
                }
            }
            creds = exchanges.isEmpty() ? statics
                    : new io.github.vaquarkhan.aegis.config.credentials.SourceCredentialProvider(statics, exchanges);
        }
        List<ToolSource> toolSources = new ArrayList<>();
        Map<String, SourceBuild> byId = new LinkedHashMap<>();
        for (SourceBuild sb : sources) {
            toolSources.add(sb.source);
            byId.put(sb.id, sb);
        }
        java.util.function.Consumer<Map<String, Object>> catalogEvents = e -> {
            Map<String, Object> rec = new LinkedHashMap<>(e);
            rec.put("ts", ctx.clock().instant().toString());
            rec.put("gateway", gatewayName);
            LOG.log(System.Logger.Level.INFO, "catalog: " + rec);
        };
        ToolCatalog catalog = new ToolCatalog(toolSources, (sourceId, st) -> byId.get(sourceId).bind(st),
                integrity, defScreen, failOnSourceError, acceptNew, catalogEvents);

        // Approval decisions go to the same hash-chained journal as the calls they authorise, so one
        // offline-verifiable file shows who asked, who approved, and what then ran.
        ApprovalService approvals = new ApprovalService(signingKey, state, approverGroups,
                Duration.ofSeconds(requestTtl), Duration.ofSeconds(tokenTtl), ctx.clock(), sink, gatewayName);
        VrpService vrp = new VrpService(signingKey, state, Duration.ofSeconds(vrpTtl), ctx.clock());
        GovernanceSettings settings = new GovernanceSettings(gatewayName, writeGroups, destructiveGroups, approvalFor,
                vrpEnabled, scopes, rate, argScreen, outputGuard, egressGuard, hooks, advertiseControls, auditArgs,
                listeners(metrics, tracer));
        GovernanceEngine engine = new GovernanceEngine(settings, catalog, pdp, approvals, vrp, state, sink, creds,
                breakers, ctx.clock(), checkArgumentUrls);
        List<ContentGovernor.Binding> contentBindings = new ArrayList<>();
        for (SourceBuild sb : sources) {
            if (sb.content != null) {
                contentBindings.add(new ContentGovernor.Binding(sb.contentSource, sb.content, sb.discovery));
            }
        }
        ContentGovernor content = contentBindings.isEmpty() ? null
                : new ContentGovernor(settings, contentBindings, pdp, state, sink, creds, breakers, integrity, contentScreen,
                        new ContentGovernor.Options(acceptNew, failOnSourceError, checkArgumentUrls), ctx.clock(), catalogEvents);
        try {
            catalog.load();
            if (content != null) {
                content.load();
            }
        } catch (Exception e) {
            engine.close();
            if (tracer != null) {
                tracer.close();
            }
            closeStateAfterFailedBuild(stateResource, new ConfigException("gateway startup failed", e));
            throw e;
        }
        McpServerHandler handler = new McpServerHandler(engine, content, serverOptions,
                new SessionRegistry(ctx.clock(), sessionIdle, maxSessions));
        AdminApi admin = new AdminApi(engine, content, approverGroups, auditVerifierGroups,
                () -> auditJournal.verify().ok(), serverOptions.allowedOrigins());
        ScheduledExecutorService refresher = null;
        if (refreshSeconds > 0) {
            refresher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aegis-catalog-refresh");
                t.setDaemon(true);
                return t;
            });
            refresher.scheduleWithFixedDelay(() -> {
                try {
                    catalog.refresh();
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.ERROR, "catalog refresh failed", e);
                }
                if (content != null) {
                    try {
                        content.refresh();
                    } catch (Exception e) {
                        LOG.log(System.Logger.Level.ERROR, "prompt catalog refresh failed", e);
                    }
                }
            }, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
        }
        return new Gateway(engine, handler, admin, hostSections, refresher, metrics, tracer, stateResource, ctx.env());
    }

    /**
     * {@code secrets: {source: env|file|vault|<provider>, settings: {...}}}. Absent or {@code env} keeps
     * the process environment. The Vault token itself always comes from the process environment or a
     * file, never from the provider it unlocks.
     */
    private static BuildContext secrets(ConfigNode s, Map<String, Object> raw, BuildContext ctx) throws Exception {
        String source = s.string("source", "env").trim().toLowerCase(Locale.ROOT);
        ConfigNode settings = s.child("settings");
        java.util.function.Function<String, String> resolver;
        switch (source) {
            case "env" -> {
                return ctx;
            }
            case "file" -> resolver = new FileSecretProvider(ctx.baseDir().resolve(settings.requiredString("directory")));
            case "vault" -> {
                URI address = endpoint(settings, "address");
                String why = preliminaryEgress(raw, ctx).check(address);
                if (why != null) {
                    throw settings.error("address", "is not allowed by config.egress: " + why);
                }
                String tokenEnv = settings.string("tokenEnv", null);
                String tokenFile = settings.string("tokenFile", null);
                if ((tokenEnv == null) == (tokenFile == null)) {
                    throw new ConfigException(settings.path() + " needs exactly one of tokenEnv or tokenFile");
                }
                String token = tokenEnv != null ? requireEnv(ctx, tokenEnv, settings.path() + ".tokenEnv")
                        : Files.readString(ctx.baseDir().resolve(tokenFile), StandardCharsets.UTF_8).strip();
                String ca = settings.string("caFile", null);
                VaultKvSecretProvider vault;
                try {
                    vault = new VaultKvSecretProvider(address, settings.string("mount", "secret"),
                            settings.requiredString("path"), token, settings.string("namespace", null),
                            ca == null ? null : ctx.baseDir().resolve(ca), Duration.ofMillis(settings.integer("timeoutMs", 5000)));
                } catch (java.io.IOException e) {
                    // Fail closed: no gateway starts without the secrets it was configured to read.
                    throw new ConfigException(settings.path() + ": could not read secrets from " + address
                            + " (" + e.getClass().getSimpleName() + ")", e);
                }
                LOG.log(System.Logger.Level.INFO, "secrets: " + vault.names().size() + " keys read from " + address.getHost());
                resolver = vault;
            }
            default -> {
                SecretProviderFactory factory = provider(SecretProviderFactory.class, source, SecretProviderFactory::type)
                        .orElseThrow(() -> new ConfigException(s.path() + ".source unknown secret provider '" + source
                                + "' (built in: env, file, vault; add a SecretProviderFactory provider jar for others)"));
                EgressGuard guard = preliminaryEgress(raw, ctx);
                resolver = factory.create(settings, ctx, guard::check);
                if (resolver == null) {
                    throw new ConfigException(s.path() + ".source provider '" + source + "' produced no resolver");
                }
            }
        }
        return new BuildContext(resolver, ctx.baseDir(), ctx.clock(), ctx.resolver(), ctx.extraHooks(),
                ctx.extraAuditSink(), ctx.credentialOverride(), ctx.stateStore());
    }

    /**
     * The operator's own egress list (hosts, CIDRs, schemes; https by default) without source endpoints:
     * a secret store must be allowed explicitly, it is not implied by any source.
     */
    @SuppressWarnings("unchecked")
    private static EgressGuard preliminaryEgress(Map<String, Object> raw, BuildContext ctx) {
        Object e = raw.get("egress");
        ConfigNode egress = new ConfigNode("config.egress", e instanceof Map ? (Map<String, Object>) e : Map.of());
        Set<String> schemes = new LinkedHashSet<>(egress.strings("schemes"));
        if (schemes.isEmpty()) {
            schemes.add("https");
        }
        return new EgressGuard(egress.strings("allowHosts"), egress.strings("allowCidrs"), schemes, ctx.resolver());
    }

    private static void closeStateAfterFailedBuild(AutoCloseable stateResource, RuntimeException original) {
        if (stateResource == null) {
            return;
        }
        try {
            stateResource.close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    // ================================================================= sources

    /** Per-tool governance settings from configuration. */
    private record ToolSettings(ToolClass toolClass, List<ResourceRule> resources, String dryRunMode, String dryRunArgument,
                                String previewTool, Set<String> allowedGroups, long timeoutMillis) {}

    private static final class SourceBuild {
        String id;
        String namePrefix;
        ToolClass defaultClass;
        boolean hideUnlisted;
        boolean quarantined;
        String evidence;
        long timeoutMillis;
        StaticCredentialProvider.Spec credentials;
        /** Per-caller credentials by RFC 8693 exchange; null when the source uses a static credential. */
        io.github.vaquarkhan.aegis.config.credentials.TokenExchangeCredentials exchange;
        ToolSource source;
        List<URI> declaredEndpoints = new ArrayList<>();
        Map<String, ToolSettings> tools = new LinkedHashMap<>();
        Map<String, ToolExecutor> executorsByName = new LinkedHashMap<>();
        ContentSettings content;
        ContentSource contentSource;
        java.util.function.Supplier<Map<String, String>> discovery;

        /** Filled on demand by {@link #executorsOfSource()}; see that method for why it is lazy. */
        private Map<String, ToolExecutor> sourceExecutors;

        /**
         * This source's own tools by name, for resolving a {@code dryRun.previewTool} on a source that
         * builds one executor per tool but does not fill {@link #executorsByName} while the config is
         * parsed (every source type except {@code http}).
         *
         * <p>Lazy, and consulted last, because for an {@code mcp} source discovery is a network round
         * trip: those sources dispatch by upstream name and are answered before this is ever reached.
         */
        Map<String, ToolExecutor> executorsOfSource() {
            if (sourceExecutors == null) {
                Map<String, ToolExecutor> found = new LinkedHashMap<>();
                try {
                    for (ToolSource.SourcedTool st : source.discover()) {
                        found.put(st.descriptor().name(), st.executor());
                    }
                } catch (Exception e) {
                    throw new ConfigException("source " + id + ": cannot list its tools to resolve a"
                            + " dryRun.previewTool: " + e.getMessage(), e);
                }
                sourceExecutors = found;
            }
            return sourceExecutors;
        }

        Optional<GovernedTool> bind(ToolSource.SourcedTool st) {
            if (quarantined) {
                // Source-wide quarantine is deliberately stronger than individual tool pinning: an
                // operator who has not accepted the source's evidence must not get a callable old tool
                // merely because it was discovered before the source was quarantined.
                return Optional.empty();
            }
            String upstream = st.descriptor().name();
            ToolSettings s = tools.get(upstream);
            if (s == null && hideUnlisted) {
                return Optional.empty();
            }
            ToolClass cls = s != null && s.toolClass() != null ? s.toolClass() : defaultClass;
            // Upstream hints may only RAISE authority (0.1 overlays could downgrade DESTRUCTIVE to READ).
            Map<String, Object> ann = st.descriptor().annotations();
            if (ann != null && Boolean.TRUE.equals(ann.get("destructiveHint")) && !Boolean.TRUE.equals(ann.get("readOnlyHint"))) {
                cls = ToolClass.max(cls, ToolClass.DESTRUCTIVE);
            }
            DryRunSpec dry = DryRunSpec.GATEWAY;
            if (s != null && s.dryRunMode() != null) {
                switch (s.dryRunMode()) {
                    case "GATEWAY" -> dry = DryRunSpec.GATEWAY;
                    case "ARGUMENT" -> {
                        // The preview flag must be something the backend actually receives and understands;
                        // otherwise a "dry run" would be the real call with an ignored extra argument.
                        java.util.Map<String, Object> props = io.github.vaquarkhan.aegis.engine.json.JsonValues.object(
                                st.descriptor().inputSchema(), "properties");
                        if (props == null || !props.containsKey(s.dryRunArgument())) {
                            throw new ConfigException("source " + id + " tool " + upstream + ": dryRun.argument '"
                                    + s.dryRunArgument() + "' is not a property of the tool's input schema");
                        }
                        dry = DryRunSpec.argument(s.dryRunArgument());
                    }
                    case "TOOL" -> {
                        if (s.previewTool().equals(upstream)) {
                            throw new ConfigException("source " + id + " tool " + upstream + ": dryRun.previewTool must be a different tool");
                        }
                        // A validate phase must not be able to change anything, or "dry run" is just the
                        // operation under a friendlier name. So the preview tool has to be listed in this
                        // source's tools: section and classified READ there, explicitly: relying on the
                        // source default would make the guarantee depend on a value written elsewhere.
                        ToolSettings ps = tools.get(s.previewTool());
                        if (ps == null || ps.toolClass() == null) {
                            throw new ConfigException("source " + id + " tool " + upstream + ": dryRun.previewTool '"
                                    + s.previewTool() + "' must be listed in this source's tools: section with an"
                                    + " explicit class, because a preview has to be READ");
                        }
                        if (ps.toolClass() != ToolClass.READ) {
                            throw new ConfigException("source " + id + " tool " + upstream + ": dryRun.previewTool '"
                                    + s.previewTool() + "' is " + ps.toolClass() + "; a preview must be classified READ"
                                    + " so that validating cannot change anything");
                        }
                        // http sources: the preview tool's own executor, recorded while the config was
                        // parsed. Sources that dispatch by upstream name (mcp, spark-connect) can reuse
                        // their own executor, because the name in the ExecutionContext is what selects
                        // the operation. Everything else resolves against the source's own tool list, so
                        // the preview is genuinely the other tool's executor and never this one.
                        ToolExecutor preview = executorsByName.get(s.previewTool());
                        if (preview == null && !st.executor().dispatchesByUpstreamName()) {
                            preview = executorsOfSource().get(s.previewTool());
                            if (preview == null) {
                                throw new ConfigException("source " + id + " tool " + upstream + ": dryRun.previewTool '"
                                        + s.previewTool() + "' is not a tool of this source");
                            }
                        }
                        if (preview == null) {
                            preview = st.executor();
                        }
                        dry = DryRunSpec.tool(preview, s.previewTool());
                    }
                    default -> throw new ConfigException("unknown dry-run mode " + s.dryRunMode());
                }
            }
            return Optional.of(new GovernedTool(namePrefix + upstream, id, st.descriptor(), cls,
                    s == null ? List.of() : s.resources(), dry, s == null ? Set.of() : s.allowedGroups(), st.executor(),
                    s != null && s.timeoutMillis() > 0 ? s.timeoutMillis() : timeoutMillis));
        }
    }

    private static SourceBuild source(ConfigNode s, BuildContext ctx) throws Exception {
        SourceBuild sb = new SourceBuild();
        sb.id = s.requiredString("id");
        if (!ID.matcher(sb.id).matches()) {
            throw s.error("id", "must match " + ID.pattern());
        }
        String type = s.requiredString("type").toLowerCase(Locale.ROOT);
        sb.namePrefix = s.string("namePrefix", "");
        sb.timeoutMillis = s.integer("timeoutMs", 30_000);
        // Only the two documented values are accepted: a typo must not silently fall back to EXPOSE.
        String unlisted = s.string("unlistedTools", "EXPOSE").trim().toUpperCase(Locale.ROOT);
        if (!"EXPOSE".equals(unlisted) && !"HIDE".equals(unlisted)) {
            throw s.error("unlistedTools", "must be EXPOSE or HIDE");
        }
        sb.hideUnlisted = "HIDE".equals(unlisted);
        try {
            sb.defaultClass = ToolClass.parse(s.string("defaultClass", "DESTRUCTIVE"));
        } catch (IllegalArgumentException e) {
            throw s.error("defaultClass", "must be READ, MUTATE or DESTRUCTIVE");
        }
        ConfigNode trust = s.child("trust");
        String trustState = trust.string("state", "ACTIVE").toUpperCase(Locale.ROOT);
        sb.evidence = trust.string("evidence", null);
        if (!"ACTIVE".equals(trustState) && !"QUARANTINED".equals(trustState)) {
            throw trust.error("state", "must be ACTIVE or QUARANTINED");
        }
        if ("QUARANTINED".equals(trustState) && (sb.evidence == null || sb.evidence.isBlank())) {
            throw trust.error("evidence", "is required when a source is QUARANTINED");
        }
        sb.quarantined = "QUARANTINED".equals(trustState);
        ConfigNode credentialNode = s.child("credentials");
        if ("token-exchange".equalsIgnoreCase(credentialNode.string("type", "none").trim())) {
            // Calls carry an exchanged per-caller token; discovery (tools/list) has no caller, so it uses
            // the separate discovery credential, which is the gateway's own.
            sb.exchange = tokenExchange(credentialNode, sb.id, ctx);
            sb.credentials = credentials(credentialNode.child("discovery"), ctx);
        } else {
            sb.credentials = credentials(credentialNode, ctx);
        }
        // Discovery (tools/list) uses the source's own service credentials, never a caller token.
        java.util.function.Supplier<Map<String, String>> discovery = () -> StaticCredentialProvider.headersFor(sb.credentials);
        switch (type) {
            case "mcp" -> {
                for (Map.Entry<String, ConfigNode> e : s.childMap("tools").entrySet()) {
                    sb.tools.put(e.getKey(), toolSettings(e.getValue(), false));
                }
                String transport = s.string("transport", "http").toLowerCase(Locale.ROOT);
                HttpMcpClient.Era era = choice(s, "era", "AUTO", HttpMcpClient.Era.class);
                String legacyVersion = s.string("legacyVersion", null);
                int maxBytes = (int) s.integer("maxResponseBytes", 4 << 20);
                McpClient client;
                if ("http".equals(transport)) {
                    URI url = endpoint(s, "url");
                    sb.declaredEndpoints.add(url);
                    client = new HttpMcpClient(url, era, legacyVersion, maxBytes, Duration.ofSeconds(5));
                } else if ("stdio".equals(transport)) {
                    List<String> cmd = s.strings("command");
                    if (cmd.isEmpty()) {
                        throw s.error("command", "is required for transport stdio");
                    }
                    Map<String, String> env = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : s.stringMap("envFrom").entrySet()) {
                        env.put(e.getKey(), requireEnv(ctx, e.getValue(), s.path() + ".envFrom." + e.getKey()));
                    }
                    env.putAll(s.stringMap("env"));
                    String wd = s.string("workingDir", null);
                    client = new StdioMcpClient(cmd, env, wd == null ? null : ctx.baseDir().resolve(wd), era, legacyVersion, maxBytes);
                } else {
                    throw s.error("transport", "must be http or stdio");
                }
                sb.source = new McpProxyToolSource(sb.id, client, discovery, sb.timeoutMillis);
                sb.content = contentSettings(s, sb);
                if (sb.content != null) {
                    sb.contentSource = new McpContentSource(sb.id, client);
                    sb.discovery = discovery;
                }
            }
            case "http" -> {
                URI baseUrl = endpoint(s, "baseUrl");
                sb.declaredEndpoints.add(baseUrl);
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER).build();
                List<ToolSource.SourcedTool> tools = new ArrayList<>();
                Map<String, String> templates = new LinkedHashMap<>();
                for (ConfigNode t : s.children("tools")) {
                    String name = t.requiredString("name");
                    if (sb.tools.containsKey(name)) {
                        throw t.error("name", "duplicate tool '" + name + "'");
                    }
                    Object schemaRaw = t.raw("inputSchema");
                    Map<String, Object> schema = schemaRaw == null ? Map.of("type", "object") : JsonValues.asObject(schemaRaw);
                    if (schema == null) {
                        throw t.error("inputSchema", "must be an object");
                    }
                    ToolDescriptor d = new ToolDescriptor(name, t.string("title", null), t.requiredString("description"),
                            schema, JsonValues.asObject(t.raw("outputSchema")), null);
                    ConfigNode req = t.child("request");
                    String path = req.string("path", "");
                    List<Object> required = JsonValues.asList(schema.get("required"));
                    for (String ph : HttpToolExecutor.pathPlaceholders(path)) {
                        if (required == null || !required.contains(ph)) {
                            throw req.error("path", "placeholder {" + ph + "} must be listed in inputSchema.required");
                        }
                    }
                    ConfigNode resp = t.child("response");
                    String pointer = resp.string("pointer", null);
                    HttpToolSpec.ResponseStatusCheck statusCheck = null;
                    if (resp.has("status")) {
                        ConfigNode statusCheckNode = resp.child("status");
                        String statusPointer = statusCheckNode.requiredString("pointer");
                        if (!statusCheckNode.has("equals")) {
                            throw statusCheckNode.error("equals", "is required");
                        }
                        statusCheck = new HttpToolSpec.ResponseStatusCheck(JsonPointer.parse(statusPointer),
                                statusCheckNode.integer("equals", 0));
                    }
                    HttpToolSpec spec = new HttpToolSpec(req.string("method", "GET").toUpperCase(Locale.ROOT), path,
                            req.stringMap("query"), req.raw("body"), req.stringMap("headers"),
                            pointer == null ? null : JsonPointer.parse(pointer), statusCheck,
                            (int) resp.integer("maxBytes", 1 << 20));
                    HttpToolExecutor exec = new HttpToolExecutor(baseUrl, spec, http);
                    String bodyText = spec.body() == null ? "" : io.github.vaquarkhan.aegis.engine.json.Json.write(spec.body());
                    templates.put(name, path + " " + String.join(" ", spec.query().values())
                            + " " + (bodyText.contains("{*}") ? "{" + "*ALL*" + "}" : bodyText));
                    ToolSettings ts = toolSettings(t, true);
                    sb.tools.put(name, ts);
                    sb.executorsByName.put(name, exec);
                    tools.add(new ToolSource.SourcedTool(d, exec));
                }
                if (tools.isEmpty()) {
                    throw s.error("tools", "must declare at least one tool for an http source");
                }
                for (Map.Entry<String, ToolSettings> te : sb.tools.entrySet()) {
                    ToolSettings ts = te.getValue();
                    if ("TOOL".equals(ts.dryRunMode()) && !sb.executorsByName.containsKey(ts.previewTool())) {
                        throw s.error("tools", "dryRun.previewTool '" + ts.previewTool() + "' is not a tool of this source");
                    }
                    String tpl = templates.get(te.getKey());
                    if ("ARGUMENT".equals(ts.dryRunMode())
                            && !tpl.contains("{" + ts.dryRunArgument() + "}") && !tpl.contains("{*ALL*}")) {
                        throw s.error("tools", "tool " + te.getKey() + ": dryRun.argument '" + ts.dryRunArgument()
                                + "' is not referenced by the request template, so the backend would never see it");
                    }
                }
                sb.source = new HttpToolSource(sb.id, tools);
            }
            default -> {
                for (Map.Entry<String, ConfigNode> e : s.childMap("tools").entrySet()) {
                    sb.tools.put(e.getKey(), toolSettings(e.getValue(), false));
                }
                ToolSourceFactory f = provider(ToolSourceFactory.class, type, ToolSourceFactory::type)
                        .orElseThrow(() -> new ConfigException(s.path() + ".type unknown source type '" + type
                                + "' (built-in: mcp, http; others need a ToolSourceFactory on the classpath)"));
                sb.source = f.create(sb.id, s.child("settings"), ctx);
            }
        }
        return sb;
    }

    /**
     * Resources, prompts and completion of an mcp source. Absent sections mean OFF, so an existing
     * MCP server's resources and prompts are never exposed unless the operator opts in.
     */
    private static ContentSettings contentSettings(ConfigNode s, SourceBuild sb) {
        boolean resources = s.has("resources");
        boolean prompts = s.has("prompts");
        boolean completions = s.bool("completions", false);
        if (!resources && !prompts) {
            if (completions) {
                throw s.error("completions", "needs a resources or prompts section on the same source");
            }
            return null;
        }
        ConfigNode r = s.child("resources");
        ConfigNode p = s.child("prompts");
        List<String> uriPatterns = r.strings("uriPatterns");
        if (resources && uriPatterns.isEmpty()) {
            throw r.error("uriPatterns", "must list at least one resource URI glob (for example \"file:///data/*\")");
        }
        for (String u : uriPatterns) {
            if (u.isBlank() || "*".equals(u.trim())) {
                throw r.error("uriPatterns", "must be specific URI globs; \"*\" would expose every resource of the server");
            }
        }
        return new ContentSettings(sb.id, sb.namePrefix, resources, uriPatterns, r.stringSet("allowedGroups"),
                r.bool("allowBinary", false), prompts, p.strings("names"), p.stringSet("allowedGroups"),
                p.bool("allowBinary", false), completions, sb.timeoutMillis);
    }

    private static ToolSettings toolSettings(ConfigNode t, boolean classRequired) {
        String cls = classRequired ? t.requiredString("class") : t.string("class", null);
        List<ResourceRule> res = new ArrayList<>();
        for (ConfigNode r : t.children("resources")) {
            List<JsonPointer> ptrs = new ArrayList<>();
            if (r.has("pointer")) {
                ptrs.add(JsonPointer.parse(r.requiredString("pointer")));
            }
            for (String p : r.strings("pointers")) {
                ptrs.add(JsonPointer.parse(p));
            }
            res.add(new ResourceRule(r.requiredString("type"), ptrs, r.string("join", "."), r.bool("required", true)));
        }
        ConfigNode dry = t.child("dryRun");
        String mode = dry.has("mode") ? dry.requiredString("mode").toUpperCase(Locale.ROOT) : null;
        String arg = dry.string("argument", null);
        String preview = dry.string("previewTool", null);
        if ("ARGUMENT".equals(mode) && arg == null) {
            throw dry.error("argument", "is required for mode ARGUMENT");
        }
        if ("TOOL".equals(mode) && preview == null) {
            throw dry.error("previewTool", "is required for mode TOOL");
        }
        return new ToolSettings(cls == null ? null : ToolClass.parse(cls), res, mode, arg, preview,
                t.stringSet("allowedGroups"), t.integer("timeoutMs", 0));
    }

    /**
     * {@code credentials: {type: token-exchange, tokenEndpoint, clientIdEnv, clientSecretEnv, audience,
     * resource, scope, timeoutMs, checkSubject, discovery: {...static credential...}}}.
     */
    private static io.github.vaquarkhan.aegis.config.credentials.TokenExchangeCredentials tokenExchange(
            ConfigNode c, String sourceId, BuildContext ctx) {
        URI endpoint = endpoint(c, "tokenEndpoint");
        String audience = c.string("audience", null);
        String resource = c.string("resource", null);
        if (audience == null && resource == null) {
            throw c.error("audience", "or resource is required: an exchanged token must be limited to this backend");
        }
        long timeout = c.integer("timeoutMs", 5000);
        if (timeout < 100 || timeout > 60_000) {
            throw c.error("timeoutMs", "must be between 100 and 60000");
        }
        return new io.github.vaquarkhan.aegis.config.credentials.TokenExchangeCredentials(sourceId,
                new io.github.vaquarkhan.aegis.config.credentials.TokenExchangeCredentials.Settings(endpoint,
                        requireEnv(ctx, c.requiredString("clientIdEnv"), c.path() + ".clientIdEnv"),
                        requireEnv(ctx, c.requiredString("clientSecretEnv"), c.path() + ".clientSecretEnv"),
                        audience, resource, c.string("scope", null), Duration.ofMillis(timeout), c.bool("checkSubject", true)),
                ctx.clock());
    }

    private static StaticCredentialProvider.Spec credentials(ConfigNode c, BuildContext ctx) {
        StaticCredentialProvider.Kind kind = choice(c, "type", "none", StaticCredentialProvider.Kind.class);
        return switch (kind) {
            case NONE -> new StaticCredentialProvider.Spec(kind, null, null, null, null);
            case BEARER -> new StaticCredentialProvider.Spec(kind, null, requireEnv(ctx, c.requiredString("tokenEnv"), c.path() + ".tokenEnv"), null, null);
            case BASIC -> new StaticCredentialProvider.Spec(kind, null, null,
                    requireEnv(ctx, c.requiredString("usernameEnv"), c.path() + ".usernameEnv"),
                    requireEnv(ctx, c.requiredString("passwordEnv"), c.path() + ".passwordEnv"));
            case HEADER -> new StaticCredentialProvider.Spec(kind, c.requiredString("header"),
                    requireEnv(ctx, c.requiredString("valueEnv"), c.path() + ".valueEnv"), null, null);
        };
    }

    private static URI endpoint(ConfigNode s, String key) {
        URI u;
        try {
            u = URI.create(s.requiredString(key));
        } catch (IllegalArgumentException e) {
            throw s.error(key, "is not a valid URL");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || u.getHost() == null || u.getRawUserInfo() != null) {
            throw s.error(key, "must be an absolute http(s) URL without user-info");
        }
        return u;
    }

    private static byte[] signingKey(ConfigNode approval, BuildContext ctx) throws Exception {
        String env = approval.string("keyEnv", null);
        String file = approval.string("keyFile", null);
        String value = null;
        if (env != null) {
            value = ctx.env().apply(env);
            if (value == null) {
                throw approval.error("keyEnv", "names " + env + ", which is not set by secrets.source");
            }
        } else if (file != null) {
            value = Files.readString(ctx.baseDir().resolve(file), StandardCharsets.UTF_8).trim();
        }
        if (value == null) {
            return null;
        }
        byte[] key = value.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw approval.error(env != null ? "keyEnv" : "keyFile", "key must be at least 32 bytes");
        }
        return key;
    }

    /**
     * Builds the built-in {@code remote} hook. The endpoint is checked against the egress allow list
     * here, at startup, and refused if it is not allowed: the hook's own HTTP call is not re-checked
     * per request, so this is the one place an operator can be told that a control they configured
     * would send governance data somewhere the egress policy does not permit.
     */
    private static GovernanceHook remoteHook(BuildContext ctx, ConfigNode s, EgressGuard egressGuard) {
        URI url;
        try {
            url = new URI(s.requiredString("url"));
        } catch (java.net.URISyntaxException e) {
            throw s.error("url", "must be a URI");
        }
        String why = egressGuard.check(url);
        if (why != null) {
            throw s.error("url", "is not allowed by config.egress: " + why);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        String tokenEnv = s.string("tokenEnv", null);
        if (tokenEnv != null) {
            headers.put("Authorization", "Bearer " + requireEnv(ctx, tokenEnv, s.path() + ".tokenEnv"));
        }
        Set<RemoteHook.Phase> phases = EnumSet.noneOf(RemoteHook.Phase.class);
        for (String p : s.strings("phases")) {
            try {
                phases.add(RemoteHook.Phase.valueOf(p.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw s.error("phases", "must name BEFORE and/or AFTER");
            }
        }
        return new RemoteHook(s.string("name", "remote"), url, Duration.ofMillis(s.integer("timeoutMs", 2000)), headers,
                s.bool("includeArguments", false), s.bool("includeResult", false),
                (int) s.integer("maxResponseBytes", 8192), phases);
    }

    /** The observers, in order. Both are optional and both are read-only: neither can change a decision. */
    private static List<io.github.vaquarkhan.aegis.engine.spi.DecisionListener> listeners(DecisionMetrics metrics,
                                                                                         OtlpSpanExporter tracer) {
        List<io.github.vaquarkhan.aegis.engine.spi.DecisionListener> out = new ArrayList<>();
        if (metrics != null) {
            out.add(metrics);
        }
        if (tracer != null) {
            out.add(tracer);
        }
        return out;
    }

    /**
     * Builds the built-in {@code sql-readonly} hook. The argument names are required rather than
     * defaulted: a guard that silently checks nothing reads like protection and is worse than no guard.
     */
    private static GovernanceHook sqlReadOnlyHook(ConfigNode s) {
        List<String> args = s.strings("arguments");
        if (args.isEmpty()) {
            throw s.error("arguments", "must name at least one statement argument to check, for example [sql]");
        }
        return new SqlReadOnlyHook(s.string("name", "sql-readonly"), new LinkedHashSet<>(args),
                (int) s.integer("maxChars", 8192));
    }

    private static String requireEnv(BuildContext ctx, String name, String where) {
        String v = ctx.env().apply(name);
        if (v == null || v.isEmpty()) {
            throw new ConfigException(where + " names " + name + ", which is not set by secrets.source");
        }
        return v;
    }

    /**
     * Reads an enumerated value. An unknown value stops startup with the key path and the accepted
     * values, rather than surfacing as a bare {@link IllegalArgumentException} without context.
     */
    private static <E extends Enum<E>> E choice(ConfigNode n, String key, String def, Class<E> type) {
        String v = n.string(key, def);
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            List<String> names = new ArrayList<>();
            for (E c : type.getEnumConstants()) {
                names.add(c.name());
            }
            throw n.error(key, "must be one of " + names);
        }
    }

    private static ControlMode controlMode(ConfigNode n, String key) {
        try {
            return ControlMode.parse(n.string(key, "ENFORCE"));
        } catch (IllegalArgumentException e) {
            throw n.error(key, "must be ENFORCE, AUDIT or OFF");
        }
    }

    private static Set<ToolClass> classes(List<String> names, ConfigNode where) {
        Set<ToolClass> out = EnumSet.noneOf(ToolClass.class);
        for (String n : names) {
            try {
                out.add(ToolClass.parse(n));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(where.path() + ": " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * Finds a plugin by type. A provider whose own dependencies are missing (for example the Kafka
     * admin source without kafka-clients) is skipped with a warning instead of breaking every other
     * source; it only matters if configuration actually asks for that type.
     */
    private static <T> Optional<T> provider(Class<T> spi, String type, java.util.function.Function<T, String> typeOf) {
        java.util.Iterator<ServiceLoader.Provider<T>> it = ServiceLoader.load(spi).stream().iterator();
        while (it.hasNext()) {
            try {
                T p = it.next().get();
                if (type.equals(typeOf.apply(p))) {
                    return Optional.of(p);
                }
            } catch (java.util.ServiceConfigurationError | LinkageError e) {
                LOG.log(System.Logger.Level.WARNING, "skipping unloadable " + spi.getSimpleName() + " provider: " + e);
            }
        }
        return Optional.empty();
    }

    private static AuditSink tee(AuditSink primary, AuditSink extra) {
        return new AuditSink() {
            @Override
            public void append(Map<String, Object> record) throws Exception {
                primary.append(record); // both must succeed: fail-closed
                extra.append(record);
            }

            @Override
            public void close() {
                try {
                    extra.close();
                } catch (Exception ignored) {
                    // shutdown
                }
                try {
                    primary.close();
                } catch (Exception ignored) {
                    // shutdown
                }
            }
        };
    }
}
