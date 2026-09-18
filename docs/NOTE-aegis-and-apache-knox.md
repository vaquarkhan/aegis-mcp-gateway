# Aegis and Apache Knox: mapping, reuse, and a phased plan (corrected)

Author: Viquar Khan

Status: working note for incubator / Knox coordination (dev-list discussion with Larry McCay).

## Intention of this document

I am sharing this in the open to build consensus with the community, not to stake out a position.
The goal is to work out together what Aegis should adopt and reuse from Apache Knox and what it
genuinely needs to add for Model Context Protocol (MCP) governance. I would rather inherit Knox's
hardened, proven mechanisms than rebuild them, and this note tries to map exactly which of those we
can lean on.

To be clear about intent: this is not an attempt to re-create, fork, or duplicate Knox. Knox is
excellent at what it does, and where our needs overlap I want to consume Knox (and KnoxIDF) rather
than write a parallel version. What Aegis adds is a narrower, MCP-specific governance layer on top,
and this document exists so the community can tell me where that line should sit, correct anything I
have wrong, and decide together how the two projects best fit.

This is the corrected version of the original note. Following Larry's review, I re-validated every
Knox claim against the Apache Knox source (`apache/knox`, `master`, commit for KNOX-3442). Three
conclusions were wrong. Those are fixed inline below and flagged. I have also added a "Why Aegis and
not just Knox" section and full source, specification, and standards links.

Not a second source of truth for the SPI or deny codes; those remain in code and in
[DESIGN-CONFORMANCE-0.1.md](DESIGN-CONFORMANCE-0.1.md).

The short version: at a high level both systems share a familiar gateway pattern. A caller hits a
single entry point, the request runs through an ordered chain of policy enforcers, it reaches a
backend, and everything is audited. Knox has done this for human and REST clients for years, and I
would rather inherit that hardened machinery than rebuild it. The resemblance, though, is in the
pattern, not the data plane: Knox governs the transport envelope (URL, method, headers), while Aegis
governs the semantics of each tool call inside the JSON-RPC body, for a model that can be
manipulated. So the plan is to reuse Knox's machinery and add the tool-aware governance layer it was
not built to do.

### References used throughout

