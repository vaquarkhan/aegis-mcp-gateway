# Aegis 2.0 and IBM ContextForge: side-by-side feature value review

Date: 2026-11-07.

## Purpose

The question is which ContextForge features would add value to Aegis, and which Aegis does not need. Aegis has a narrow focus:

1. Governing MCP tool calls against Apache data projects (Kafka, Spark, Flink, Iceberg, Hive, ZooKeeper, Calcite, Ignite, ActiveMQ, BookKeeper, Flume and others, plus Apache Knox as an optional front door).
2. MCP governance: who may call which tool, on which resource, with whose approval, and with what record.
3. Responsible AI controls on the agent's actions: human approval, least privilege, data minimisation, provenance and a tamper-evident record.

## What was compared

- **ContextForge.** Source checkout at `contextforge-security-review/`, HEAD `7d34038ca1f2057bf62c3cb27eb90818296b4947`, sparse paths `docs`, `mcpgateway`, `plugins`, `charts`. Claims below cite `docs/docs/architecture/security-features.md` (version 1.0.0-RC-3, "Everything listed here is present in the codebase today") and the `mcpgateway/services` and `plugins` directory listings.
- **Aegis.** `aegis-mcp-gateway-20261104-2.0-review-ready` at base `2f7b944` plus the uncommitted fixes in this review. Every Aegis claim names a class and a runtime test.

Naming note: ContextForge is an IBM open source project (`github.com/IBM/mcp-context-forge`), not an Apache Software Foundation project. Aegis is also not an ASF project. It is an Apache-2.0 licensed project under `io.github.vaquarkhan` that targets Apache data projects.

## The short answer

- **Platform features.** ContextForge is a broad AI gateway and registry: admin UI, multi-provider SSO, email accounts, teams, virtual servers, federation, REST and gRPC translation, an LLM proxy and chat, caching, and about 40 plugins. Most of that is platform scope Aegis deliberately does not carry.
- **Security features ContextForge had that Aegis lacked.** There were seven, all small and generic:
  - DNS-pinned outbound connections.
  - JWT `jti` and revocation.
  - Per-IP limits with lockout.
  - Team quotas.
  - A separate admin surface.
  - Default PII redaction.
  - SIEM export.

  All seven were added to Aegis in this pass (Part L, tests `GAP-L1a` to `GAP-L4d`).
- **Where Aegis goes further.** Aegis is deeper on action governance:
  - Per-call independent approval with separation of duties.
  - Destructive-action dry-run receipts.
  - Tool-definition pinning with a signed server catalog and break-glass containment.
  - A hash-chained journal written before execution, with fail-closed behaviour.
  - Complete-control mode: execution contracts, output policy, artifact and plugin admission, and signed deployment attestation.

  ContextForge's own document lists immutable audit, sandboxed execution and federated attestation as planned, not shipped.
- **Worth considering next.** A few ContextForge plugin ideas match Aegis's responsible-AI focus. They fit as Aegis remote hooks rather than as built-in platform features: content moderation, secrets detection in arguments, LLM Guard, and citation validation. See the last section.

## Side by side

Legend for the "Value for Aegis" column:

| Value | Meaning |
| --- | --- |
| ADOPTED | Was a real gap; implemented and tested in this pass |
| EQUIVALENT | Aegis already covers the need in a different form |
| AEGIS AHEAD | Aegis has a control ContextForge does not ship |
| NOT REQUIRED | Platform scope outside Aegis's focus; adding it would grow attack surface without serving the focus |
| CONSIDER | Fits the focus; recommended as a hook or small addition later |

### Identity and authentication

