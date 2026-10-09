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
package io.github.vaquarkhan.aegis.engine.content;

import io.github.vaquarkhan.aegis.engine.GovernanceSettings;
import io.github.vaquarkhan.aegis.engine.catalog.CatalogIntegrity;
import io.github.vaquarkhan.aegis.engine.control.CircuitBreakers;
import io.github.vaquarkhan.aegis.engine.control.ControlMode;
import io.github.vaquarkhan.aegis.engine.control.EgressGuard;
import io.github.vaquarkhan.aegis.engine.control.Glob;
import io.github.vaquarkhan.aegis.engine.control.HookRunner;
import io.github.vaquarkhan.aegis.engine.control.InjectionScreen;
import io.github.vaquarkhan.aegis.engine.control.ScopeRule;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.engine.model.RoundTrip;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.model.TraceContext;
import io.github.vaquarkhan.aegis.engine.spi.DecisionListener;
import io.github.vaquarkhan.aegis.engine.spi.AuditSink;
import io.github.vaquarkhan.aegis.engine.spi.ContentSource;
import io.github.vaquarkhan.aegis.engine.spi.CredentialProvider;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import io.github.vaquarkhan.aegis.engine.spi.PolicyDecisionPoint;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor.ExecutionException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Governs MCP resources, resource templates, prompts and argument completion.
 *
 * <p>Why these need governance at all: they cannot change backend state, but they put data
 * (resources) and instructions (prompts) straight into the model's context. So every governed read
 * ({@code resources/read}, {@code prompts/get}, {@code completion/complete}) is a READ action that
 * passes the same chain as a READ tool call, with the same deny codes and step numbers:
 * <pre>
 *  1 exposure     source enabled for this content, caller in the configured groups, prompt active
 *                 (pinned and unchanged), resource URI routed to exactly one source
 *  2 input+scope  argument shape (prompt arguments are strings, declared, required present); scopes
 *                 over the typed resource {@code mcp:resource:<uri>} / {@code mcp:prompt:<name>}
 *  3 policy       PDP over the canonical action (tool = the MCP method, class READ)
 *  5 egress       source endpoint and every http(s) URL in the request
 *  6 rate/budget  charged like a READ tool call
 *  7 breaker      shared with the source's tools (same backend)
 *  8 screening    request arguments (argument screen), then extension hooks, then the returned
 *                 content (hooks again, then the content screen)
 *    commit       ALLOW_INTENT audit; failure denies with AUDIT_UNAVAILABLE
 * 10 execute      credentials resolved now; result shape validated and rebuilt from spec fields only;
 *                 every string redacted; binary parts refused unless allowed; size bounded
 * </pre>
 * Lists ({@code resources/list}, {@code resources/templates/list}, {@code prompts/list}) are
 * filtered by exposure and URI/name patterns, redacted and screened item by item, and rate limited;
 * they are not audited, like {@code tools/list}. Prompt definitions are pinned exactly like tool
 * definitions (key {@code prompt:<name>}), so a changed prompt is quarantined (rug-pull defence).
 * With a signed server catalog, the prompt key must also appear in the record for the source that
 * advertised it; a matching pin from another source cannot authorize it. Prompt message text is dynamic by design (templated), so it is screened, not pinned.
 */
public final class ContentGovernor {

    private static final System.Logger LOG = System.getLogger(ContentGovernor.class.getName());

    public static final String READ_RESOURCE = "resources/read";
    public static final String GET_PROMPT = "prompts/get";
    public static final String COMPLETE = "completion/complete";
    public static final String LIST_RESOURCES = "resources/list";
    public static final String LIST_TEMPLATES = "resources/templates/list";
    /** Spec limit for {@code completion.values}. */
    public static final int MAX_COMPLETION_VALUES = 100;
    private static final int MAX_URI_CHARS = 4096;
    private static final int MAX_NAME_CHARS = 256;

    /** One source's content backend, settings, discovery credentials, and source-scoped signed pins. */
    public record Binding(ContentSource source, ContentSettings settings, Supplier<Map<String, String>> serviceCredentials,
                          Set<String> signedNames) {
        public Binding(ContentSource source, ContentSettings settings, Supplier<Map<String, String>> serviceCredentials) {
            this(source, settings, serviceCredentials, null);
        }

        public Binding {
            if (!source.id().equals(settings.sourceId())) {
                throw new IllegalArgumentException("binding source id mismatch: " + source.id() + " vs " + settings.sourceId());
            }
            serviceCredentials = serviceCredentials == null ? Map::of : serviceCredentials;
            signedNames = signedNames == null ? null : Set.copyOf(signedNames);
        }
    }

    /** Behaviour switches shared with the tool catalog. */
    public record Options(boolean acceptNewPromptsAfterStart, boolean failOnSourceError, boolean checkArgumentUrls) {}

    /** Result of one governed request: the MCP result when allowed, the first denial otherwise. */
    public record Outcome(String requestId, Decision decision, Map<String, Object> result) {
        public boolean allowed() {
            return decision.allowed();
        }
    }

    /** One exposed prompt and its integrity status. */
    public record PromptEntry(String exposedName, String upstreamName, String sourceId, Map<String, Object> advertised,
                              Set<String> argumentNames, Set<String> requiredArguments, String digest, boolean active,
                              String reason) {}

    private final GovernanceSettings settings;
    private final List<Binding> bindings;
    private final Map<String, Binding> byId;
    private final PolicyDecisionPoint pdp;
    private final StateStore state;
    private final AuditSink audit;
    private final CredentialProvider credentials;
    private final CircuitBreakers breakers;
    private final CatalogIntegrity integrity;
    private final InjectionScreen contentScreen;
    private final Options options;
    private final Clock clock;
    private final HookRunner hooks;
    private final Consumer<Map<String, Object>> events;
    private final SecureRandom random = new SecureRandom();
    private volatile Map<String, PromptEntry> prompts = Map.of();
    private volatile boolean loaded;