- Apache Knox: [project](https://knox.apache.org/), [source](https://github.com/apache/knox)
- MCP specification: [transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports),
  [security best practices](https://modelcontextprotocol.io/specification/draft/basic/security_best_practices),
  [governance](https://modelcontextprotocol.io/)
- OWASP: [MCP Top 10](https://owasp.org/www-project-mcp-top-10/),
  [MCP03 Tool Poisoning](https://owasp.org/www-community/attacks/MCP_Tool_Poisoning)
- CoSAI / OASIS: WS4 secure design for agentic systems
- IETF: [RFC 8693](https://www.rfc-editor.org/rfc/rfc8693) token exchange,
  [RFC 8707](https://www.rfc-editor.org/rfc/rfc8707) resource indicators

## How the two projects relate

![Aegis and Apache Knox relationship](assets/aegis-knox-relationship.png)

The picture reads left to right. Human and REST clients go through Knox. AI agents go through
Aegis. The middle column is the set of capabilities Knox has already built and hardened that Aegis
plans to reuse rather than write from scratch.

## Design direction from the dev-list discussion

This summarizes the direction from the dev-list discussion with Larry McCay, whose review reshaped
the plan in a good way.

1. **Lead with a config-driven MCP server proxy.** Discover and integrate MCP servers over MCP
   itself, driven by configuration rather than per-engine code. This mirrors Knox's move from
   code-per-service to a declarative service-definition model.
2. **A break-glass path and mashup servers.** Keep the four deep adapters (Flink, Kafka, Spark,
   Iceberg) as the reference bar for real, tool-aware governance, and let break-glass and mashup
   definitions sit on the same declarative model.
3. **OAuth discovery and delegated agentic identity.** Replace today's token pass-through with RFC
   8693 token exchange and an actor chain, aligned with Knox's new KnoxIDF service (below).
4. **Authorization at the tool and resource level,** with Apache Ranger as a policy decision point.
5. **An MCP server catalog** with an API and UI, following Knox's admin API/UI patterns.

## How the chains line up (corrected)

Knox assembles its chain per topology from declarative service definitions. The usual default policy
order is webappsec, authentication, rewrite, identity-assertion, authorization, dispatch (see
`ServiceDefinitionDeploymentContributor`). Aegis runs a fixed ten-step chain where the first denial
wins and returns a stable code (see `InterceptorChain.preflight` and `Decision` in
`mcp-gateway-core`).

**Release context (verified against the Knox release tags).** The latest Knox release is v3.0.0
(release commit `abbdb527`, "Preparing Apache Knox v3.0.0 Release", commit date 2026-09-02; the
release tag object was created 2026-09-09). This note's Knox checkout is `master`
(3.0.0-SNAPSHOT), 46 commits ahead. Every capability in the mapping table below was confirmed
present in the shipped v3.0.0 release, not just on master: rate limiting (`WebAppSecContributor`
DoSFilter), HA failover (`ConfigurableHADispatch`), ACL gating (`AclsAuthorizationFilter`), SSE
(`SSEDispatch`), dispatch, and audit, plus the RFC 8693 principals (`TokenExchangePrincipal`,
`ActorChainPrincipal`).

**What is NOT released:** the KnoxIDF service (federated OIDC provider, delegation policy admin API,
discovery-metadata grant advertising) is unreleased, targeting Knox 3.1.0 (0 files in v3.0.0;
present only on master). Wherever this note refers to KnoxIDF, treat it as forthcoming in 3.1.0, not
a current released capability. The mapping table itself does not depend on KnoxIDF.

**Correction notice.** In the original note, rows 1, 6, and 7 wrongly said Knox has "none." Knox
supports all three (confirmed in the v3.0.0 release). They are corrected below and marked
*(corrected)*.

| Step | Aegis step and deny code | Knox provider role | Notes |
| --- | --- | --- | --- |
| before 1 | Authentication | authentication and federation | Knox uses Shiro, LDAP, KnoxSSO, pac4j, and HeaderPreAuth (`ShiroDeploymentContributor`, `Pac4jDispatcherFilter`). Aegis uses bearer, token file, and OAuth with JWKS. |
| 1 | Exposure, `NOT_EXPOSED` | topology inclusion + AclsAuthz (analogue) *(corrected)* | Knox only deploys declared services and can gate each per service by user, group, and IP (`AclsAuthorizationFilter`). Aegis differs by not advertising write-locked tools at all. |
| 2 | Scope, `READONLY_CALLER` / `SCOPE_DENIED` | identity-assertion (analogue) | Knox maps a caller to an effective principal and groups (`RegexIdentityAssertionFilter`). Aegis maps to scopes and resource allow lists (job, jar, topic, table). |
| 3 | Policy, `POLICY_DENIED` | authorization | Knox uses an ACL provider by user, group, and IP. Aegis uses a PDP (builtin, OPA, or cedar-lite / HTTP delegate). Ranger is a suggested PDP backend. |
| 4 | Approval, `APPROVAL_REQUIRED` | none | Aegis needs a single-use HMAC approval token for anything not read-only. Knox has no per-request approval gate. |
| 5 | Egress and SSRF, `EGRESS_DENIED` | dispatch whitelist | Knox restricts dispatch targets (`WhitelistUtils`). Aegis adds an unconditional deny of metadata and link-local ranges. |
| 6 | Rate limit, `RATE_LIMITED` | webappsec rate limiting *(corrected)* | Knox has a first-class rate-limiting filter (Jetty DoSFilter) in the webappsec provider (`WebAppSecContributor`), plus HttpClient max-connection caps. Aegis makes it a chain step. |
| 7 | Circuit breaker, `BREAKER_OPEN` | HA dispatch failover/retry *(corrected)* | Knox marks failed endpoints and retries the next replica up to `maxFailoverAttempts` (`ConfigurableHADispatch`). It is not a circuit breaker with cooldown, which is what Aegis adds: a per-tool breaker that opens on repeated timeouts or backend errors and resets through a half-open probe. |
| 8 | Prompt injection scan, `PROMPT_INJECTION` | none | Inbound tripwire and outbound scan. No Knox analogue: Knox's security providers do no request-body threat scanning (the rewrite provider parses bodies only to rewrite URLs, not to inspect for threats). |
| 9 | VRP receipt, `VRP_FAILED` | none | Dry-run receipt for destructive operations. No Knox analogue. |
| none | none | rewrite (inbound/outbound URL) | Knox rewrites URLs to hide cluster internals (`UrlRewriteProcessor`). Aegis exposes typed tools, so there is nothing to rewrite. |
| 10 | Execute and output controls | dispatch | Knox forwards via the dispatch filter (`DefaultDispatch`). Aegis runs the backend, then bounds and redacts output and scans it again. |
| after 10 | Audit, hash chained | audit | Knox uses a Log4j audit context (`Log4jAuditor`). Aegis uses a SHA-256 hash chain, tamper-evident, exposed at `/audit/verify`. |

Steps 1, 2, 3, 5, 6, 7, authentication, execute, and audit have Knox analogues (step 7 is
failover/retry rather than a true breaker). Only steps 4 (approval), 8 (prompt injection), and 9
(VRP) are genuine agent-era additions.

## What we inherit from Knox

Looking harder at the Knox source, there is more to reuse than my first note listed. Being explicit
so we do not accidentally rebuild it.

1. **Delegated identity, RFC 8693 token exchange with an actor chain.** Knox carries the
   delegation record in the JWT `act` claim (`TokenExchangePrincipal`, `ActorChainPrincipal`). The
   `TokenExchangePrincipal` and `ActorChainPrincipal` classes shipped in the released v3.0.0. Larry
   notes Knox 3.1.0 will add KnoxIDF (a federated OIDC provider targeting agentic identity, with
   native integration to enterprise IdPs like Keycloak, Ping, Okta, Entra, and RFC 8693 token
   exchange with nested `act` claims). KnoxIDF is unreleased today, currently on master targeting
   3.1.0, so treat it as the forthcoming identity foundation to build on, not a shipped capability.
2. **Externalized high-availability token state** (`TokenStateService`,
   `PersistentTokenStateService`, `JDBCTokenStateService`).
3. **The pluggable authorization model** (ACL provider; Ranger via a Ranger-side plugin).
4. **The declarative service-definition model.**
5. **Directory hot-reload** (`DefaultTopologyService`).
6. **HA failover dispatch** (`ConfigurableHADispatch`).
7. **Keystore and alias secret store** (`AliasService`, `KeystoreService`, `MasterService`).
8. **Federation with SAML and OIDC** (pac4j).
9. **Service and endpoint discovery** (Ambari and Cloudera Manager monitors).
10. **Admin API, admin UI, and homepage** (`gateway-service-admin`, `gateway-admin-ui`,
    `knox-homepage-ui`).
11. **Configuration injection and topology validation** (`TopologyValidator`).

## Why Aegis and not just Knox

Larry's fair question: what does a separate piece buy over extending Knox? Being honest about what
the facts settle and what they do not.

### What Knox structurally does not do

Verified in source: `AclsAuthorizationFilter` authorizes on the effective principal, groups, and
remote IP, and never reads the request body. No `gateway-provider-security-*` module inspects body
content.

MCP does the opposite. It collapses operations onto a single endpoint and puts the operation, the
tool name, and the arguments inside the JSON-RPC body (MCP transports). So `tools/list` and a
destructive `tools/call` are the same URL and method. Route-level authorization cannot tell them
apart. Governing MCP is a stateful, body-level, tool-aware job, which is a different data plane from
routed REST.

**Proxying MCP versus governing MCP (precise, so it cannot be overstated).** To be exact: Knox can
proxy MCP at the transport layer. In the released v3.0.0, `SSEDispatch` streams `text/event-stream`
for GET and POST, and `DefaultDispatch.createRequestEntity` forwards the JSON-RPC request body to
the backend, so a Knox chain can carry MCP's HTTP and SSE traffic. What Knox as-shipped cannot do is
govern the tool call: `AclsAuthorizationFilter` decides on principal, groups, and remote IP and never
parses the body, so it cannot tell `tools/list` from a destructive `tools/call` when both are the
same POST with the tool name inside the JSON-RPC body. Per-tool, body-level, stateful governance is
a distinct layer that must be added on top. That is the same layer Kong and Traefik added to their
existing gateways, and the layer Aegis provides. So the case for Aegis is a governance data-plane
argument, not a claim that Knox cannot proxy MCP.

### Why this layer cannot live in Knox's architecture

The reason is Knox's own core primitive. In `GatewayFilter.doFilter`, Knox selects the entire policy
chain by URL-template matching, `chains.match(pathWithQueryTemplate)`, and every filter binds to a
URL path (`Holder.template`). Because MCP puts every operation and every tool on a single endpoint,
all MCP calls match the same chain, so the mechanism Knox uses to select and apply policy has no
signal to distinguish tools. The controls MCP governance needs therefore have no place in the model:

- **Per-tool authorization** (allow one tool, deny another on the same server): the tool name is in
  the JSON-RPC body; `chains.match` routes by URL and `AclsAuthorizationFilter` decides on
  principal/groups/IP, never the body.
- **Tool-catalog integrity / poisoning detection:** Knox has no MCP tool-catalog model; pinning a
  tool's schema would require parsing and diffing `tools/list` responses, which is not part of
  Knox's authorization or dispatch path.
- **Argument and content screening:** Knox's security providers do no request-body content scanning,
  and `DefaultDispatch.createRequestEntity` reads the body stream once to forward it, so a
  body-parsing gate would have to buffer every request against the streaming model.
- **Session-scoped state** (single-use approval nonces, per-session budgets): Knox's
  request-processing chain runs per request and has no MCP-session unit of governance state to
  attach these to (`TokenStateService` tracks token lifecycle, not per-session tool-call
  governance).
- **Server-initiated methods** (`sampling/createMessage`, `elicitation/create`): Knox models
  client-to-server REST; methods that flow server-to-client are outside its request model.

**Honesty guard:** none of this is "impossible in Java"; Knox can host a filter that does anything.
The point is these controls are foreign to Knox's routing, authorization, and state model; adding
them means running a stateful, body-parsing, tool-aware engine inside a servlet filter, a different
engine hosted by Knox rather than an extension of it.

### Deeper reasons it should be its own project

Beyond the data-plane point, several architecture and governance reasons favour a separate project.

- **The adversary model is inverted, not just extended.** Knox authenticates a caller and then
  trusts it for the route; its per-request checks re-establish identity, not the intent of the
  payload. Aegis must assume the caller (the model) may be manipulated by prompt injection and keep
  distrusting the content of every tool call, which is why per-request approval and injection
  screening exist. "Authenticate then trust" cannot be safely retrofitted into "authenticate then
  keep distrusting each call"; that is a foundational assumption, not a filter you add.
- **Separation of duties.** A tamper-evident governance and audit layer (the SHA-256 hash chain at
  `/audit/verify`) carries more weight when it is independently governed than when it is a submodule
  of a system it constrains. This is the same logic behind Apache Ranger being its own project
  rather than a Hadoop module.
- **Scoped dependency and attack surface.** Aegis pulls in agent-specific dependencies (JWKS
  validation, OPA and Cedar policy engines, HMAC approval; in source as `JwksJwtValidator`,
  `OpaPdp`, `CedarPdp`, `Approval`). A separate project keeps that supply chain scoped to those who
  need it, instead of enlarging a host gateway's CVE exposure for capabilities most of its users do
  not run.
- **Failure isolation.** As its own process, an Aegis fault cannot take down a host gateway's human
  and REST traffic; folding them into one deployable couples their failure domains.
- **Independent security-response cadence.** A defect in a layer that authorizes irreversible
  machine actions should be able to ship on its own timeline, not be coupled to a host gateway's
  release train, and the reverse.
- **Focused contributor community.** Agent-security review (prompt injection, tool poisoning,
  agentic delegation) draws a different, focused contributor pool; a dedicated project lets each
  community stay focused on what it knows best.
- **Sound architecture boundaries.** Absorbing every capability into one gateway pushes it toward a
  monolithic "god project" that owns REST perimeter security, Hadoop discovery, identity federation,
  and now agentic tool governance in a single deployable and release train.

Keeping Aegis separate respects separation of concerns and clear bounded contexts: Knox owns the
transport and identity perimeter, Aegis owns MCP tool-action governance, and the two integrate
through well-defined seams (a Knox `/ext` adapter and KnoxIDF) rather than through a shared
codebase. Loose coupling between them means each can evolve, be tested, and be reasoned about
independently; tight coupling in one project would make both harder to change safely. It also serves
scalability: the two planes have different load profiles (streaming, long-lived MCP sessions versus
request/response REST) and should be able to scale independently rather than being bound to one
process and one scaling unit.

### The net-new controls, and who prescribes them

These have no Knox analogue and map onto published MCP governance guidance:

| Control | Prescribed by | Aegis mechanism |
| --- | --- | --- |
| Per-request human approval for sensitive actions | CoSAI / OASIS | HMAC single-use `approvalToken` |
| Tool- and resource-level authorization | OWASP MCP Top 10 | per-tool policy over the JSON-RPC call |
| Tool integrity vs poisoning / schema tampering | OWASP MCP03 | schema-digest pinning (`ToolCatalogIntegrity`) |
| Prompt-injection / tool-misuse defense | OWASP; MCP security | inbound + outbound content screening |
| No token pass-through / confused deputy | MCP security best practices | re-mint at the boundary (RFC 8693, RFC 8707) |

**The one sentence:** Knox governs the transport envelope for human and REST traffic; Aegis governs
the semantics of tool actions taken by a model that can be manipulated, against a server that can
lie.

### What is already built (0.1.0)

A ten-step chain with a stable deny taxonomy, the `ToolClass` model (`READ` / `MUTATE` /
`DESTRUCTIVE`) with write-lock and per-request approval, validate-run-promote receipts for
destructive tools, SHA-256 schema-digest pinning, egress/SSRF guarding, rate limiting and budgets, a
SHA-256 hash-chained audit, and four reference-depth adapters (Flink, Kafka, Spark, Iceberg). The
discussion is about where this engine should live, not whether it exists.

### Honesty about what this does and does not settle

- The data-plane point argues for keeping governance logic as a portable, self-contained engine. It
  does not, by itself, decide whether that engine is its own project or a Knox-hosted module. That
  is a strategic and community question, and I want Larry's read on it.
- A gateway enforces the identity, authorization, integrity, egress, and audit controls, but for
  model-layer risks like prompt injection it limits blast radius and enables detection rather than
  fully preventing them. Aegis's injection screen is a coarse tripwire; schema pinning is
  trust-on-first-use unless operators pre-pin digests. Not overstated.

## A model that could work for both

- **Aegis core** = a portable, gateway-agnostic governance engine, independently versioned so it
  tracks the MCP spec and runs next to any MCP server.
- **Aegis-Knox adapter** = a thin `/ext` module that embeds the core, so Knox operators get MCP
  governance in-process with the deployment base and provider services.
- **KnoxIDF integration** = Aegis consumes KnoxIDF for federated OIDC and RFC 8693 token exchange
  with nested `act` claims, replacing the token pass-through Aegis does today.

This is complementary to where Knox is heading: once Knox establishes who an agent acts for
(KnoxIDF), Aegis decides what that agent's specific tool call may do. I am glad to contribute in
whichever shape best serves that goal, and open to the module being the right home if a protocol-fit
prototype comes back clean.

## The plan, divided into phases

### Bootstrap

- Complete IP clearance, name search, ASF infrastructure, and grow the committer base.
- Pull the thin adapters out of the initial contribution and keep the four deep adapters as the
  governance reference bar.
- Draft the declarative MCP server-definition schema and coordinate it, and the SPI, with the Knox
  community given the engine overlap.

### Phase 0.2, identity, high availability, and the proxy foundation

- Build the MCP server proxy and the declarative definition model, with directory hot-reload and
  validation. Reuse from Knox: the declarative model, hot-reload, and validation.
- Extend tool-catalog integrity to discovered tools.
- Replace credential pass-through with RFC 8693 token exchange and an actor chain. Reuse: KnoxIDF.
- Externalize shared state (nonce, rate, budget, audit) behind a shared store.
- Add OAuth discovery. Close the residual DNS-rebinding gap in egress.

### Phase 0.3, authorization depth, federation, and resilience

- Wire Apache Ranger as the PDP for tool- and resource-level authorization.
- Add SAML/OIDC federation. Reuse from Knox: pac4j and KnoxIDF.
- Add failover dispatch across downstream MCP server replicas. Reuse: HA dispatch.
- Break-glass and mashup server definitions; Cedar runtime; CIMD and SPIFFE; VRP proof.

### Phase 0.4 and later

- Build the MCP server catalog API and UI. Reuse from Knox: admin API/UI and homepage patterns.
- Ecosystem items: Atlas lineage, impact preview, dual-control approval, purpose binding, compliance
  mappings, signed catalogs, human-in-the-loop approval console.

## What stays the same

The governance chain, the deny taxonomy, the write lock, and the SPI seams stay stable. Filling a
deferred item replaces a fail-closed stub behind the same config keys; it does not redesign the
gateway.

## Closing

The two projects share a gateway pattern but operate on different data planes: Knox governs the
transport envelope, Aegis governs the semantics of tool calls. Aegis reuses Knox where it makes
sense, identity via KnoxIDF and the mechanisms noted above, and adds the write lock, approval, agent
egress, prompt and VRP checks, and tool integrity that governing AI agents needs. Where the
governance engine should live, standalone, a Knox `/ext` module, or both, is the open question I
want to decide with the community and on the evidence of a protocol-fit prototype.