| Capability | ContextForge | Aegis | Value for Aegis | Why |
| --- | --- | --- | --- | --- |
| JWT bearer validation | HS/RS/ES 256 to 512 | RS256, ES256, HS256 allow list, `crit` refused, HTTPS-only JWKS (`JwtVerifier`) | EQUIVALENT | Smaller allow list is a deliberate hardening choice |
| JWT `jti` and per-token revocation | `TokenRevocation` table; doc states auth paths fail open when the revocation store is unavailable | `requireJti`, `TokenRevocations` in the shared state store, `requireRevocation` fails closed (`GAP-L2a` to `L2c`) | ADOPTED | Stolen-token response; Aegis chose fail closed |
| Subject-wide sign-out | Not described | `POST /revocations/subject/{sub}` refuses every token issued before the cutoff (`GAP-L2b`) | AEGIS AHEAD | Works without knowing token ids |
| Per-IP rate limit and lockout | Per-IP admin rate limit; login lockout at the ORM layer | `ClientAddressLimiter` before token verification, lockout with Retry-After, trusted-proxy X-Forwarded-For (`GAP-L3a`) | ADOPTED | Pre-auth flood and token guessing |
| Mutual TLS, SPIFFE workload identity | mTLS listed as planned | `tls.clientAuth: require`, `identity.type: spiffe` (`SpiffeMutualTlsTest`) | AEGIS AHEAD | Agent workloads on Kubernetes |
| Multi-provider SSO login (GitHub, Google, Entra, Okta, Keycloak) | Yes | Not a login server; validates tokens from any OIDC IdP | NOT REQUIRED | Apache deployments already have an IdP or Knox; Aegis stays a resource server |
| Email and password accounts, password policy, Argon2id | Yes | None | NOT REQUIRED | Storing passwords is attack surface with no governance value |
| Dynamic Client Registration (DCR) | Yes (with issuer allow list) | None | NOT REQUIRED | DCR has been a source of SSRF bulletins; registration belongs at the IdP |
| Token catalog (gateway-issued API keys) | Hashed tokens, scoped to server, permissions, CIDR, time | Hashed static tokens; scopes come from the IdP | EQUIVALENT | Issuing credentials is the IdP's role |
| RFC 9728 protected resource metadata | Yes | Yes (`ProtectedResourceMetadata`) | EQUIVALENT | Both follow MCP authorization |
| RFC 8693 token exchange to backends | Token exchange actor and cache | `TokenExchangeCredentials` per source, subject match check, now pinned transport | EQUIVALENT | |
| Apache Knox as front door | No | `aegis-knox3-extension` | AEGIS AHEAD | Core to the Apache focus |

### Authorization and agent permissions

| Capability | ContextForge | Aegis | Value for Aegis | Why |
| --- | --- | --- | --- | --- |
| RBAC | Global, team and personal roles, permission audit log | IdP groups mapped to tool classes, write and destructive groups, deny-overrides rules | EQUIVALENT | Groups come from the IdP; no second user directory |
| Teams, invitations, personal teams | Yes | None | NOT REQUIRED | Tenant management is platform scope |
| Team quotas | User and team limits (source review); team size quotas (`MAX_MEMBERS_PER_TEAM`) | `rateLimits.perGroupPerMinute` shared across all group members (`GAP-L3b`) | ADOPTED | Many identities in one team cannot multiply the limit |
| Typed resource scope (topic, table, job) | Visibility flag private, team or public | `ScopeRule`, `ResourceRule` with JSON pointers into arguments (`tm10`) | AEGIS AHEAD | Kafka topic or Iceberg table level control |
| Per-call human approval with separation of duties | Not shipped | `ApprovalService`: requester cannot approve, token bound to caller, tool, argument digest, definition digest, single use (`tm11`) | AEGIS AHEAD | Responsible-AI human-in-the-loop for writes |
| Dry-run receipt before destructive action | No | `VrpService` receipts (`GatewayHostTest`) | AEGIS AHEAD | Human sees the preview before approving |
| OAuth step-up for tool scopes | Not described | 403 `insufficient_scope` naming the tool scopes (`AegisServer.stepUp`) | AEGIS AHEAD | |
| Policy as code | Optional external OPA plugin; `unified_pdp` plugin | Built-in rules plus OPA PDP (`OpaPolicy`, now over pinned transport) | EQUIVALENT | |
| Separate admin surface | Admin UI and API off by default | `host.adminListen`; complete control requires it plus `adminGroups` (`GAP-L4a`) | ADOPTED | Admin routes not reachable on the MCP port |

### Tool integrity and supply chain