    public ContentGovernor(GovernanceSettings settings, List<Binding> bindings, PolicyDecisionPoint pdp, StateStore state,
                           AuditSink audit, CredentialProvider credentials, CircuitBreakers breakers,
                           CatalogIntegrity integrity, InjectionScreen contentScreen, Options options, Clock clock,
                           Consumer<Map<String, Object>> events) {
        this.settings = settings;
        this.bindings = List.copyOf(bindings);
        Map<String, Binding> m = new LinkedHashMap<>();
        for (Binding b : this.bindings) {
            if (m.put(b.settings().sourceId(), b) != null) {
                throw new IllegalArgumentException("duplicate content source id " + b.settings().sourceId());
            }
        }
        this.byId = Collections.unmodifiableMap(m);
        this.pdp = pdp;
        this.state = state;
        this.audit = audit;
        this.credentials = credentials == null ? CredentialProvider.NONE : credentials;
        this.breakers = breakers;
        this.integrity = integrity;
        this.contentScreen = contentScreen;
        this.options = options;
        this.clock = clock;
        // Same hook list as the tool chain, so a control an operator adds governs data and instructions
        // as well as actions. The engine owns closing them.
        this.hooks = new HookRunner(settings.hooks());
        this.events = events == null ? e -> { } : events;
    }

    // ================================================================= capabilities

    public boolean offersResources() {
        return bindings.stream().anyMatch(b -> b.settings().resourcesEnabled());
    }

    public boolean offersPrompts() {
        return bindings.stream().anyMatch(b -> b.settings().promptsEnabled());
    }

    public boolean offersCompletions() {
        return bindings.stream().anyMatch(b -> b.settings().completionsEnabled());
    }

    // ================================================================= prompt catalog

    /** Startup discovery of prompts: pins on first sight (TOFU) or verifies operator pins. */
    public synchronized void load() throws Exception {
        prompts = buildPrompts(true);
        loaded = true;
    }

    /** Re-discovers prompts and re-verifies pins; changed prompts are quarantined. */
    public synchronized void refresh() throws Exception {
        if (!loaded) {
            load();
            return;
        }
        prompts = buildPrompts(false);
    }

    public List<PromptEntry> promptEntries() {
        return new ArrayList<>(prompts.values());
    }

    /**
     * Operator accepts a reviewed definition of a quarantined prompt. Config pins cannot be
     * overridden, and the reviewed digest must still be current at the acceptance point.
     */
    public synchronized boolean acceptPrompt(String exposedName, String expectedDigest) throws Exception {
        PromptEntry e = prompts.get(exposedName);
        if (e == null || e.active() || expectedDigest == null || !expectedDigest.equals(e.digest())) {
            return false;
        }
        if (!integrity.accept(pinKey(exposedName), expectedDigest)) {
            return false;
        }
        Map<String, PromptEntry> copy = new TreeMap<>(prompts);
        copy.put(exposedName, new PromptEntry(e.exposedName(), e.upstreamName(), e.sourceId(), e.advertised(),
                e.argumentNames(), e.requiredArguments(), expectedDigest, true, "accepted by operator"));
        prompts = Collections.unmodifiableMap(copy);
        event("PROMPT_ACCEPTED", e.sourceId(), exposedName, "digest=" + expectedDigest);
        return true;
    }

    private static String pinKey(String exposedName) {
        return "prompt:" + exposedName;
    }

    private Map<String, PromptEntry> buildPrompts(boolean startup) throws Exception {
        Map<String, PromptEntry> previous = prompts;
        Map<String, PromptEntry> next = new TreeMap<>();
        for (Binding b : bindings) {
            ContentSettings cs = b.settings();
            if (!cs.promptsEnabled()) {
                continue;
            }
            List<Map<String, Object>> offered;
            try {
                String why = egressWhy(b);
                if (why != null) {
                    throw new IllegalStateException("egress denied: " + why);
                }
                offered = b.source().listPrompts(new ContentSource.Request(b.serviceCredentials().get(), cs.timeoutMillis(), null));
            } catch (Exception e) {
                event("SOURCE_UNAVAILABLE", cs.sourceId(), null, "prompts/list: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (options.failOnSourceError() && startup) {
                    throw new IllegalStateException("prompt source '" + cs.sourceId() + "' failed discovery", e);
                }
                keepPrevious(previous, next, cs.sourceId());
                continue;
            }
            // First pass: names and shape, so a conflicting listing pins nothing.
            Map<String, Map<String, Object>> mine = new LinkedHashMap<>();
            String conflict = null;
            for (Map<String, Object> p : offered) {
                String upstream = JsonValues.string(p, "name");
                if (upstream == null || upstream.isEmpty() || upstream.length() > MAX_NAME_CHARS || argumentSpec(p) == null) {
                    event("PROMPT_INVALID", cs.sourceId(), upstream, "prompt definition is malformed; not exposed");
                    continue;
                }
                if (!cs.promptPatterns().isEmpty() && !Glob.anyMatches(cs.promptPatterns(), upstream)) {
                    continue;
                }
                String exposed = cs.namePrefix() + upstream;
                if (mine.containsKey(exposed) || next.containsKey(exposed)) {
                    conflict = "duplicate prompt name '" + exposed + "' (source '" + cs.sourceId() + "'); set a namePrefix";
                    break;
                }
                mine.put(exposed, p);
            }
            if (conflict != null) {
                if (startup) {
                    throw new IllegalStateException(conflict);
                }
                event("SOURCE_INVALID", cs.sourceId(), null, conflict);
                keepPrevious(previous, next, cs.sourceId());
                continue;
            }
            for (Map.Entry<String, Map<String, Object>> e : mine.entrySet()) {
                next.put(e.getKey(), classify(b, e.getKey(), e.getValue(), startup || previous.containsKey(e.getKey()), previous));
            }
        }
        return Collections.unmodifiableMap(next);
    }

    private static void keepPrevious(Map<String, PromptEntry> previous, Map<String, PromptEntry> next, String sourceId) {
        for (PromptEntry prev : previous.values()) {
            if (prev.sourceId().equals(sourceId) && !next.containsKey(prev.exposedName())) {
                next.put(prev.exposedName(), prev);
            }
        }
    }

    /** @return [declared names, required names], or null when {@code arguments} is malformed */
    private static List<Set<String>> argumentSpec(Map<String, Object> prompt) {
        Object a = prompt.get("arguments");
        Set<String> names = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        if (a == null) {
            return List.of(names, required);
        }
        List<Object> list = JsonValues.asList(a);
        if (list == null) {
            return null;
        }
        for (Object o : list) {
            Map<String, Object> arg = JsonValues.asObject(o);
            String n = arg == null ? null : JsonValues.string(arg, "name");
            if (n == null || n.isEmpty() || !names.add(n)) {
                return null;
            }
            Object req = arg.get("required");
            if (req != null && !(req instanceof Boolean)) {
                return null;
            }
            if (Boolean.TRUE.equals(req)) {
                required.add(n);
            }
        }
        return List.of(names, required);
    }

