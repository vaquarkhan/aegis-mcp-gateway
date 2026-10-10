<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# Aegis 2.0 security

Aegis sits between a language model and production data infrastructure. This page lists what it
promises, how each promise is enforced and tested, what it does not promise, and how to report a
problem. The repository-wide policy (supported versions, disclosure timeline) is in the root
[SECURITY.md](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/SECURITY.md). The HTML [security and threat-model guide](guide/security.html) is the
Pages entry for reviewers. The product [threat model](THREAT-MODEL.md) and the dated
[security validation record](evidence/SECURITY-VALIDATION.md) accompany this page.

## Reporting a vulnerability

Report privately, never in a public issue: open a private security advisory at
<https://github.com/vaquarkhan/aegis-mcp-gateway/security/advisories/new>. Include the version or
commit, the configuration with secrets removed, reproduction steps and the impact you see. You get
an acknowledgement within five business days. Please allow 90 days before public disclosure, or a
shorter time we agree on together.

## Security properties

Each row names the property and the test that checks it. Breaking one is a vulnerability.

| # | Promise | Enforced by | Tested in |
| --- | --- | --- | --- |
| S1 | Every protected MCP, admin and metrics request needs a host-authenticated identity (JWT/JWKS, hashed static token, or Knox Subject). Health, readiness and OAuth discovery are the only intentionally unauthenticated own-host endpoints; Knox health follows its topology route. | `AegisServer`, `McpServerHandler`, `JwtVerifier`, `StaticTokenIdentity`, `KnoxIdentityBridge`; no anonymous MCP mode exists | `GatewayHostTest`, `KnoxExtensionTest` |
| S2 | JWTs: algorithm allow list (HS256, RS256, ES256), exact `iss`, required `aud`, bounded skew, `alg: none` refused, JWKS cache bounded and fail-closed | `JwtVerifier` | `GatewayHostTest` (forged signature, `alg: none`), `JwtVerifierTest` (issuer, audience, expiry, skew, algorithm confusion) |
| S3 | The caller's inbound token is never forwarded to a protected backend. Backend credentials are resolved only after governance and audit. With `credentials.type: token-exchange`, the verified inbound JWT is sent only to the configured, egress-checked token endpoint to obtain a backend-audience token; it is not stored in `CallerIdentity`, audit, hooks, or policy input. | `CredentialProvider`, `TokenExchangeCredentials`, `SubjectToken` call sites in `GovernanceEngine` / `ContentGovernor` | `GovernanceEngineTest`, `GatewayHostTest` (`auth=null` at a static-credential backend), `TokenExchangeCredentialsTest`, Keycloak evidence |
| S4 | Default deny: no policy rule, no call; an exception in any control is a denial. Glob evaluation is bounded, and bound exhaustion denies rather than acting as an unmatched allow or protected-resource miss. | `RulePolicy`, `GovernanceEngine.call`, `Glob` | `GovernanceEngineTest`, `ControlsTest` |
| S5 | MUTATE/DESTRUCTIVE need a per-call approval bound to the caller and the full action (gateway name, tool, class, source, typed resources, argument digest, pinned definition digest, and the tool's required action scopes when it has any); single use; the requester cannot approve, even under a differently spelled name (NFKC and case folded), and delegated identities cannot approve; the approver sees redacted, bounded arguments and the dry-run preview; a decision the journal cannot record is not made. Once promotion starts, a consumed approval remains consumed if intent journaling later fails, so the caller must obtain a fresh approval before retrying. | `ApprovalService`, `Action.binding`, `GovernanceEngine.approverEvidence` | `GovernanceEngineTest`, `SeparationOfDutiesTest`, tutorial step 7 |
| S6 | DESTRUCTIVE needs a signed dry-run receipt for the same full action binding as S5; single use; a receipt from another gateway or from before a tool-definition change is refused. Once promotion starts, a consumed receipt remains consumed if intent journaling later fails, so the caller must issue a fresh dry run before retrying. | `VrpService` | `GovernanceEngineTest`, `SeparationOfDutiesTest`, `RealSparkConnectServerTest` |
| S7 | No request executes unless its intent was journaled; the journal is hash-chained and verifiable offline. Promotion is at-most-once: approval and receipt nonces are consumed before `ALLOW_INTENT`; if that append fails, no credentials or backend call occur, but any consumed controls must be replaced before retrying. | `HashChainFileAuditSink`, `AuditChainVerifier` | `HashChainFileAuditSinkTest`, `GovernanceEngineTest`, `ContentGovernanceTest` |
| S8 | HTTP egress: every destination presented to `EgressGuard` and every DNS answer returned during that check is classified; private, loopback, link-local and metadata ranges need an explicit CIDR; redirects are not followed; nested HTTP(S) argument URLs are checked. Plugin sources report their configured endpoints (`ToolSource.configuredEndpoints`): Kafka Admin bootstrap servers (`tcp://host:port`) and the Spark Connect server join the allow list like an HTTP `baseUrl` and are checked on every call. Kafka listener addresses learned after bootstrap are a separate topology hop that needs Kafka listener configuration and network policy. DNS check/connect timing has the limit below. | `EgressGuard`, `BoundedHttp`, `ToolSource.configuredEndpoints` | `ControlsTest`, `ExistingMcpServerProxyTest` (redirect to 169.254.169.254), `ExampleConfigsTest` (nested metadata URL, real Kafka broker), `KafkaAdminEgressTest` |
| S9 | Tool and prompt definitions are pinned; a changed or poisoned definition is quarantined. Under a signed server catalog, a prompt pin belongs to the source record that advertised it, so another source's same-named pin cannot authorize it. | `CatalogIntegrity`, `ToolCatalog`, `ContentGovernor` | `GovernanceEngineTest`, `ExistingMcpServerProxyTest`, `ContentGovernanceTest`, `ContentGovernorTest.aSignedPromptPinCannotAuthorizeTheSamePromptFromAnotherSource` |
| S10 | Request/body agreement: 2026 headers must equal the body; duplicate JSON keys are rejected; size and depth bounded during parsing | `McpServerHandler`, `Json` | `McpServerHandlerTest`, `JsonTest` |
| S11 | Output is bounded and every text string redacted for secret shapes before it reaches the model; binary parts (image and audio `data`, any `blob`) are withheld unless `output.allowBinary` (tool results) or the source's `resources`/`prompts` `allowBinary` is set, and pass unchanged when allowed | `OutputGuard`, `ContentGovernor.guard` | `OutputGuardTest`, `ControlsTest`, `ContentGovernanceTest`, `ContentGovernorTest` |
| S12 | Injection phrases in arguments, tool/prompt definitions and returned resource/prompt content are screened (normalised, base64-aware) | `InjectionScreen` | `ControlsTest`, `ContentGovernanceTest`, tutorial step 9 |
| S13 | Resources are routed to exactly one source and cannot return URIs outside that source's patterns | `ContentGovernor.route`, `readResult` | `ContentGovernanceTest` |
| S14 | Sessions (2025 era) are caller-bound; another principal gets 404; capacity bounded per caller | `SessionRegistry` | `McpServerHandlerTest` |
| S15 | No server-to-client request (sampling, elicitation) reaches the model through the gateway | `HttpMcpClient` SSE handling | `ExistingMcpServerProxyTest` |
| S16 | Spark identifiers cannot become SQL; a token for Spark Connect is sent only over TLS | `SparkConnectToolSource.quote`, `SparkConnectSettings` | `SparkConnectSourceTest`, `RealSparkConnectServerTest` |
| S17 | Admin API: authenticated, approver groups only for decisions and catalog changes, delegated callers cannot accept a quarantined tool or prompt, acceptance intent is journaled before the pin changes, browser Origin checked (CSRF) | `AdminApi` | `GatewayHostTest` (self-approval 403), `AdminApiTest` (Origin, non-approver, delegated accept 403, journal failure 503, catalog and prompt accept) |
| S18 | Observability never weakens enforcement: listeners cannot change decisions, their failures are ignored, metrics carry no caller or argument values | `DecisionListener`, `DecisionMetrics` | `ObservabilityTest` |
| S19 | An extension hook cannot fail open, cannot invent a deny code, and in `AUDIT` mode cannot alter the response at all; a hook that throws, returns nothing or times out denies (or withholds) when enforcing | `HookRunner`, `HookBinding` | `HooksTest`, `ContentHooksTest` |
| S20 | A remote hook endpoint must be on the egress allow list before the gateway will start, and it receives no arguments or results unless the operator opts in | `RemoteHook`, `GatewayFactory.remoteHook` | `RemoteHookTest` (startup refusal, payload contents) |
| S21 | Own-host route admission happens after identity resolution but before request-body buffering and MCP/admin dispatch; CORS preflight is exact-origin and cannot widen route access | `OwnServerPerimeter`, `OwnServerSecuritySettings` (`host.routes`, `host.cors`), `AegisServer` | `OwnServerPerimeterTest`, `GatewayHostTest.ownHostRouteGroupsAndCorsRunBeforeMcpDispatch`, `corsPreflightCannotAdvertiseAnOriginTheMcpHandlerRejects` |
| S22 | A `QUARANTINED` source contributes no callable tool, even if it was otherwise configured and discovered. With `serverCatalog`, an offline Ed25519-signed register (verified before parsing) decides at startup which sources may start: an unlisted source, or an ACTIVE one whose configuration block no longer matches its signed digest, stops the gateway; a QUARANTINED or REVOKED record means the source is never constructed, discovered or added to egress; only the tools a record pins are bound; pinning becomes signed-only (`REQUIRE_PINS`, no pin file, no run-time acceptance). A break-glass order signed with a separate key can only reduce capability by quarantining or revoking named sources, inside a window of at most 24 hours, and refuses startup when expired. A host trust anchor (`--require-server-catalog`, `AEGIS_REQUIRE_SERVER_CATALOG_KEY` or `-Daegis.requireServerCatalogKey`, an absolute path read from the host, not the configuration) makes the catalog mandatory and fixes its review key. A signed `serial` on the catalog, the catalog manifest or a break-glass order is kept as a high-water mark in the state store, and an older or unnumbered file of that kind is then refused | `GatewayFactory.SourceBuild.bind`, `sources[].trust`, `SignedServerCatalog`, `BreakGlassContainment`, `SignedSerial`, `CatalogIntegrity` (signed-only), `GatewayFactory.requiredCatalogKey` | `GatewayFactoryTest.sourceTrustQuarantineHidesEveryToolUntilOperatorConfigActivatesTheSource`, `SignedServerCatalogTest`, `SignedSerialTest`, `ServerCatalogConfigTest`, `ServerCatalogFlowTest`, `DerbyStateStoreTest.theAcceptedSignedSerialSurvivesARestartSoAnOlderFileIsRefused` |
| S23 | `derby-local` state persists nonces and rate windows through a restart of one process and preserves atomic take/rate semantics. Derby's embedded lock keeps a second process out | `DerbyStateStore` in `aegis-state-derby` | `DerbyStateStoreTest` (including a real second JVM), `GatewayHostTest.derbyLocalStateProviderIsDiscoveredByTheOwnServer` |
| S24 | Published OAuth protected-resource metadata names the same resource the JWT audience is checked against, unless the operator explicitly declares a mismatch; every 401 points at the metadata | `ProtectedResourceMetadata`, `OwnServerSecuritySettings` (`host.oauth`) | `OwnServerSecuritySettingsTest.oauthMetadataRequiresAudienceBindingUnlessExplicitlyEscaped`, `ProtectedResourceMetadataTest.theEndpointIsServedAndEvery401PointsAtIt` |
| S25 | The `project-governance` hook denies configured protected resources to every caller and requires a bounded, control-character-free evidence reference for configured classes | `ProjectGovernanceGuardrail` | `ProjectGovernanceGuardrailTest` |
| S26 | Optional per-route OAuth scopes (`host.routes.mcpScopes`, `adminScopes`, `metrics.requiredScopes`): a valid token without every required scope gets 403 `insufficient_scope` naming the scope to request; `scope` and `scp` are never merged; a scope is never a substitute for route groups; misconfiguration fails at startup. Per tool, `actionScopes` requires exact verified scopes at step 2, before resource scopes, policy, approval, credentials or the backend; identities that establish no OAuth grants (static, SPIFFE, stdio, Knox) carry none and are denied such tools. On the own host a JWT caller denied for missing action scopes gets 403 with an `insufficient_scope` challenge naming the route scopes and the tool's scopes (MCP step-up) | `JwtVerifier.verifyWithScopes`, `OwnServerPerimeter`, `OwnServerSecuritySettings`, `GovernanceEngine`, `GatewayFactory` (`tools[].actionScopes`), `ScopeChallenge`, `AegisServer.stepUp` | `ScopeEnforcementTest`, `ProtectedResourceMetadataTest.overTheWireAValidTokenWithoutTheRequiredScopeGets403InsufficientScope`, `ProtectedResourceMetadataTest.aToolCallWithoutTheToolsActionScopesGetsAStepUpChallengeNamingThem`, `GovernanceEngineTest.missingActionScopeDeniesBeforeCredentialsOrBackendAndFullGrantProceeds`, `GatewayFactoryTest.actionScopesAreTrustedToolRequirementsAndDenyBeforeBackendAccess`, `JwtVerifierTest.validTokenMapsSubjectGroupsIssuerAndVerifiedScopes` |
| S27 | Host separation: only `aegis-knox3-extension` imports Knox; all Knox dependencies are `provided`; the own-host jar contains no Knox adapter or class; the Knox bundle contains no Knox implementation or own-host perimeter; both contain the same Aegis `GovernanceEngine` | module POMs and package boundaries | `KnoxPackagingBoundaryTest`; `tools/provenance/compare_knox_source.py` |
| S28 | `GET /audit/verify` verifies only the fixed configured journal, over its last committed prefix and without blocking appends; a dedicated audit-verifier group is required, and HTTP responses reveal only `verified:true|false`. One journal file has one writer: a second writer in the same or another process is refused | `AdminApi`, `HashChainFileAuditSink.verify`, `HashChainFileAuditSink` writer lock | `AdminApiTest`, `HashChainFileAuditSinkTest` |
| S29 | With `credentials.type: token-exchange`, the caller's inbound JWT is sent only to the configured token endpoint (which must pass egress at startup), never to a backend, never into `CallerIdentity`, audit, hook requests or policy input; a failed exchange denies with no fallback; an inspectable three-part issued JWT must carry a non-blank string `sub` equal to the caller subject, or the call is denied; a compact encrypted JWE is denied because its subject cannot be checked. Opaque tokens are passed on as issued. | `TokenExchangeCredentials`, `SubjectToken`, `AegisServer.verifiedJwt` | `TokenExchangeCredentialsTest`; Keycloak 26.8.0 run ([evidence](evidence/TOKEN-EXCHANGE-KEYCLOAK.md)) |
| S30 | Schema `$ref`s are resolved only inside the tool's own schema; a remote reference is never fetched and denies the call; reference cycles are bounded. A response filter is a JSON Pointer with no functions or variables, and no route registers a backend, or reads, replaces or bypasses the server catalog or a break-glass order, at run time | `SchemaValidator`, `JsonPointer`, `AdminApi` | `SchemaRefTest`, `JsonPointerExpressionTest`, `AdminApiTest.noRouteRegistersABackendAtRunTime` |
| S31 | An A2A agent card cannot redirect calls: the JSON-RPC endpoint must be on the card's origin or equal the pinned `endpointUrl`, and it is part of the pinned tool definition | `A2aToolSource` | `A2aToolSourceTest.cardTrustRulesFailClosed` |

## What Aegis does not promise

These are documented limits, not vulnerabilities:

- **Prompt injection is limited, not prevented.** Phrase screening catches known patterns and
  simple obfuscation; a novel phrasing can pass. Approval and least privilege are the real defence
  for anything that matters.
- **Redaction is heuristic.** Default patterns catch common secret shapes (cloud keys, private
  keys, bearer tokens, JWTs); it is not a DLP product.
- **State-store deployment mode.** `memory` is still one-process and non-durable. `derby-local` is
  durable without an external database installation, but it deliberately allows exactly one writable
  Aegis process. `postgres` is the shared PostgreSQL provider for approvals, receipts, rate and budget
  windows; its real integration profile must pass against the target PostgreSQL deployment before it is
  claimed as production-verified. It does not yet make TOFU catalog pins or circuit breakers shared.
- **Plugin egress.** The Kafka Admin and Spark Connect sources pass their configured endpoints through
  the egress check on every call (S8). Their clients resolve DNS themselves, so an answer can differ
  between the check and the connect. A Kafka bootstrap broker can also advertise additional broker
  listener addresses, which the Kafka client reaches as a later topology hop. Constrain those addresses
  with Kafka listener configuration and network policy. The JDBC and ZooKeeper sources open their own sockets to their
  configured URL or connect string. Restrict these at the network layer too.
- **A remote hook endpoint is checked at startup, not per request.** Its URL passes the egress guard
  before the gateway serves traffic, but the hook's own call is not re-checked on each request. Use an
  IP literal or a CIDR for the endpoint if DNS rebinding is in your threat model. A hook enabled with
  `includeArguments` or `includeResult` is a deliberate data path out of the gateway.
- **Backend identity.** By default a backend sees the gateway's service credential. With
  `credentials.type: token-exchange` (own host, JWT callers) each call carries a per-caller token
  instead (S29). `secrets.source` (`env`, `file`, `vault`) changes where credentials are read from,
  not whose identity the backend sees; values are read at start-up, so rotation needs a restart.
- **Trace ids are correlation, not authentication.** A caller chooses its own `traceparent`.
- **Signed catalog manifests, server catalogs and break-glass orders.** Rollback protection needs a
  signed `serial` and lasts as long as the state store: across restarts with `derby-local` or
  `postgres`, for one process with in-memory state. Unnumbered files are judged by signature only, so
  an older one signed by the same key still verifies; retire the key to revoke it. Removing a
  break-glass order from the configuration lifts the containment at the next restart, so whoever can
  write the configuration can end a containment early; it cannot activate anything the review key did
  not sign. None of these files is reloaded while the gateway runs. Without the host trust anchor
  (`--require-server-catalog` or `AEGIS_REQUIRE_SERVER_CATALOG_KEY`), the catalog's public key is named
  by the configuration, so whoever can write the whole configuration can drop the `serverCatalog`
  block or point it at a key of their own; set the anchor where that matters. Per-tool `actionScopes` trust the identity
  provider's `scope`/`scp` claim as issued; Aegis does not check what the provider was allowed to grant.
  Audit checkpoints are only as strong as the storage of the
  checkpoint file, and the checkpoint file itself is not hash-chained.
- **Mutual TLS on the own host is opt-in.** `host.tls.clientAuth: require` trusts only `clientCaFile`;
  `identity.type: spiffe` maps verified X.509-SVIDs to callers. Trusted-proxy identity assertion is not
  built in, and SVIDs are not fetched from a SPIRE agent: the keystore and bundle are files you rotate.
- **HTTP backends: DNS is checked, not pinned.** `EgressGuard` resolves the backend name and checks
  every answer, then the JDK HTTP client opens its own connection by name. The JVM's address cache
  usually hands both the same answer, but nothing in Aegis guarantees it, so a rebinding answer can
  land between check and connect. If that is in your threat model, allow IP literals or CIDRs, or
  put an egress proxy or network policy under the gateway as well.
- **stdio sources are not sandboxed.** The child process gets a minimal environment and only an
  operator-configured command, but it runs with the gateway's operating-system privileges and its
  own network access is not governed. Run it in a container or under a restricted account.
- **OAuth scopes exist at both route and action levels.** `host.routes.mcpScopes` gates the whole MCP route, while optional per-tool `actionScopes` require exact verified JWT scopes at step 2. On the own host, a valid JWT missing action scopes receives a 403 `insufficient_scope` step-up challenge naming the route and tool scopes. Groups, typed resources, and policy still apply.
- **Upgrading invalidates outstanding approvals and receipts.** Tokens now bind the full action
  (S5, S6), so an approval token or dry-run receipt issued by an older build is refused. Drain
  pending approvals before an upgrade.

## MCP threat coverage

Two public lists name the MCP-specific threats: the
[OWASP MCP Top 10](https://owasp.org/www-project-mcp-top-10/) (beta at the time of writing) and the
[MCP security best practices](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices)
in the 2025-11-25 specification. The tables below map each of the ten to a control or a stated limit at
the gateway boundary; Aegis does not eliminate any of them. The status words mean:

- **Strong gateway controls:** multiple enforced controls and tests cover the part visible to Aegis.
- **Partial:** Aegis reduces the risk, but a material path remains outside or below the gateway.
- **Limited:** the available detector is heuristic and bypasses are expected.
- **External:** prevention mainly belongs to inventory, deployment or network controls outside Aegis.

### OWASP MCP Top 10

| Item | Coverage | Promises | What is left to you |
| --- | --- | --- | --- |
| MCP01 Token mismanagement and secret exposure | Strong gateway controls | S3 keeps the caller token out of protected backends and `CallerIdentity`; S11 redacts common secret shapes; stdio children get a minimal environment; raw audit arguments are off by default. With configured token exchange, the inbound JWT is sent only to the egress-checked token endpoint. | Credential storage, issuance, rotation and revocation remain operator/IdP duties. Redaction is pattern based, and an operator can deliberately enable sensitive data paths. |
| MCP02 Privilege escalation via scope creep | Strong gateway controls | S4 default deny, per-group exposure, typed-resource scopes, S5 per-call approval, S26 OAuth route scopes, and unlisted tools treated as `DESTRUCTIVE` | IdP group lifecycle remains external. OAuth scopes apply per route and, through `actionScopes`, per tool, with a step-up challenge on the own host; policy and typed resources add least privilege per action. |
| MCP03 Tool poisoning | Strong only with trusted initial pins | S9 pins definitions and quarantines changes; S12 screens definitions/content; hints only raise class; S17 requires a non-delegated approver and journal-first acceptance | Use `catalog.mode: REQUIRE_PINS`. In TOFU mode a poisoned first definition can become the initial pin if heuristic screening misses it. Pinning detects change, not truth. |
| MCP04 Supply chain and dependency tampering | Partial | S9 and S22 cover downstream definitions/source admission; S8 limits guarded HTTP destinations; the release profile writes a CycloneDX SBOM | No vulnerability gate checks the SBOM, and the provenance scanner required by `AGENTS.md` has not run. Build-tool, dependency-repository and artifact-signing controls remain release duties. |
| MCP05 Command injection and execution | Partial | No shell command is built from tool arguments; stdio commands are operator configuration; typed paths refuse traversal; Spark identifiers are quoted; SQL gets the `sql-readonly` guard and a single-statement JDBC path | A configured stdio command runs with gateway privileges, and an approved backend job can execute code. Aegis does not sandbox child processes or backend runtimes. |
| MCP06 Prompt injection via contextual payloads | Limited | S12 screens arguments, definitions and returned content; S11 bounds/redacts output; S5 gates state changes | Screening is heuristic and novel payloads can pass. Least privilege, typed resources and independent approval contain impact but do not prevent prompt injection. |
| MCP07 Insufficient authentication and authorization | Strong gateway controls | S1, S2, S14, S17, S21, S24 and S26 enforce protected-route identity, token checks, session binding, admin authorization, route admission, audience binding and optional scopes | Knox mode relies on the configured Knox provider chain and topology. Identity-provider policy, credential revocation and upstream group correctness remain external. |
| MCP08 Lack of audit and telemetry | Strong local evidence | S7 journals intent before execution in a verifiable hash chain; approval decisions and catalog acceptance are journaled; S18 supplies bounded metrics/traces | Ship and monitor the journal. The local chain has no external head anchor, built-in rotation or SIEM alerting, so a host owner who can rewrite all history remains a deployment concern. |
| MCP09 Shadow MCP servers | External with partial containment | S8 prevents Aegis from reaching unlisted guarded HTTP targets; S22 can quarantine a configured source; the catalog inventories what this gateway exposes | Aegis cannot discover or stop MCP servers deployed outside it. Asset inventory, endpoint controls and network policy own prevention. |
| MCP10 Context injection and over-sharing | Partial at the gateway boundary | `tools/list` is caller-filtered with private cache scope; S14 binds sessions; S13 routes resources to one source; S11 bounds/redacts output; S15 rejects server-to-client sampling/elicitation | Aegis holds no model memory or client context. A client can still retain, combine or disclose content after receiving it. |

### MCP specification security best practices

| Practice | Status | How |
| --- | --- | --- |
| Confused deputy | Implemented for the gateway-as-deputy case; the OAuth-proxy case does not arise | The deputy risk for a gateway is executing a caller's request with the gateway's own, broader backend credential. Aegis authorizes every call on the tool, class, typed resources and arguments in the MCP body before it resolves that credential, and the caller's token is never forwarded (S3). Route authorization alone does not prevent this: Apache Knox `AclsAuthz` admits by user, group and IP and never reads the JSON-RPC body, so it admits `list_*` and `delete_*` identically. `KnoxAclRouteScopeTest` runs the real Knox 3.0.0 filter to prove that, and proves Aegis denies the destructive call so the backend never sees it. The OAuth-proxy form of the problem (per-client consent for a static client ID) does not arise: Aegis runs no OAuth proxy, no dynamic client registration and no consent screen, and never issues tokens. |
| Token passthrough | Implemented for protected backends | Tokens must carry the configured audience, and `oauth.resource` must equal it unless declared otherwise (S24). The inbound caller token is never forwarded to a protected backend (S3). With configured RFC 8693 exchange it is sent only to the egress-checked token endpoint to obtain a backend-audience token. |
| Server-side request forgery | Partial | Every destination presented to `EgressGuard` and every DNS answer returned during that check is classified; private and metadata ranges need an explicit CIDR, redirects are not followed, and nested HTTP(S) URLs are found (S8). Published metadata URLs must be https or loopback http. Kafka Admin bootstrap endpoints and Spark Connect endpoints are checked on every call. DNS is checked but not pinned, Kafka advertised broker listeners and the JDBC and ZooKeeper sources need network policy. |
| Session hijacking | Implemented for Aegis sessions | Every protected request is authenticated; a session is never authentication. Session ids come from `SecureRandom` and are caller-bound; another principal gets 404 (S14). 2026-era clients use no protocol session. |
| Local MCP server compromise | Partial | stdio commands come only from operator config, never from a client; the child gets a minimal environment; the own listener binds 127.0.0.1 by default, needs a token and checks browser Origin. The child is not sandboxed (see the limits above). |
| OAuth authorization URL validation | Implemented for metadata Aegis publishes | This is mainly a client duty. Aegis refuses to publish an authorization-server or documentation URL that is not https (or loopback http), so it never hands a client `javascript:`, `data:` or `file:` URLs. Aegis does not run an authorization UI. |
| Scope minimization | Implemented per route and, optionally, per tool | S26: a valid token without the route's scope gets 403 `insufficient_scope` with the scope to request, and the 401 challenge names it up front. A tool with `actionScopes` additionally needs those exact verified scopes, or the call is `SCOPE_DENIED` before policy and the backend. On the own host, a valid JWT missing action scopes also gets a 403 `insufficient_scope` step-up challenge naming the route and tool scopes. Groups, typed resources and policy still apply on top. |

## Threat model and validation evidence

- **Threat model:** [THREAT-MODEL.md](THREAT-MODEL.md): trust-zone diagram, DFD, STRIDE by zone,
  detailed TM01-TM10 abuse cases, OWASP MCP handling for all ten items, and residual assumptions.
- **Historical security validation record:** [evidence/SECURITY-VALIDATION.md](evidence/SECURITY-VALIDATION.md):
  command, environment, reported 227-test suite, per-class evidence catalogue, and explicit non-claims.
  The record is unattested and not bound to the current tree by commit/tree identifiers and immutable
  raw output, so it is not current candidate evidence.
- **Release claims index:** [evidence/CLAIMS-MATRIX.md](evidence/CLAIMS-MATRIX.md).
- **Framework mappings:** [GOVERNANCE-MAPPING.md](GOVERNANCE-MAPPING.md) (OWASP LLM and Agentic Top 10).

## Hardening checklist

1. Use JWT identity with `jwksUri` and TLS (`host.tls`), or run inside Knox. Static tokens are for development.
2. Keep `listen.host: 127.0.0.1` unless a network path is needed; never expose plaintext on a shared network.
3. Set `approval.keyEnv` to a random key of at least 32 bytes and keep approver groups small and non-delegated.
4. Leave unlisted tools at `DESTRUCTIVE` or use `unlistedTools: HIDE`; classify every tool explicitly.
5. Use `catalog.mode: REQUIRE_PINS` with reviewed inline pins or a signed catalog manifest for production catalogs, and always for sources that expose `MUTATE` or `DESTRUCTIVE` tools. Do not use a shared writable pin file as production authority. TOFU and `catalog.pinFile` are lower-assurance evaluation modes because the first definition seen becomes the pin.
6. Keep `egress.allowCidrs` minimal; never add `169.254.0.0/16`.
7. Keep `screening.*` at `ENFORCE`; add organisation-specific phrases and redaction patterns.
8. Keep `audit.fsync: true`, ship the journal off the host, and run `--verify-audit` on a schedule. Copy the record count and head hash it prints to separate append-only storage; the chain is tamper-evident only relative to a head retained outside the host.
9. Leave `resources`, `prompts`, their `allowBinary` keys and `output.allowBinary` off unless a source needs them.
10. Protect `/metrics` with `host.metrics.allowedGroups` (or the Knox ACL).
11. Contain what the egress guard does not see. The guard checks every HTTP destination Aegis itself contacts. A stdio child process, a JDBC or ZooKeeper connection, or a plugin that opens its own sockets is reached through configuration and its own client library, so run the gateway (or the stdio child) under a default-deny network policy that allows only the configured backends, and as a restricted operating-system account.
12. Give each source its own least-privilege backend credential through `credentials` (or a host `CredentialProvider`), held in environment variables or a secret mount, never in the configuration file. Read-only roles for `READ` SQL tools.
13. Treat `memory` and `derby-local` state as single-process. For several replicas use `state.type: postgres` with one `namespace`. TOFU pins remain per process in `catalog.pinFile`; for identical production pins across replicas, use reviewed inline pins or a signed manifest with `REQUIRE_PINS`. Circuit breakers also stay per replica.