| Capability | ContextForge | Aegis | Value for Aegis | Why |
| --- | --- | --- | --- | --- |
| Tool definition pinning and quarantine on change (rug pull) | Not shipped | TOFU and REQUIRE_PINS, digest-checked accept (`ToolCatalog`) | AEGIS AHEAD | OWASP MCP03 |
| Signed server catalog, rollback protection, break-glass containment | No | `SignedServerCatalog`, `SignedSerial`, `BreakGlassContainment` | AEGIS AHEAD | Change control for Apache production clusters |
| Executable and plugin JAR hash admission | No | `ArtifactAdmission`, provider code-source check for every plugin type (`GAP-L4b`) | AEGIS AHEAD | OWASP MCP04 |
| Signed deployment attestation for remote servers | "Federated attestation" listed as planned | Ed25519 signature by a host-anchored admission key (`CC-K1`) | AEGIS AHEAD | |
| Sandboxed execution of servers | Listed as planned (gVisor, Firecracker, WebAssembly) | Sandbox command prefix required in complete control (`ProcessLauncher`); runtime profile proof needs Linux | AEGIS AHEAD (launcher), EXTERNAL (runtime) | Honest: the sandbox runtime itself is the platform's |
| MCP server catalog and registry (public servers) | Yes | No | NOT REQUIRED | Aegis governs a reviewed set, not a marketplace |

### Controls on each call

| Capability | ContextForge | Aegis | Value for Aegis | Why |
| --- | --- | --- | --- | --- |
| Input validation | Central `SecurityValidator`, schema guard plugin | Input schema, `strictProperties`, operator overlay schemas, SQL read-only hook, path segment checks | EQUIVALENT | |
| Prompt-injection screening | Plugins (content moderation, regex, LLM Guard) | Normalised phrase screening on arguments, definitions, content (ENFORCE) and results (AUDIT, ENFORCE in complete control) | EQUIVALENT | |
| Output contract (no free text to the model) | No | `OutputPolicy` STRUCTURED_ALLOWLIST and HANDLE_ONLY | AEGIS AHEAD | Structural answer to injection, not a phrase list |
| Handle-bound release of withheld data | No | `ResultHandleStore`, take-once, never to the producer (`CC-K2`) | AEGIS AHEAD | Data minimisation |
| Execution contracts (typed operation vs free form) | No | `ExecutionContract`, FREE_FORM refused in complete control | AEGIS AHEAD | OWASP MCP05 |
| Egress allow list and SSRF checks | URL validation; DNS pinning for connections | `EgressGuard` on endpoints and every argument URL; now `PinnedHttp` connects only to checked answers with SNI and hostname verification kept (`GAP-L1a` to `L1e`) | ADOPTED | DNS rebinding was a real Aegis gap |
| PII redaction | `pii_filter` plugin | `output.redactPii`, default on in complete control (`GAP-L4c`) | ADOPTED | Responsible-AI data minimisation |
| Secret redaction in results | Secrets detection plugin | AWS keys, bearer, JWT, PEM scanner, optional entropy rule | EQUIVALENT | |
| Rate limits, timeouts, circuit breaker | Yes | Caller, tool, team, budget, pre-auth, deadlines, breakers | EQUIVALENT | |
| Response caching | Cached tool result, response cache by prompt plugins | None by design | NOT REQUIRED | A cache can bypass budgets and serve stale or other users' data |

### Audit, observability and compliance evidence

| Capability | ContextForge | Aegis | Value for Aegis | Why |
| --- | --- | --- | --- | --- |
| Tamper-evident audit | "Immutable audit trails" listed as planned | Hash-chained journal, verifier, checkpoints (`tm15`) | AEGIS AHEAD | Evidence for responsible-AI review |
| Audit written before execution, call refused if audit fails | Not described | ALLOW_INTENT before backend; AUDIT_UNAVAILABLE fails closed | AEGIS AHEAD | No unrecorded action |
| SIEM export | `siem_export_service`, security events table | `AuditSinkFactory` SPI, every record after the journal, fail closed (`GAP-L4d`) | ADOPTED | |
| OpenTelemetry | Yes | OTLP spans without identity or arguments | EQUIVALENT | Aegis keeps PII out of traces |
| Prometheus metrics | Yes | Yes | EQUIVALENT | |
| Support bundle with redaction | Yes | None | CONSIDER | Useful for operators; low priority |