    private PromptEntry classify(Binding binding, String exposed, Map<String, Object> def, boolean firstSightAllowed,
                                 Map<String, PromptEntry> previous) throws Exception {
        ContentSettings cs = binding.settings();
        String digest = Digests.canonicalSha256(def);
        PromptEntry prev = previous.get(exposed);
        if (binding.signedNames() != null && !binding.signedNames().contains(pinKey(exposed))) {
            return promptEntry(cs, exposed, def, digest, prev, false, "definition is not pinned by this source's signed record");
        }
        CatalogIntegrity.Verdict v = integrity.verify(pinKey(exposed), digest,
                firstSightAllowed || (prev == null && options.acceptNewPromptsAfterStart()));
        boolean active;
        String reason;
        switch (v) {
            case MATCH, PINNED_NOW -> {
                active = true;
                reason = v == CatalogIntegrity.Verdict.PINNED_NOW ? "pinned on first sight" : "";
            }
            case MISMATCH -> {
                active = false;
                reason = "definition changed since it was pinned";
            }
            default -> {
                active = false;
                reason = "definition is not pinned";
            }
        }
        return promptEntry(cs, exposed, def, digest, prev, active, reason);
    }

    private PromptEntry promptEntry(ContentSettings cs, String exposed, Map<String, Object> def, String digest,
                                    PromptEntry prev, boolean active, String reason) {
        if (active && contentScreen.mode() != ControlMode.OFF) {
            Optional<String> hit = contentScreen.firstMatch(def);
            if (hit.isPresent()) {
                event("PROMPT_DEFINITION_SUSPICIOUS", cs.sourceId(), exposed, "matched: " + hit.get());
                if (contentScreen.mode() == ControlMode.ENFORCE) {
                    active = false;
                    reason = "definition matches a poisoning pattern";
                }
            }
        }
        Map<String, Object> advertised = new LinkedHashMap<>(JsonValues.asObject(settings.outputGuard().redactAll(def).value()));
        advertised.put("name", exposed);
        List<Set<String>> spec = argumentSpec(def);
        PromptEntry e = new PromptEntry(exposed, JsonValues.string(def, "name"), cs.sourceId(),
                Collections.unmodifiableMap(advertised), Set.copyOf(spec.get(0)), Set.copyOf(spec.get(1)), digest, active, reason);
        boolean changed = prev == null || !prev.digest().equals(digest) || prev.active() != active;
        if (changed) {
            event(active ? "PROMPT_ACTIVE" : "PROMPT_QUARANTINED", cs.sourceId(), exposed, reason + " digest=" + digest);
        }
        return e;
    }

    // ================================================================= lists

    /** {@code prompts/list}: active prompts this caller may see (served from the pinned catalog). */
    public List<Map<String, Object>> listPrompts(CallerIdentity caller) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PromptEntry e : prompts.values()) {
            Binding b = byId.get(e.sourceId());
            if (e.active() && b != null && exposed(caller, b.settings().promptGroups())) {
                out.add(e.advertised());
            }
        }
        return out;
    }

    /** {@code resources/list}: live from every enabled source, filtered, redacted and screened. */
    public Outcome listResources(CallerIdentity caller, String protocolVersion, String traceparent) {
        return listing(caller, protocolVersion, traceparent, LIST_RESOURCES, "resources", "uri");
    }

    /** {@code resources/templates/list}. */
    public Outcome listResourceTemplates(CallerIdentity caller, String protocolVersion, String traceparent) {
        return listing(caller, protocolVersion, traceparent, LIST_TEMPLATES, "resourceTemplates", "uriTemplate");
    }

    private Outcome listing(CallerIdentity caller, String protocolVersion, String traceparent, String method, String listKey,
                            String uriKey) {
        Trace t = new Trace(newRequestId(), caller, method, protocolVersion, traceparent, clock.millis());
        try {
            t.action = new Action(method, ToolClass.READ, "", List.of(), Digests.canonicalSha256(Map.of()));
            String rateCode = settings.rateLimiter().admit(state, caller, t.action, clock.instant());
            if (rateCode != null) {
                return deny(t, Decision.deny(rateCode, DenyCodes.STEP_RATE,
                        rateCode.equals(DenyCodes.RATE_LIMITED) ? "call rate limit reached" : "call budget exhausted"));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            List<String> unavailable = new ArrayList<>();
            for (Binding b : bindings) {
                ContentSettings cs = b.settings();
                if (!cs.resourcesEnabled() || !exposed(caller, cs.resourceGroups())) {
                    continue;
                }
                List<Map<String, Object>> items = fetchList(b, t, unavailable);
                if (items == null) {
                    continue;
                }
                for (Map<String, Object> item : items) {
                    String uri = JsonValues.string(item, uriKey);
                    if (uri == null || uri.length() > MAX_URI_CHARS || !Glob.anyMatches(cs.uriPatterns(), uri)) {
                        continue;
                    }
                    if (contentScreen.mode() != ControlMode.OFF) {
                        Optional<String> hit = contentScreen.firstMatch(item);
                        if (hit.isPresent()) {
                            event("RESOURCE_SUSPICIOUS", cs.sourceId(), uri, "matched: " + hit.get());
                            if (contentScreen.mode() == ControlMode.ENFORCE) {
                                continue;
                            }
                        }
                    }
                    Map<String, Object> redacted = JsonValues.asObject(settings.outputGuard().redactAll(item).value());
                    if (redacted != null) {
                        out.add(redacted);
                    }
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(listKey, out);
            if (!unavailable.isEmpty()) {
                result.put("_meta", Map.of(ControlTokens.META_PREFIX + "unavailableSources", List.copyOf(unavailable)));
            }
            notifyListeners(t, Decision.allow(), true);
            return new Outcome(t.requestId, Decision.allow(), result);
        } catch (RuntimeException e) {
            return internal(t, e);
        }
    }

    /** One source's list, through egress and breaker. Null (and the source recorded) on failure. */
    private List<Map<String, Object>> fetchList(Binding b, Trace t, List<String> unavailable) {
        String id = b.settings().sourceId();
        String method = t.method;
        String why = egressWhy(b);
        if (why != null) {
            event("SOURCE_EGRESS_DENIED", id, null, method + ": " + why);
            unavailable.add(id);
            return null;
        }
        CircuitBreakers.Permit permit = breakers.acquire(id);
        if (permit == CircuitBreakers.Permit.DENIED) {
            unavailable.add(id);
            return null;
        }
        Map<String, String> creds;
        try {
            creds = credentials.credentialsFor(id, t.caller);
        } catch (Exception e) {
            breakers.release(id, permit);
            event("SOURCE_CREDENTIALS", id, null, method + ": " + e.getClass().getSimpleName());
            unavailable.add(id);
            return null;
        }
        try {
            ContentSource.Request req = new ContentSource.Request(creds, b.settings().timeoutMillis(), t.tc.header());
            List<Map<String, Object>> items = LIST_TEMPLATES.equals(method)
                    ? b.source().listResourceTemplates(req) : b.source().listResources(req);
            breakers.success(id, permit);
            return items == null ? List.of() : items;
        } catch (ExecutionException | RuntimeException e) {
            breakers.failure(id, permit);
            event("SOURCE_UNAVAILABLE", id, null, method + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            unavailable.add(id);
            return null;
        }
    }

    // ================================================================= governed reads

    /** {@code resources/read}. */
    public Outcome readResource(CallerIdentity caller, String uri, String protocolVersion, String traceparent) {
        return readResource(caller, uri, protocolVersion, traceparent, RoundTrip.NONE);
    }

    /** {@code resources/read}, possibly an MRTR retry. */
    public Outcome readResource(CallerIdentity caller, String uri, String protocolVersion, String traceparent, RoundTrip roundTrip) {
        Trace t = new Trace(newRequestId(), caller, READ_RESOURCE, protocolVersion, traceparent, clock.millis());
        t.roundTrip = roundTrip == null ? RoundTrip.NONE : roundTrip;
        try {
            if (uri == null || uri.isEmpty() || uri.length() > MAX_URI_CHARS || hasControl(uri)) {
                return deny(t, Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE, "uri must be a non-empty URI string"));
            }
            t.resourceLabel = uri;
            Binding b = route(uri);
            if (b == null) {
                return deny(t, Decision.deny(DenyCodes.NOT_EXPOSED, DenyCodes.STEP_EXPOSURE, "resource is not exposed"));
            }
            t.binding = b;
            if (!exposed(caller, b.settings().resourceGroups())) {
                return deny(t, Decision.deny(DenyCodes.NOT_EXPOSED, DenyCodes.STEP_EXPOSURE, "resource is not exposed to this caller"));
            }
            Map<String, Object> args = Map.of("uri", uri);
            return governed(t, List.of("mcp:resource:" + uri), args, b.settings().allowBinaryResources(),
                    req -> b.source().readResource(uri, req),
                    r -> readResult(b, r));
        } catch (RuntimeException e) {
            return internal(t, e);
        }
    }

    /** {@code prompts/get}. */
    public Outcome getPrompt(CallerIdentity caller, String name, Map<String, Object> arguments, String protocolVersion,
                             String traceparent) {
        return getPrompt(caller, name, arguments, protocolVersion, traceparent, RoundTrip.NONE);
    }

    /** {@code prompts/get}, possibly an MRTR retry. */
    public Outcome getPrompt(CallerIdentity caller, String name, Map<String, Object> arguments, String protocolVersion,
                             String traceparent, RoundTrip roundTrip) {
        Trace t = new Trace(newRequestId(), caller, GET_PROMPT, protocolVersion, traceparent, clock.millis());
        t.roundTrip = roundTrip == null ? RoundTrip.NONE : roundTrip;
        try {
            t.resourceLabel = name;
            PromptEntry e = name == null ? null : prompts.get(name);
            if (e == null) {
                return deny(t, Decision.deny(DenyCodes.NOT_EXPOSED, DenyCodes.STEP_EXPOSURE, "prompt is not exposed"));
            }
            Binding b = byId.get(e.sourceId());
            t.binding = b;
            if (b == null || !exposed(caller, b.settings().promptGroups())) {
                return deny(t, Decision.deny(DenyCodes.NOT_EXPOSED, DenyCodes.STEP_EXPOSURE, "prompt is not exposed to this caller"));
            }
            if (!e.active()) {
                return deny(t, Decision.deny(DenyCodes.CATALOG_INTEGRITY, DenyCodes.STEP_EXPOSURE, "prompt is quarantined: " + e.reason()));
            }
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            String argError = promptArgumentError(e, args);
            if (argError != null) {
                return deny(t, Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE, argError));
            }
            Map<String, Object> actionArgs = new LinkedHashMap<>();
            actionArgs.put("name", e.exposedName());
            actionArgs.put("arguments", args);
            return governed(t, List.of("mcp:prompt:" + e.exposedName()), actionArgs, b.settings().allowBinaryPrompts(),
                    req -> b.source().getPrompt(e.upstreamName(), args, req),
                    ContentGovernor::promptResult);
        } catch (RuntimeException ex) {
            return internal(t, ex);
        }
    }

    /**
     * {@code completion/complete}.
     *
     * @param params the request params: {@code ref} ({@code ref/prompt} + name or {@code ref/resource}
     *               + uri), {@code argument} ({@code name}, {@code value}) and optional {@code context.arguments}
     */
    public Outcome complete(CallerIdentity caller, Map<String, Object> params, String protocolVersion, String traceparent) {
        Trace t = new Trace(newRequestId(), caller, COMPLETE, protocolVersion, traceparent, clock.millis());
        try {
            Map<String, Object> ref = JsonValues.object(params, "ref");
            Map<String, Object> argument = JsonValues.object(params, "argument");
            String type = ref == null ? null : JsonValues.string(ref, "type");
            String argName = argument == null ? null : JsonValues.string(argument, "name");
            Object argValue = argument == null ? null : argument.get("value");
            if (type == null || argName == null || argName.isEmpty() || !(argValue instanceof String)) {
                return deny(t, Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE,
                        "completion needs ref.type, argument.name and a string argument.value"));
            }
            Map<String, Object> contextArgs = Map.of();
            Map<String, Object> context = JsonValues.object(params, "context");
            if (params.containsKey("context")) {
                Map<String, Object> ca = context == null ? null : JsonValues.object(context, "arguments");
                if (context == null || (context.containsKey("arguments") && ca == null) || (ca != null && !allStrings(ca))) {
                    return deny(t, Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE,
                            "context.arguments must be an object of strings"));
                }
                contextArgs = ca == null ? Map.of() : ca;
            }
            Binding b;
            String resource;
            Map<String, Object> exposedRef = new LinkedHashMap<>();
            Map<String, Object> upstreamRef = new LinkedHashMap<>();
            exposedRef.put("type", type);
            upstreamRef.put("type", type);
            switch (type) {
                case "ref/prompt" -> {
                    String name = JsonValues.string(ref, "name");
                    t.resourceLabel = name;
                    PromptEntry e = name == null ? null : prompts.get(name);
                    b = e == null ? null : byId.get(e.sourceId());
                    if (e == null || b == null || !exposed(caller, b.settings().promptGroups())) {
                        return deny(t, Decision.deny(DenyCodes.NOT_EXPOSED, DenyCodes.STEP_EXPOSURE, "prompt is not exposed"));
                    }
                    t.binding = b;
                    if (!e.active()) {
                        return deny(t, Decision.deny(DenyCodes.CATALOG_INTEGRITY, DenyCodes.STEP_EXPOSURE, "prompt is quarantined: " + e.reason()));
                    }
                    if (!e.argumentNames().contains(argName)) {
                        return deny(t, Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE,
                                "argument '" + argName + "' is not declared by this prompt"));
                    }
                    resource = "mcp:prompt:" + e.exposedName();
                    exposedRef.put("name", e.exposedName());
                    upstreamRef.put("name", e.upstreamName());
                }
                case "ref/resource" -> {
                    String uri = JsonValues.string(ref, "uri");
                    t.resourceLabel = uri;
                    if (uri == null || uri.isEmpty() || uri.length() > MAX_URI_CHARS || hasControl(uri)) {
                        return deny(t, Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE, "ref.uri must be a non-empty URI string"));
                    }
                    b = route(uri);
                    if (b == null || !exposed(caller, b.settings().resourceGroups())) {
                        return deny(t, Decision.deny(DenyCodes.NOT_EXPOSED, DenyCodes.STEP_EXPOSURE, "resource is not exposed"));
                    }
                    t.binding = b;
                    resource = "mcp:resource:" + uri;
                    exposedRef.put("uri", uri);
                    upstreamRef.put("uri", uri);
                }
                default -> {
                    return deny(t, Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE, "ref.type must be ref/prompt or ref/resource"));
                }
            }
            if (!b.settings().completionsEnabled()) {
                return deny(t, Decision.deny(DenyCodes.NOT_EXPOSED, DenyCodes.STEP_EXPOSURE, "completion is not enabled for this source"));
            }
            Map<String, Object> arg = Map.of("name", argName, "value", argValue);
            Map<String, Object> actionArgs = new LinkedHashMap<>();
            actionArgs.put("ref", exposedRef);
            actionArgs.put("argument", arg);
            Map<String, Object> upstream = new LinkedHashMap<>();
            upstream.put("ref", upstreamRef);
            upstream.put("argument", arg);
            if (!contextArgs.isEmpty()) {
                actionArgs.put("context", Map.of("arguments", contextArgs));
                upstream.put("context", Map.of("arguments", contextArgs));
            }
            Binding target = b;
            return governed(t, List.of(resource), actionArgs, false,
                    req -> target.source().complete(upstream, req),
                    ContentGovernor::completionResult);
        } catch (RuntimeException e) {
            return internal(t, e);
        }
    }

    @FunctionalInterface
    private interface Fetch {
        Map<String, Object> run(ContentSource.Request request) throws ExecutionException;
    }

    @FunctionalInterface
    private interface Shape {
        /** @return the rebuilt, spec-only result, or throws IllegalArgumentException with the reason */
        Map<String, Object> rebuild(Map<String, Object> upstream);
    }

    private Outcome governed(Trace t, List<String> resources, Map<String, Object> args, boolean allowBinary, Fetch fetch, Shape shape) {
        CallerIdentity caller = t.caller;
        Binding b = t.binding;
        String sourceId = b.settings().sourceId();
        Action action = new Action(t.method, ToolClass.READ, sourceId, resources, Digests.canonicalSha256(args));
        t.action = action;

        // ---- step 2: scope
        if (!settings.scopes().isEmpty()) {
            String out = ScopeRule.firstOutOfScope(settings.scopes(), caller, resources);
            if (out != null) {
                return deny(t, Decision.deny(DenyCodes.SCOPE_DENIED, DenyCodes.STEP_SCOPE, "resource out of scope: " + out));
            }
        }
        // ---- step 3: policy
        try {
            PolicyDecisionPoint.Result r = pdp.decide(caller, action, args);
            if (r == null || !r.allow()) {
                return deny(t, Decision.deny(DenyCodes.POLICY_DENIED, DenyCodes.STEP_POLICY,
                        r == null ? "policy returned no decision" : r.reason()));
            }
        } catch (Exception e) {
            return deny(t, Decision.deny(DenyCodes.POLICY_DENIED, DenyCodes.STEP_POLICY,
                    "policy evaluation failed (" + e.getClass().getSimpleName() + ")"));
        }
        // ---- step 5: egress
        String why = egressWhy(b);
        if (why == null && options.checkArgumentUrls()) {
            List<URI> urls = new ArrayList<>(EgressGuard.urlsIn(args));
            urls.addAll(EgressGuard.urlsIn(t.roundTrip.inputResponses()));
            for (URI u : urls) {
                why = settings.egressGuard().check(u);
                if (why != null) {
                    break;
                }
            }
        }
        if (why != null) {
            return deny(t, Decision.deny(DenyCodes.EGRESS_DENIED, DenyCodes.STEP_EGRESS, why));
        }
        // ---- step 6: rate and budget
        String rateCode = settings.rateLimiter().admit(state, caller, action, clock.instant());
        if (rateCode != null) {
            return deny(t, Decision.deny(rateCode, DenyCodes.STEP_RATE,
                    rateCode.equals(DenyCodes.RATE_LIMITED) ? "call rate limit reached" : "call budget exhausted"));
        }
        // ---- step 7: breaker
        CircuitBreakers.Permit permit = breakers.acquire(sourceId);
        if (permit == CircuitBreakers.Permit.DENIED) {
            return deny(t, Decision.deny(DenyCodes.BREAKER_OPEN, DenyCodes.STEP_BREAKER, "backend circuit is open"));
        }
        t.permit = permit;
        // ---- step 8: request screening
        if (settings.argumentScreen().mode() != ControlMode.OFF) {
            Optional<String> hit = settings.argumentScreen().firstMatch(args);
            if (hit.isEmpty()) {
                hit = settings.argumentScreen().firstMatch(t.roundTrip.inputResponses());
            }
            if (hit.isPresent()) {
                if (settings.argumentScreen().mode() == ControlMode.ENFORCE) {
                    return deny(t, Decision.deny(DenyCodes.PROMPT_INJECTION, DenyCodes.STEP_INJECTION,
                            "arguments match an injection pattern"));
                }
                t.screening = hit.get();
            }
        }
        // ---- step 8 (hooks): the operator's own controls, in configured order
        GovernanceHook.Context hookCtx = new GovernanceHook.Context(t.requestId, t.tc.traceId(), caller,
                operationFor(t.method), t.method, null, sourceId, action, args, false);
        Decision hookDenial = hooks.before(hookCtx, DenyCodes.STEP_INJECTION, t.hookAudit);
        if (hookDenial != null) {
            return deny(t, hookDenial);
        }
        // ---- commit
        try {
            audit.append(record(t, "ALLOW_INTENT", Decision.allow()));
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "audit journal unavailable; denying " + t.requestId, e);
            return deny(t, Decision.deny(DenyCodes.AUDIT_UNAVAILABLE, DenyCodes.STEP_EXECUTE,
                    "the audit journal could not record this request, so it was not executed"));
        }
        // ---- step 10: execute
        Map<String, String> creds;
        try {
            creds = credentials.credentialsFor(sourceId, caller);
        } catch (Exception e) {
            return fail(t, DenyCodes.BACKEND_ERROR, DenyCodes.STEP_EXECUTE,
                    "backend credentials could not be resolved (" + e.getClass().getSimpleName() + ")", false);
        }
        Map<String, Object> upstream;
        try {
            upstream = fetch.run(new ContentSource.Request(creds, b.settings().timeoutMillis(), t.tc.header(), t.roundTrip));
            if (upstream == null) {
                throw new ExecutionException("backend returned no result");
            }
            breakers.success(sourceId, t.permit);
            t.permit = null;
        } catch (ExecutionException e) {
            if (e.availabilityFailure()) {
                breakers.failure(sourceId, t.permit);
            } else {
                breakers.release(sourceId, t.permit);
            }
            t.permit = null;
            return fail(t, e.timeout() ? DenyCodes.TIMEOUT : DenyCodes.BACKEND_ERROR, DenyCodes.STEP_EXECUTE, e.getMessage(), true);
        } catch (RuntimeException e) {
            breakers.failure(sourceId, t.permit);
            t.permit = null;
            return fail(t, DenyCodes.BACKEND_ERROR, DenyCodes.STEP_EXECUTE,
                    "backend call failed (" + e.getClass().getSimpleName() + ")", true);
        }
        Map<String, Object> rebuilt;
        try {
            if ("input_required".equals(upstream.get("resultType"))) {
                // MRTR: relayed to the client (redacted and content-screened below like any content);
                // the client's retry is governed again as a new request. Completion cannot use MRTR.
                if (COMPLETE.equals(t.method)) {
                    throw new IllegalArgumentException("completion/complete may not return input_required");
                }
                rebuilt = inputRequired(upstream);
            } else {
                rebuilt = shape.rebuild(upstream);
            }
        } catch (IllegalArgumentException e) {
            return fail(t, DenyCodes.BACKEND_ERROR, DenyCodes.STEP_EXECUTE, "invalid " + t.method + " result: " + e.getMessage(), true);
        }
        // Hooks see the rebuilt, spec-only content before it is redacted, bounded and screened, so
        // whatever a hook adds is still guarded on the way out.
        HookRunner.Content afterHooks = hooks.afterContent(hookCtx, rebuilt, DenyCodes.STEP_EXECUTE, t.hookAudit);
        if (afterHooks.denied()) {
            return fail(t, afterHooks.denial().code(), afterHooks.denial().step(), afterHooks.denial().reason(), true);
        }
        rebuilt = afterHooks.value();
        Guarded g = guard(rebuilt);
        if (g.binary && !allowBinary) {
            return fail(t, DenyCodes.POLICY_DENIED, DenyCodes.STEP_EXECUTE,
                    "the result contains binary content, which is not allowed for this source (allowBinary)", true);
        }
        int size = Json.write(g.value).getBytes(StandardCharsets.UTF_8).length;
        if (size > settings.outputGuard().maxBytes()) {
            return fail(t, DenyCodes.BACKEND_ERROR, DenyCodes.STEP_EXECUTE,
                    "result is " + size + " bytes, over the output limit of " + settings.outputGuard().maxBytes(), true);
        }
        // ---- step 8 (output): the content screen covers what enters the model's context
        if (contentScreen.mode() != ControlMode.OFF) {
            Optional<String> hit = contentScreen.firstMatch(g.value);
            if (hit.isPresent()) {
                if (contentScreen.mode() == ControlMode.ENFORCE) {
                    return fail(t, DenyCodes.PROMPT_INJECTION, DenyCodes.STEP_INJECTION,
                            "returned content matches an injection pattern", true);
                }
                t.screening = t.screening == null ? "output: " + hit.get() : t.screening;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(g.value);
        t.redactions = g.redactions;
        t.resultDigest = Digests.canonicalSha256(result);
        if (g.redactions > 0) {
            result.put("_meta", Map.of(ControlTokens.META_PREFIX + "redactions", (long) g.redactions));
        }
        auditBestEffort(record(t, "EXECUTION_RESULT", Decision.allow()));
        notifyListeners(t, Decision.allow(), true);
        return new Outcome(t.requestId, Decision.allow(), result);
    }

    // ================================================================= result shapes

    /** {@code InputRequiredResult}: at least one of {@code inputRequests} (object) and {@code requestState} (string). */
    private static Map<String, Object> inputRequired(Map<String, Object> r) {
        Object ir = r.get("inputRequests");
        Object rs = r.get("requestState");
        if (ir != null && !(ir instanceof Map)) {
            throw new IllegalArgumentException("inputRequests must be an object");
        }
        if (rs != null && !(rs instanceof String)) {
            throw new IllegalArgumentException("requestState must be a string");
        }
        if (ir == null && rs == null) {
            throw new IllegalArgumentException("input_required needs inputRequests or requestState");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resultType", "input_required");
        if (ir != null) {
            out.put("inputRequests", ir);
        }
        if (rs != null) {
            out.put("requestState", rs);
        }
        return out;
    }

    /** {@code ReadResourceResult}: {@code contents[]}, each with a URI this source serves. */
    private static Map<String, Object> readResult(Binding b, Map<String, Object> r) {
        List<Object> contents = JsonValues.asList(r.get("contents"));
        if (contents == null) {
            throw new IllegalArgumentException("contents must be an array");
        }
        List<Object> out = new ArrayList<>();
        for (Object o : contents) {
            Map<String, Object> c = JsonValues.asObject(o);
            String uri = c == null ? null : JsonValues.string(c, "uri");
            if (uri == null) {
                throw new IllegalArgumentException("every content item needs a uri");
            }
            // A read of an exposed URI must not smuggle out resources this source does not expose.
            if (!Glob.anyMatches(b.settings().uriPatterns(), uri)) {
                throw new IllegalArgumentException("content item uri is outside this source's uriPatterns");
            }
            boolean text = c.get("text") instanceof String;
            boolean blob = c.get("blob") instanceof String;
            if (text == blob) {
                throw new IllegalArgumentException("every content item needs exactly one of text or blob");
            }
            out.add(pick(c, "uri", "mimeType", "text", "blob", "annotations"));
        }
        return Map.of("contents", out);
    }

    /** {@code GetPromptResult}: optional description, {@code messages[]} with role user|assistant. */
    private static Map<String, Object> promptResult(Map<String, Object> r) {
        List<Object> messages = JsonValues.asList(r.get("messages"));
        if (messages == null) {
            throw new IllegalArgumentException("messages must be an array");
        }
        List<Object> out = new ArrayList<>();
        for (Object o : messages) {
            Map<String, Object> m = JsonValues.asObject(o);
            String role = m == null ? null : JsonValues.string(m, "role");
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new IllegalArgumentException("message role must be user or assistant");
            }
            if (!(m.get("content") instanceof Map)) {
                throw new IllegalArgumentException("message content must be an object");
            }
            out.add(pick(m, "role", "content"));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        if (r.get("description") instanceof String d) {
            res.put("description", d);
        }
        res.put("messages", out);
        return res;
    }

    /** {@code CompleteResult}: {@code completion.values} (strings, at most 100), total, hasMore. */
    private static Map<String, Object> completionResult(Map<String, Object> r) {
        Map<String, Object> c = JsonValues.object(r, "completion");
        List<Object> values = c == null ? null : JsonValues.asList(c.get("values"));
        if (values == null) {
            throw new IllegalArgumentException("completion.values must be an array");
        }
        List<Object> vs = new ArrayList<>();
        for (Object v : values) {
            if (!(v instanceof String)) {
                throw new IllegalArgumentException("completion values must be strings");
            }
            if (vs.size() < MAX_COMPLETION_VALUES) {
                vs.add(v);
            }
        }
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("values", vs);
        if (c.get("total") instanceof Number n) {
            comp.put("total", n);
        }
        boolean hasMore = Boolean.TRUE.equals(c.get("hasMore")) || values.size() > MAX_COMPLETION_VALUES;
        if (c.containsKey("hasMore") || hasMore) {
            comp.put("hasMore", hasMore);
        }
        return Map.of("completion", comp);
    }

    private static Map<String, Object> pick(Map<String, Object> m, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys) {
            if (m.containsKey(k)) {
                out.put(k, m.get(k));
            }
        }
        return out;
    }

    // ================================================================= output guard

    private record Guarded(Map<String, Object> value, int redactions, boolean binary) {}

    /**
     * Redacts every string except binary payloads ({@code blob}; {@code data} of image/audio parts),
     * which regular expressions would corrupt. Binary parts are reported so policy can refuse them.
     */
    private Guarded guard(Map<String, Object> value) {
        int[] count = new int[1];
        boolean[] binary = new boolean[1];
        Object v = walk(value, count, binary);
        return new Guarded(JsonValues.asObject(v), count[0], binary[0]);
    }

    private Object walk(Object v, int[] count, boolean[] binary) {
        if (v instanceof String s) {
            var r = settings.outputGuard().redactAll(s);
            count[0] += r.redactions();
            return r.value();
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(walk(o, count, binary));
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Object type = m.get("type");
            boolean media = "image".equals(type) || "audio".equals(type);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if ("blob".equals(k) || (media && "data".equals(k))) {
                    binary[0] = true;
                    out.put(k, e.getValue());
                } else {
                    out.put(k, walk(e.getValue(), count, binary));
                }
            }
            return out;
        }
        return v;
    }

    // ================================================================= helpers

    private Binding route(String uri) {
        Binding found = null;
        for (Binding b : bindings) {
            if (b.settings().resourcesEnabled() && Glob.anyMatches(b.settings().uriPatterns(), uri)) {
                if (found != null) {
                    // Overlapping uriPatterns: refuse rather than guess which backend is meant.
                    LOG.log(System.Logger.Level.WARNING, "resource URI matches the uriPatterns of more than one source; denied");
                    return null;
                }
                found = b;
            }
        }
        return found;
    }

    private String egressWhy(Binding b) {
        URI endpoint = b.source().endpoint();
        return endpoint == null ? null : settings.egressGuard().check(endpoint);
    }

    private static boolean exposed(CallerIdentity caller, Set<String> groups) {
        return groups.isEmpty() || caller.inAnyGroup(groups);
    }

    /** Which hook operation a governed read is. Unknown methods never reach hooks (fail-closed). */
    private static GovernanceHook.Operation operationFor(String method) {
        return switch (method) {
            case READ_RESOURCE -> GovernanceHook.Operation.RESOURCE_READ;
            case GET_PROMPT -> GovernanceHook.Operation.PROMPT_GET;
            case COMPLETE -> GovernanceHook.Operation.COMPLETION;
            default -> throw new IllegalStateException("no hook operation for method " + method);
        };
    }

    private static String promptArgumentError(PromptEntry e, Map<String, Object> args) {
        for (Map.Entry<String, Object> a : args.entrySet()) {
            if (!e.argumentNames().contains(a.getKey())) {
                return "argument '" + a.getKey() + "' is not declared by this prompt";
            }
            if (!(a.getValue() instanceof String)) {
                return "prompt argument '" + a.getKey() + "' must be a string";
            }
        }
        for (String r : e.requiredArguments()) {
            if (!args.containsKey(r)) {
                return "required prompt argument '" + r + "' is missing";
            }
        }
        return null;
    }

    private static boolean allStrings(Map<String, Object> m) {
        for (Object v : m.values()) {
            if (!(v instanceof String)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private void notifyListeners(Trace t, Decision d, boolean reachedBackend) {
        if (settings.listeners().isEmpty()) {
            return;
        }
        DecisionListener.Event e = new DecisionListener.Event(t.requestId, t.tc.traceId(), t.method, "content", ToolClass.READ.name(),
                t.binding == null ? "" : t.binding.settings().sourceId(), d.allowed() ? DenyCodes.ALLOWED : d.code(), d.step(),
                reachedBackend, clock.millis() - t.startMillis,
                new DecisionListener.Span(t.tc.spanId(), t.tc.parentSpanId(), t.startMillis));
        io.github.vaquarkhan.aegis.engine.observe.Listeners.notify(settings.listeners(), e);
    }

    private Outcome internal(Trace t, RuntimeException e) {
        LOG.log(System.Logger.Level.ERROR, "content governance failed for " + t.requestId, e);
        return deny(t, Decision.deny(DenyCodes.POLICY_DENIED, DenyCodes.STEP_POLICY, "internal governance error"));
    }

    private Outcome deny(Trace t, Decision d) {
        if (t.permit != null && t.binding != null) {
            breakers.release(t.binding.settings().sourceId(), t.permit);
            t.permit = null;
        }
        auditBestEffort(record(t, "DECISION", d));
        notifyListeners(t, d, false);
        return new Outcome(t.requestId, d, null);
    }

    private Outcome fail(Trace t, String code, int step, String message, boolean reachedBackend) {
        if (t.permit != null && t.binding != null) {
            breakers.release(t.binding.settings().sourceId(), t.permit);
            t.permit = null;
        }
        t.reachedBackend = reachedBackend;
        // Redact secret shapes in a backend failure message before it becomes the caller-visible reason
        // and the audit record, exactly as successful content is redacted on the way out.
        String safeMessage = message == null
                ? "backend failure"
                : String.valueOf(settings.outputGuard().redactAll(message).value());
        Decision d = Decision.deny(code, step, safeMessage);
        auditBestEffort(record(t, "EXECUTION_RESULT", d));
        notifyListeners(t, d, reachedBackend);
        return new Outcome(t.requestId, d, null);
    }

    private void auditBestEffort(Map<String, Object> rec) {
        try {
            audit.append(rec);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "audit journal unavailable for " + rec.get("event") + " " + rec.get("requestId"), e);
        }
    }

    private Map<String, Object> record(Trace t, String event, Decision d) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ts", clock.instant().toString());
        r.put("gateway", settings.gatewayName());
        r.put("event", event);
        r.put("requestId", t.requestId);
        r.put("clientRequestId", "");
        r.put("traceId", t.tc.traceId());
        r.put("spanId", t.tc.spanId());
        if (t.tc.parentSpanId() != null) {
            r.put("parentSpanId", t.tc.parentSpanId());
        }
        r.put("protocolVersion", safe(t.protocolVersion));
        r.put("actor", t.caller.actor());
        r.put("effectiveSubject", t.caller.effectiveSubject());
        r.put("issuer", t.caller.issuer());
        r.put("authMethod", t.caller.authMethod());
        if (!t.caller.actorChain().isEmpty()) {
            r.put("actorChain", t.caller.actorChain());
        }
        r.put("tool", t.method);
        r.put("toolClass", ToolClass.READ.name());
        if (t.binding != null) {
            r.put("source", t.binding.settings().sourceId());
        }
        if (t.action != null) {
            r.put("resources", t.action.resources());
            r.put("argsDigest", t.action.argsDigest());
        } else if (t.resourceLabel != null) {
            r.put("target", safe(t.resourceLabel));
        }
        r.put("dryRun", false);
        if (t.roundTrip.isRetry()) {
            r.put("roundTripRetry", true);
        }
        r.put("outcome", d.auditOutcome());
        if (d.denied()) {
            r.put("code", d.code());
            r.put("step", (long) d.step());
            r.put("reason", d.reason());
        }
        if (t.reachedBackend != null) {
            r.put("reachedBackend", t.reachedBackend);
        }
        if (t.resultDigest != null) {
            r.put("resultDigest", t.resultDigest);
            r.put("redactions", (long) t.redactions);
        }
        if (t.screening != null) {
            r.put("screeningMatch", t.screening);
        }
        if (!t.hookAudit.isEmpty()) {
            r.put("hookAudit", List.copyOf(t.hookAudit));
        }
        r.put("durationMs", clock.millis() - t.startMillis);
        return r;
    }

    private void event(String type, String source, String name, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", "CONTENT");
        m.put("type", type);
        m.put("source", source);
        if (name != null) {
            m.put("name", safe(name));
        }
        m.put("detail", detail == null ? "" : safe(detail));
        events.accept(m);
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 200; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return sb.toString();
    }

    private String newRequestId() {
        byte[] b = new byte[12];
        random.nextBytes(b);
        return "req_" + HexFormat.of().formatHex(b);
    }

    /** Mutable per-request trace; confined to one thread. */
    private static final class Trace {
        final String requestId;
        final CallerIdentity caller;
        final String method;
        final String protocolVersion;
        final TraceContext tc;
        final long startMillis;
        final List<String> hookAudit = new ArrayList<>();
        Binding binding;
        Action action;
        String resourceLabel;
        RoundTrip roundTrip = RoundTrip.NONE;
        CircuitBreakers.Permit permit;
        String screening;
        String resultDigest;
        int redactions;
        Boolean reachedBackend;

        Trace(String requestId, CallerIdentity caller, String method, String protocolVersion, String traceparent, long startMillis) {
            this.requestId = requestId;
            this.caller = caller;
            this.method = method;
            this.protocolVersion = protocolVersion;
            this.tc = TraceContext.continueFrom(traceparent);
            this.startMillis = startMillis;
        }
    }
}