### Platform and protocol scope

| Capability | ContextForge | Aegis | Value for Aegis | Why |
| --- | --- | --- | --- | --- |
| Admin web UI | Yes | None (admin API only) | NOT REQUIRED | UIs bring CSRF, XSS and cookie surface (several gateway CVEs are UI XSS) |
| Virtual servers composed from many backends | Yes | Sources with name prefixes in one catalog | EQUIVALENT | |
| Gateway federation (gateway of gateways) | Yes | No | NOT REQUIRED | Increases trust transitivity; each Aegis governs its own cluster |
| REST and OpenAPI to MCP translation | Yes | `type: http` source with typed request templates | EQUIVALENT | Aegis requires explicit typed templates, which is safer than auto-import |
| gRPC to MCP translation | Yes | No | NOT REQUIRED | Apache admin APIs are mostly REST or native clients (Kafka admin source) |
| A2A agents | Front door and agent registry | A2A agents as tool sources only (ADR-001) | EQUIVALENT | Aegis governs agent calls like tools |
| LLM proxy and chat client | Yes | No | NOT REQUIRED | Aegis governs actions, not model traffic |
| Content plugins (summarizer, markdown cleaner, HTML to markdown, timezone, TOON encoder, JSON repair) | Yes | No | NOT REQUIRED | Content transformation, not governance |
| Multi-instance leader election, session affinity, Redis caches, Rust data plane | Yes | Shared state store providers (Derby, PostgreSQL) for multi-replica governance state | EQUIVALENT | Aegis needs shared governance state only |
| Bulk import and export of configuration | Yes | Configuration is a reviewed file and a signed catalog | NOT REQUIRED | Import paths bypass review |

## What was adopted in this pass

Each item below was a real gap found by this comparison. All are implemented and proven at runtime.

| Id | Gap | Classes | Test |
| --- | --- | --- | --- |
| L1 | DNS rebinding between egress check and connect | `EgressGuard.pin`, `EgressBinding`, `OutboundHttp`, `PinnedHttp`, `BuildContext.outbound`; wired into MCP and HTTP sources, token exchange, OPA, remote hooks, OTLP, Vault and A2A | `PinnedHttpTest` (GAP-L1a to L1d), `DnsRebindingConfigTest` (GAP-L1e) |
| L2 | No `jti`, no revocation | `JwtVerifier.TokenIdPolicy`, `TokenRevocations`, `AdminApi.withRevocations`, `AegisServer` binding | `ThreatModelGapJwtTest`, `ThreatModelGapServerTest`, `AdminApiGapTest` |
| L3 | No pre-auth or IP limits, no team quotas | `ClientAddressLimiter`, `AegisServer.preAuth`, `RateLimiter.perGroupPerMinute` | `ThreatModelGapServerTest` (GAP-L3a), `ThreatModelGapConfigTest` (GAP-L3b) |
| L4 | Shared admin listener, AUDIT default for results, no PII patterns, no SIEM SPI, unadmitted non-source plugins | `host.adminListen`, `AegisServer.checkCompleteControlPerimeter`, `screening.results` default, `OutputGuard.PII_REDACTIONS`, `AuditSinkFactory`, `completeControl.artifacts` | GAP-L4a to L4d |
| K1 | Deployment attestation was shape-checked only | `GatewayFactory.deploymentAttestation`, `attestationStatement`, `AEGIS_REQUIRE_ATTESTATION_KEY`, `SignedCatalogManifest.verifySignature` | `CompleteControlConfigTest` (CC-K1) |
| K2 | HANDLE_ONLY had no release path | `ResultHandleStore`, `GovernanceEngine.enableResultHandles`, `AdminApi.withResultRelease` | `CompleteControlConfigTest` (CC-K2), `AdminApiGapTest` |

## Not required, and why

These ContextForge features are useful for a general AI platform. For Aegis's focus they add attack surface without adding governance:

1. **Admin UI, email accounts, password policy, SSO login flows, DCR, team invitations.** Each one makes the gateway an identity provider or a web application. Apache deployments already have an IdP, LDAP or Knox. Several MCP gateway CVEs found in this review are UI XSS or registration SSRF (see the other-gateways section).
2. **LLM proxy, chat client, content transformation plugins.** Aegis governs actions. It does not route model traffic or rewrite content for presentation.
3. **Response caching.** Aegis 2.0 removed the 0.1 cache because cache hits bypassed budgets. A per-prompt cache can also serve one user's data to another.
4. **Federation and public server catalog.** Each Aegis instance governs a reviewed set of servers for one cluster. Federating gateways would make one gateway trust another's decisions.
5. **gRPC translation, bulk import.** Out of the Apache admin-API focus. Bulk import also bypasses the signed review path.

## Consider later (fits the responsible-AI focus)

These fit as **remote hooks** (`hooks: [{type: remote, ...}]`, already supported with fail-closed timeouts) or small additions, without growing the core:

| Idea from ContextForge | Fit for Aegis | Suggested form |
| --- | --- | --- |
| Content moderation and harmful content detection | Responsible-AI check on results and arguments | Reference remote hook service in `adopters/hooks`, AFTER phase |
| Secrets detection in **arguments** | Stops an agent pasting a credential into a ticket or a topic | Built-in argument scanner reusing `OutputGuard` patterns, AUDIT or ENFORCE |
| LLM Guard integration | Model-based injection classifier as a second opinion behind phrase screening | Remote hook |
| Citation validator | LLM09 misinformation: check that cited sources came from governed tool results | Remote hook using the result digests Aegis already journals |
| Security response headers (CSP, frame deny) | Aegis has no browser UI; `nosniff` is already set | Add `Cache-Control: no-store` on admin responses; low effort |
| Startup security posture report | Operator feedback on weak settings | A `doctor` command over the parsed configuration |
| Per-token usage log | Investigation of a revoked token | Add `jti` to the journal record for JWT callers |

## Other MCP gateways (brief)

The other gateways reviewed earlier show the same pattern. Their CVEs sit in platform surfaces Aegis does not carry, or in classes Aegis now closes:

| Gateway | Security model | CVE class seen | Aegis status |
| --- | --- | --- | --- |
| Docker MCP Gateway | Container isolation per server | DNS rebinding (CVE-2025-64443); OCI label argument injection (CVE-2026-55887) | DNS rebinding closed by L1; argument injection closed by strict schema and execution contracts |
| Kong AI MCP plugins | OAuth2 and ACL at the API gateway | JWT algorithm confusion (CVE-2026-14916) | Explicit algorithm allow list, key-confusion test `tm18` |
| Solo agentgateway | MCP authn and authz | XSS (CVE-2026-29791) | No web UI |
| Cloudflare MCP portals | Edge auth and traffic detection | XSS (CVE-2026-1721) | No web UI |
| ToolHive | Container runner | CVE-2026-58197 | Sandbox launcher and artifact admission on the managed path |
| ContextForge bulletins | Platform gateway | A2A DNS rebinding (CVE-2026-77822), nested filter bypass (CVE-2026-11918), default credentials, log download traversal, jq credential disclosure, OAuth registration and DCR SSRF | Rebinding closed by L1; no built-in accounts or default passwords (identity comes from the IdP or operator-supplied hashed tokens); no log download, jq, or DCR surface |

CVE identifiers and descriptions come from the NVD census in `cve-census/` and the vendor bulletins collected earlier in this review. Treat them as point-in-time data and re-check them against NVD before quoting.

## Limits of this comparison

- The ContextForge review read documentation and source at one commit. It did not run ContextForge. A feature marked "not shipped" means the security-features document lists it as planned, or the services and plugins listing does not contain it.
- Aegis claims are limited to what the tests prove on this Windows host. Helm rendering, Kubernetes NetworkPolicy and the Linux sandbox runtime are not proven here (see `DETAILED-FINDINGS-AND-FIXES.md`, Part K, external items).
