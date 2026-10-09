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
# Threat-model remediation review and delivery plan

**Original review baseline:** `consolidate-into-threat-model-review` at
`b7ed14acc08d4cfb244238055bd30bb9fdf082d6`  
**Original review type:** read-only architecture, security, implementation, and evidence review  
**Tests executed by the original review:** none  
**Later status notes:** sections explicitly dated 2026-10-06 or 2026-10-07 describe subsequent changes
observed in the dirty working tree. They are not part of the baseline commit and are not a commit-bound
attestation. Any passing-test statements in those notes are reports from later implementation work, not
tests run by the original review.  
**Purpose:** turn the identified missing capabilities and review findings into a safe, staged delivery
plan. This document distinguishes present behavior from proposed work. It is not a release attestation,
security certification, or an ASF process record.

## 1. Executive conclusion

Aegis already has a strong core for its intended role:

- statically configured, declarative MCP proxy sources over HTTP and stdio;
- discovery of an upstream server's `tools/list` result followed by catalog pinning and quarantine;
- body-aware tool and typed-resource governance before credentials and backend execution;
- own-host OAuth protected-resource metadata, JWT verification, and actor-chain capture;
- route OAuth scopes, canonical resource scopes, policy, approval, destructive-action receipts, egress,
  output controls, and an audit chain; and
- a thin Apache Knox 3.0 `/ext` integration that lets Knox retain perimeter responsibilities while Aegis
  governs MCP semantics.

The project does **not** yet implement a managed MCP server registry, dynamic onboarding, generated
break-glass adapters, Knox-side delegated OAuth token exchange, Knox 3.1/Jakarta support, or an
OAuth-scope-to-tool/resource authorization model. (Update, 2026-10-06: this review clone now has
per-tool action scopes, a startup-only signed server catalog and contain-only break-glass orders; see the
status notes in section 10. A managed registry, onboarding, generated adapters and the Knox items remain
absent.) At review time it also had four findings that must be resolved
before the threat model can be treated as release-quality evidence:

1. wildcard matching has a quadratic worst case in authorization paths;
2. approval, receipt, and audit-intent commitment are not atomic;
3. the `verified-current` security-validation claim is not bound to the reviewed commit or raw test output;
4. `S19` is used for two unrelated claim meanings in security evidence.

The recommended order is deliberately conservative:

1. correct complexity, evidence, claim naming, and commit semantics first;
2. preserve and harden the static declarative MCP path;
3. add an offline reviewed server-registry import before adding runtime onboarding;
4. add guarded runtime onboarding only after the registry model and durable state are proven;
5. add break-glass as an additional restrictive binding, never a bypass;
6. complete delegated identity and Jakarta support as separate Knox workstreams.

## 2. Baseline architecture and non-negotiable invariants

### 2.1 Existing declarative MCP path

`aegis-config/.../GatewayFactory.java` accepts `sources[].type: mcp` and creates either an HTTP or
stdio MCP client. `aegis-mcp/.../McpProxyToolSource.java` calls the configured server's `tools/list`,
converts each advertised upstream tool into a descriptor, and proxies the selected upstream tool only
after the shared governance engine allows the action.

The configuration overlay, not the remote server, owns the security-bearing facts:

- exposed name and source prefix;
- class, where an unclassified tool remains `DESTRUCTIVE`;
- typed-resource extractors and resource scope rules;
- group visibility, dry-run behavior, timeout, and allowed output/content surfaces;
- egress policy; and
- source credential reference.

The upstream server's annotations may only raise a tool's authority. They must never lower the class,
declare a resource mapping, choose a credential, relax egress, or grant policy permissions.

### 2.2 Existing catalog boundary

`aegis-engine/.../catalog/ToolCatalog.java` is a **tool-definition integrity catalog**, not a catalog
of MCP servers. It discovers tools from configured sources, computes definition digests, and applies
TOFU, required-pin, signed-manifest, quarantine, and reviewed-acceptance behavior. A definition drift,
poisoned description, or unapproved new tool does not become callable.

The current admin API deliberately has no `/servers`, `/sources`, `/gateways`, or runtime backend
registration endpoint. `AdminApiTest.noRouteRegistersABackendAtRunTime` protects this boundary. It
prevents a privileged or compromised administrator request from turning Aegis into an SSRF-capable URL
registrar.

Any future server catalog must preserve this invariant. A generic `POST /servers { "url": ... }` API is
not an acceptable design.

### 2.3 Existing credential and identity boundary

For the own-host path, `AegisServer` admits a verified bearer JWT before consuming the request body and
binds the raw subject token only in request-local `SubjectToken`. `CallerIdentity` records actor,
effective subject, groups, issuer, authentication method, and an RFC 8693 `act` chain, but never raw
token material. `TokenExchangeCredentials` can exchange the verified own-host token at a configured,
egress-checked token endpoint and sends only the exchanged token to the backend.

For the Knox path, Knox authenticates and asserts the `Subject`; the Aegis extension maps that identity
through `KnoxIdentityBridge` and invokes the shared MCP handler. It does not copy Basic or bearer
credentials from Knox to the backend. This is intentional. Knox-side RFC 8693 exchange is not
implemented because no verified inbound token is currently bound into Aegis.

### 2.4 Required execution order

Every future feature must retain the central engine sequence:

1. active and non-quarantined tool exposure;
2. schema validation and canonical resource extraction;
3. resource scope and policy decision;
4. egress validation;
5. rate, budget, and circuit-breaker admission;
6. screening and hooks;
7. approval and destructive-action receipt verification;
8. durable audit intent under the adopted commit contract;
9. backend credential resolution; and
10. backend execution followed by output controls and result audit.

No registry, generated profile, emergency mode, OAuth scope, or Knox integration may introduce an
alternate execution path around this sequence.

## 3. Workstream A: managed MCP server catalog and onboarding

### 3.1 Current gap

Aegis can govern an individually configured MCP server, but it cannot maintain a durable inventory of
approved MCP servers. There is no server identifier, endpoint ownership contract, lifecycle state,
onboarding review, endpoint revocation, discovery record, server-level digest, or dynamic activation
mechanism.

`server/discover` in the MCP protocol is capability discovery for an individual configured connection.
It is not server registry discovery. Similarly, `ToolCatalog` tracks tool definitions, not server
records.

### 3.2 Target architecture

Introduce a separate durable **MCP Server Catalog**. Do not extend `ToolCatalog.Entry` to carry server
lifecycle state. A live tool descriptor and a reviewed server connection contract have different identity,
update, retention, and revocation behavior.

A proposed `McpServerRecord` should include:

| Field group | Required content |
| --- | --- |
| Identity | immutable ID, display name, owner, reviewers, creation/change reason |
| Transport | `HTTP` or approved `STDIO`; canonical endpoint or immutable command template |
| Endpoint identity | canonical origin/path, approved TLS/mTLS or certificate identity expectation where applicable |
| Trust | egress policy reference, credential reference, protocol era/version constraint, response/time bounds |
| Governance overlay | source prefix, default class, explicit tool overlay, resource extractors, group visibility, dry-run settings, content exposure rules |
| Discovery | contract digest, discovery timestamp, tool-definition digest snapshot, tool count |
| Lifecycle | state, record version, activation/expiry/revocation data, audit correlation IDs |
| Evidence | provenance/owner reference, change ticket or review link, reviewer decisions |

A record must not contain raw tokens, private keys, arbitrary authorization headers, arbitrary policy
code, unbounded command strings, arbitrary egress exceptions, or caller-controlled file paths.

### 3.3 Lifecycle states

Use explicit states:

```text
DRAFT -> PENDING_REVIEW -> DISCOVERED_QUARANTINED -> ACTIVE
                                     |                    |
                                     v                    v
                                  REVOKED        DRIFT_QUARANTINED
                                                          |
                                                          v
                                                      EXPIRED
```

- `DRAFT` and `PENDING_REVIEW` create no network connection and contribute no tools.
- `DISCOVERED_QUARANTINED` stores a reviewed discovery result but does not expose capabilities.
- `ACTIVE` is allowed only after exact record-version, contract-digest, discovery, tool-pin, policy, and
audit requirements succeed.
- `DRIFT_QUARANTINED` removes affected capabilities after endpoint or definition drift.
- `REVOKED` and `EXPIRED` prevent new calls and invalidate related break-glass grants.

### 3.4 Delivery phases

#### Phase A0: internal source-definition extraction

Extract the static `sources[]` parsing and binding path behind an internal source-definition model shared
by static config and a future server record. Do not change static behavior.

**Acceptance criteria**

- Existing HTTP and stdio MCP source configurations remain compatible.
- Existing AUTO, modern, and legacy MCP tests remain unchanged.
- Static sources retain current catalog refresh and pin behavior.
- Metrics and audit records distinguish static and managed source origin without exposing credentials.

#### Phase A1: offline reviewed registry import

Add a signed, declarative `mcpServers:` import that is disabled by default. It is reviewed configuration
at startup, not a mutable web API. Newly imported records stay `PENDING_REVIEW` or
`DISCOVERED_QUARANTINED` until explicitly activated.

Activation must discover through the gateway service credential, validate endpoint identity and egress,
store the server contract digest, and route all tool definitions through the current `ToolCatalog` pinning
flow. Production managed servers should require `REQUIRE_PINS` or a signed manifest.

**Acceptance criteria**

- A proposed record produces no connection, no egress allowlist mutation, no `tools/list` entry, and no
  callable tool.
- Discovery rejects private, loopback, link-local, metadata, redirecting, malformed, user-info-bearing,
  unsupported-transport, oversized, and unpinned records.
- Discovery never receives caller tokens.
- Any source/tool discovery drift becomes quarantined rather than silently replacing a definition.

#### Phase A2: guarded administrative onboarding

Only after A1 is proven, add dedicated server-catalog routes such as:

```text
GET  /server-catalog
GET  /server-catalog/{id}
POST /server-catalog/proposals
POST /server-catalog/{id}/discover
POST /server-catalog/{id}/activate
POST /server-catalog/{id}/quarantine
POST /server-catalog/{id}/revoke
```

`proposals` is non-activating. `discover` creates a review artifact only. `activate` requires non-delegated
requester/reviewer separation, exact record version, exact server contract digest, current discovery,
pre-existing egress policy reference, tool-governance overlay, required pins, and audit intent.

**Acceptance criteria**

- Existing generic registration paths remain `404`.
- All mutating routes enforce exact Origin checks, authenticated non-delegated identity, reviewer groups,
  request/reviewer separation, body/schema limits, rate limits, audit intent, and compare-and-set.
- A source change between discovery and activation yields conflict or quarantine, never activation.
- Revocation immediately removes the source and blocks later calls.

#### Phase A3: lifecycle reconciliation

Periodically rediscover active managed servers with bounded concurrency. Keep the current distinction
between transient discovery outage and actual definition drift. Add durable review reports, endpoint
expiry, rollback protection, and owner/reviewer attestation records.

## 4. Workstream B: generated break-glass adapters

### 4.1 Current gap

There is no adapter generator or emergency workflow. `--openapi-draft` generates a deny-all,
all-`DESTRUCTIVE` review draft from an offline OpenAPI document. It is useful scaffolding, but it does
not create a runtime source and must not be described as break-glass access.

### 4.2 Required meaning of break-glass

Break-glass must be a restrictive, short-lived, incident-bound overlay on the normal governance chain.
It must never mean:

- an allow-all policy;
- an egress exception;
- a bypass of resource extraction, approval, receipt, audit, screening, output controls, or catalog checks;
- a permanent generated adapter; or
- an LLM-generated production configuration that auto-applies itself.

### 4.3 Design

Generate a non-overwriting, reviewable signed artifact from an approved server record and explicitly
selected tool subset. The artifact must contain:

- server record ID/version and endpoint identity;
- selected tool definition digests;
- a default `DESTRUCTIVE` class for every selected action;
- explicit typed-resource mapping requirements;
- `unlistedTools: HIDE`;
- restrictive groups and policy overlay;
- required approval and destructive-action receipt;
- short absolute expiry;
- one-use/non-replay binding;
- immutable incident reference; and
- a signature over the complete canonical manifest.

Use a distinct `generated-break-glass` source mode or signed registry artifact. The normal
`GovernanceEngine.call()` path remains the only execution path.

### 4.4 Phases and acceptance criteria

1. Generate offline drafts only. No endpoint contact, no live config write, no source activation.
2. Require two-person activation using exact artifact, server-record, endpoint, and tool digests.
3. Require a short TTL, incident ID, non-delegated approver, approval and receipt, audit intent, and
   durable registry state.
4. On revocation, expiry, signature drift, server drift, policy failure, store failure, audit failure, or
   replay, deny before credentials or backend execution.

Test every attempted bypass: self approval, delegated approval, expired artifact, stale signature,
rollback, absent resource mapping, missing incident, removed tool pin, audit outage, state outage, and
backend credential failure.

## 5. Workstream C: delegated OAuth identity and action authorization

### 5.1 Existing own-host capabilities

Own-host OAuth is real but intentionally narrow:

- RFC 9728 protected-resource metadata is served at an exact public metadata endpoint;
- JWTs are verified for algorithm, signature, issuer, audience, time claims, and subject;
- `scope` is used before `scp`, never merged with it;
- configured route scopes and route groups admit a request before body processing;
- nested RFC 8693 `act` claims become an actor chain;
- only a verified request-local own-host JWT can drive RFC 8693 backend token exchange; and
- inbound tokens are never stored in `CallerIdentity`, audit records, hooks, policy input, approvals, or
  backend calls.

`TokenExchangeCredentials` fails closed for missing request token, token-endpoint error, invalid response,
unsupported issued-token type, and a detected subject mismatch. It has no service-token fallback.

### 5.2 Returned-token subject validation, fixed later

At the original review baseline, the implementation rejected a parseable returned JWT with a different
`sub` but allowed one with no `sub`. That was weaker than the documented requirement.

**Resolved in the later dirty working tree**

With subject checking enabled, an inspectable three-part issued JWT must now contain a non-blank string
`sub` equal to the caller's effective subject. A five-part compact JWE is refused because its subject
cannot be inspected. An opaque token is passed through as issued. This status is a later implementation
update, not a finding about the baseline commit and not a candidate-bound attestation.

**Required regression coverage for that later fix**

- missing, blank, matching, and mismatched returned JWT `sub`;
- opaque returned token behavior;
- no token-endpoint call after governance denial;
- no backend call or service-token fallback after exchange failure;
- request binding cleanup on all success/failure paths;
- cache separation by inbound token hash; and
- no token value in logs, audit, hook payloads, errors, approvals, or metrics.

### 5.3 Route OAuth scopes were not action permissions at the baseline

**Original baseline assessment.** The controls at the reviewed commit were conjunctive but distinct:

| Layer | Current purpose | Current limitation |
| --- | --- | --- |
| OAuth route scope | admit verified own-host JWT to MCP/admin/metrics route | not per tool or resource; absent in Knox mode |
| Route group | route admission after scope | not body/action authorization |
| Canonical resource scope | authorize extracted resources by caller group and resource glob | not an OAuth authorization-server grant |
| Aegis policy | tool, class, group/actor/effective subject, and resource decision | not an OAuth scope mapper |
| Backend authorization | final backend enforcement | independent of Aegis policy |

Do not overload `mcpScopes`, `adminScopes`, or `metrics.requiredScopes` with tool/resource semantics.
A real action-scope feature must run after canonical resource extraction and before the PDP decision,
name exact multi-resource and delegation semantics, retain deny precedence, compose with existing policy,
and create auditable evidence. OAuth scope strings must not acquire implied wildcard or hierarchy behavior.

### 5.4 Knox delegated identity

`KnoxIdentityBridge` maps the Knox 3.0 Subject to Aegis actor/effective subject/groups/actor chain. The
interpretation of `ActorChainPrincipal` is explicitly pending confirmation from Knox maintainers. Knox
route ACL is route-level admission only; Aegis remains responsible for JSON-RPC tool/body governance.

Knox currently does not bind a verified inbound token into `SubjectToken`, so `credentials.type:
token-exchange` fails closed in Knox mode. Do not fix this by copying Authorization, Basic credentials,
or Knox Subject private credentials to Aegis or the backend.

A Knox exchange feature is allowed only after Knox exposes a stable verified-token capability proving the
token's provenance, subject, issuer/audience, topology binding, lifetime, and exchange authorization.
Only then may it be request-locally bound and exchanged against a configured egress-checked endpoint.
Service credentials remain a separate explicit configuration choice, not an automatic fallback.

## 6. Workstream D: Knox 3.1 and Jakarta

The current extension is Knox 3.0 only. It depends on provided `javax.servlet` and `javax.ws.rs` APIs.
Knox 3.1 moves to Jakarta Servlet 6, Jakarta WS-RS 3.1, and Jersey 3. A javax-built extension cannot
load in that host.

Create a separate Knox 3.1/Jakarta host module only after published released Knox 3.1 artifacts exist.
Do not bytecode-rewrite the existing 3.0 module, mix `javax` and `jakarta` dependencies in one JAR, or
shade Knox, Jersey, servlet, or Jackson classes.

Keep the following shared: `Gateway`, `CallerIdentity`, governance engine, policy, audit, configuration,
and MCP handler. Port only host-bound contributor/lifecycle/JAX-RS/servlet and Knox API integration code.

**Release qualification**

- build against released Knox 3.1 artifacts;
- boot a real Knox 3.1 process and JAX-RS/Jersey dispatch;
- verify TLS, authentication, identity assertion, ACL admission, body limits, Aegis tool denial,
  approval/receipt flow, admin/metrics behavior, and redeploy cleanup;
- confirm the final JAR does not package Knox/Jersey/servlet/Jackson classes;
- keep 3.0 and 3.1 install instructions and test evidence separate; and
- do not label Knox 3.1 supported until the real host test passes.

## 7. Workstream E: wildcard matcher complexity

### 7.1 Finding

`aegis-engine/.../control/Glob.java` claims linear-time matching. It stores only the latest `*` fallback.
A pattern shaped like `*aaaa...ab` against a long `aaaa...a` string repeatedly rescans the suffix. When
both pattern and text grow, complexity is O(pattern length multiplied by text length), not linear.

This is a governance-path availability issue because matching is used by resource scope, tool/resource
policy, content URI control, hook binding, and project governance guardrails. Operator-defined patterns
are the multiplier; tool resources and URIs may be caller influenced.

At review time, `ControlsTest.globIsLinearAndCorrect` (since replaced) fixed the pattern size and uses a wall-clock threshold.
It cannot detect two-dimensional growth and must not prove a linear-time property.

### 7.2 Remediation choices

**Preferred if retaining a linear-time claim:** use a reviewed matcher for the exact current grammar:
full-string match, `*`, `?`, literals, and no escaping. Prove or instrument a bound proportional to
pattern plus text length. Do not use an unbounded DFA cache or determinization that trades backtracking
for state explosion.

**Conservative option:** retain a bounded O(pattern multiplied by text) approach, but impose strict
load-time limits on pattern length, star count, patterns per rule, and cumulative rule complexity, plus
uniform limits on matched resource/URI/tool-name length. Document the actual bounded complexity.

Do not rely on star collapsing or elapsed-time-only tests.

### 7.3 Required tests and rollout

- Reference-oracle equivalence for short patterns and strings, including empty input, repeated stars,
  `?`, and current UTF-16 semantics.
- Adversarial negative patterns over a geometric series that varies both pattern and text length.
- Integration tests through scope, policy, content URI, and guardrail consumers.
- Deterministic operation/cap tests rather than a machine-dependent two-second threshold.
- Load-time rejection tests for every selected complexity limit.
- Compatibility-report mode that detects behavior differences before a new matcher becomes allow authority.

Any new configuration limits can invalidate present policy files. Ship migration diagnostics identifying the
rule and pattern that exceed the limit, document a transition period, and cache only validated immutable
patterns, never caller text.

## 8. Workstream F: approval, receipt, and audit commitment

### 8.1 Finding

The successful destructive path in `GovernanceEngine` verifies approval and receipt, consumes approval,
consumes receipt, and then appends `ALLOW_INTENT`. If audit append fails, the backend does not run and
credentials are not resolved, but the approval and possibly the receipt are already consumed. The caller
must obtain new values, and the failed attempt has no required durable intent record.

This preserves fail-closed execution and replay resistance. It does not provide atomic
approval/receipt/audit behavior, despite the current wording implying that it does.

`StateStore` guarantees atomic individual operations, not a multi-key transaction paired with an arbitrary
`AuditSink`. Current memory, Derby, and PostgreSQL implementations can prevent concurrent double-use of a
nonce, but cannot make approval consumption, receipt consumption, and audit append one atomic action.

### 8.2 Decision options

1. **Specify current at-most-once behavior.** Document that an audit outage can burn approval/receipt
   values without execution, return a refresh-required outcome, and emit a later recovery record if
   available. Lowest implementation risk, but weaker operator experience.
2. **Add durable reservation/outbox semantics.** Atomically store nonce reservation, action binding, and
   pending audit intent in durable state. Complete or reconcile the audit record before backend execution.
   Recommended if audit completeness and retry usability are required. This needs a StateStore SPI
   capability, provider migrations, durable recovery, monitoring, and reconciliation.
3. **Use a transactional state-plus-audit repository.** Commit nonce state and audit intent in one database
   transaction. Strong local consistency, but changes the pluggable audit sink model. It still cannot
   make an external backend action globally exactly once.

Do not merely append audit before nonce consumption. Concurrent contenders could then create intent records
for actions that never become eligible unless the journal supports explicit reservation/abort outcomes.

### 8.3 Mandatory acceptance criteria

- Simulate audit failure after valid approval and receipt and assert the adopted public contract exactly.
- Assert no credential lookup or backend call on every failure point.
- Exercise failure between approval and receipt consumption, between state write and audit write, and
  process crash at every durable phase.
- Race two callers/replicas using the same approval and receipt. Exactly one may become eligible.
- Verify memory, Derby, and PostgreSQL semantics or explicitly limit the capability by provider.
- Preserve current action binding, definition digest, Unicode separation-of-duties, replay, and receipt
  tests.
- Ensure every `ALLOW_INTENT` has a deterministic execution or terminal-abort interpretation.

## 9. Workstream G: evidence provenance and S19 correction

### 9.1 Current evidence issue

`docs/evidence/SECURITY-VALIDATION.md` records a branch name different from the review branch, no commit
or tree digest, and no raw Maven/Surefire reports or immutable artifact. `CLAIMS-MATRIX.md` nevertheless
marks G03 `verified-current`, even though the matrix itself requires raw output/transcript before such a
promotion.

Until a bound rerun exists, downgrade G03 to `verified-historical` or a clearly named recorded-but-
unattested state. Remove wording that says it proves the current tree.

For a rerun, retain an evidence bundle under:

```text
docs/evidence/security-validation/<full-commit-sha>/
```

Include full commit and tree SHA, UTC start/end, clean/dirty state and untracked policy, exact command and
exit code, OS/architecture, Maven and JDK vendor/version/home, raw stdout/stderr, reactor summary,
selected Surefire reports or a hashed report archive, and a SHA-256 manifest. If large reports are stored
externally, commit the immutable content-addressed reference, hash, retention policy, and verifier steps.

A reviewer must be able to reconcile the claimed 227 selected tests and zero failures/errors/skips from
raw evidence, not a prose total.

### 9.2 S19 identifier collision

Canonical `docs/SECURITY.md` uses S19 for hook fail-closed behavior. The claims matrix reuses S19 for
SPIFFE mTLS, and `SECURITY-VALIDATION.md` says `SpiffeMutualTlsTest` claims S19. This makes control-to-test
traceability ambiguous.

Assign the SPIFFE row a unique host/identity identifier, for example `H12` subject to the project numbering
convention. Update the validation table to cite that identifier and retain S19 only for hook tests.

The current TLS proof uses a real JDK TLS listener and `keytool`-issued certificates. It validates
file-based X.509-SVID mapping rules. It does not exercise the SPIRE Workload API and must not be documented
as SPIRE integration evidence.

## Appendix A: Knox coordination, protocol-fit, and claim-hygiene additions

The corrected Aegis and Apache Knox coordination note changes an important architectural conclusion.
The evidence does **not** show that Knox is incapable of carrying or hosting MCP governance. Knox can
transport an MCP request, and a Knox extension can contain body-aware code. The evidence shows a narrower
fact: the shipped/configured Knox 3.0 `AclsAuthz` route authorization acts on principal, groups, and
remote IP, and does not inspect the MCP JSON-RPC body. In the current integration, Aegis parses that
body and makes the per-tool, per-resource decision.

The final review must use this precise position:

> Knox establishes the transport, topology, identity, and route-admission perimeter. Aegis supplies the
> MCP semantic decision engine. The host-neutral Aegis core can run in the own-host deployment or as the
> existing thin Knox `/ext` service. Whether the long-term community chooses standalone, Knox-hosted, or
> both is a protocol-fit and governance decision, not a claim settled by current source inspection.

### A.1 What is verified now

| Area | Current evidence | Accurate claim |
| --- | --- | --- |
| Knox host shape | `AegisKnoxServiceContributor` is a native Knox Jersey service contributor and `AegisMcpResource` delegates into the shared MCP handler and governance engine. | Aegis can be hosted as a thin Knox 3.0 `/ext` service without embedding Knox APIs into the shared engine. |
| Perimeter to semantic handoff | `KnoxAclRouteScopeTest` executes Knox 3.0 `AclsAuthorizationFilter` with body-read tripwires, then shows Aegis applies a reader/tool denial. | The tested Knox ACL path is route-level and body-blind; Aegis adds body-aware MCP action governance in this integration. |
| Credential boundary | The resource maps Knox `Subject` to `CallerIdentity`; it does not forward Knox Basic/bearer credential material to a backend. | Knox caller credentials are not backend credentials in the tested integration. |
| Packaging boundary | Knox dependencies are `provided`; packaging tests inspect the Aegis bundle. | Aegis does not package Knox, Jersey, servlet/JAX-RS, or Jackson implementation classes in the host bundle. |
| Lifecycle boundary | `AegisKnoxLifecycle` closes the route-local gateway at topology undeploy/redeploy; lazy initialization rebuilds it on a later request. | Redeploy cleanup and lazy route recreation exist. This is not an in-place hot-reload contract. |
| Knox version | Current module uses released Knox 3.0 `javax` APIs. | Knox 3.0 is the current host proof point. Knox 3.1/Jakarta is not supported. |

### A.2 What is proposed, partial, or absent

| Knox capability or direction | Current Aegis state | Required before it is a final-review claim |
| --- | --- | --- |
| Declarative service deployment | Narrowly used through the Knox service contributor and topology parameter. | Define and test the boundary between Knox service/topology declaration and Aegis per-tool governance configuration. Do not claim reuse of a generic Knox policy-definition language. |
| Topology or configuration hot reload | Not implemented as an Aegis contract. | A real target experiment proving change visibility, in-flight behavior, rollback, gateway close/reopen behavior, and audit/catalog consistency. |
| HA dispatch/failover | Not integrated. PostgreSQL state proves Aegis multi-process state semantics, not Knox HA dispatch. | Two real Knox nodes with selected dispatch/failover configuration and shared Aegis state, proving approval/receipt replay, rate/budget, catalog, audit, and failover semantics. |
| Knox JDBC token state | Not integrated. | Explicit provenance-preserving state/token contract and failure-closed real-host test. Do not equate Aegis PostgreSQL state with Knox token-state reuse. |
| Alias/keystore service | Knox may own perimeter TLS/aliases, but the extension does not resolve Aegis secrets through Knox aliases. | A dedicated adapter or credential-provider integration plus real-host proof that no secret material leaks to config, audit, or errors. |
| Federation and actor chain | Own-host actor chain is implemented. Knox `ActorChainPrincipal` interpretation remains unconfirmed. | Knox-maintainer confirmation and a real identity-provider/delegation flow showing actor, effective subject, chain, approval separation, and audit representation. |
| KnoxIDF and delegated exchange | Future work only. Knox mode does not bind a verified inbound token into `SubjectToken`, so Aegis token exchange fails closed. | Released Knox API, stable verified-token handoff, token provenance and lifetime contract, actor-chain decision, egress-checked exchange, and no automatic service-token fallback. |
| Knox discovery/monitor reuse | Not integrated. Aegis only discovers `tools/list` on already configured MCP sources. | A reviewed design mapping trusted Knox discovery outputs into the proposed MCP Server Catalog. Discovery must not automatically activate an endpoint. |
| Knox admin/UI patterns | Aegis admin routes can be mounted through Knox but do not reuse Knox administration APIs/UI. | Separate UX/API design, authorization and audit model, and explicit decision whether only patterns are reused or an actual integration is built. |
| Ranger as PDP | Ranger is an HTTP adapter profile with contract evidence, not an Aegis policy-decision integration. | A real Ranger policy decision composition design, deny precedence, typed-resource mapping, latency/failure semantics, and real Ranger evidence. |
| Knox 3.1/Jakarta | Not implemented. | Separate Jakarta module, released Knox 3.1 dependency baseline, real Knox/Jersey host process validation, and package-boundary scan. |

### A.3 Protocol-fit prototype required before a final architecture decision

The final review should require an explicit protocol-fit prototype rather than choosing a permanent
standalone-versus-Knox conclusion from structural argument alone. The prototype must use a pinned stock
Knox 3.0 distribution and the exact candidate Aegis artifact layout.

**Required evidence bundle**

- candidate Git commit and tree SHA;
- Knox distribution version, SHA-512, and JDK version;
- Aegis bundle and every installed extension JAR SHA-256;
- complete topology and Aegis configuration with secrets removed;
- reproducible startup and cleanup commands;
- raw Knox, Aegis, client, and audit logs;
- selected test report and a hash manifest; and
- a concise result table tying each scenario to evidence paths.

**Required scenarios**

1. TLS, keystore/alias configuration, chosen Knox authentication provider, identity assertion, and route
   ACL admission or denial.
2. A permitted MCP read, a route-admitted but body-denied destructive tool, and proof that the backend
   sees only the configured Aegis service or exchanged credential.
3. Approval, destructive receipt, approved execution, and replay refusal through the real Knox route.
4. Anonymous caller, wrong group, body larger than one MiB, configuration failure, admin authorization,
   metrics authorization, and undeploy/redeploy cleanup.
5. MCP protocol cases actually advertised by the extension: method/path validation, 2025 and 2026 era
   negotiation where supported, header/body disagreement, bounded requests/responses, and streaming/SSE
   only if that behavior is explicitly supported by the chosen host path.
6. No fallback from a failed Aegis route to an ungoverned downstream service.

A subsequent HA, federation, alias, discovery, Ranger, or Jakarta claim needs its own experiment. One
successful Knox route run cannot establish those separate capabilities.

### A.4 Claim-hygiene rules for the final review

Use the following wording rules throughout the final threat model, Knox note, README, architecture pages,
and release evidence:

- Say **"the tested Knox AclsAuthz route path is body-blind"**, not **"Knox cannot inspect MCP bodies"**.
- Say **"Aegis adds an MCP body-aware semantic governance layer"**, not **"Knox cannot host the layer"**.
- Say **"the threat model is extended or layered"**, not **"the adversary model is inverted"**. Knox
  establishes perimeter identity and route admission; Aegis continues to treat model input, tool bodies,
  and upstream definitions as untrusted inputs.
- Say **"Aegis can run standalone or in the current thin Knox 3.0 extension"**, not **"Aegis must remain
  a separate network service"**.
- Say **"Knox 3.0 is the current proof point"** and **"Knox 3.1/Jakarta is planned"** until separate
  released-host evidence exists.
- Say **"own-host RFC 8693 token exchange is implemented"** and **"Knox-side exchange is deferred and
  currently fails closed"**. Do not generalize own-host Keycloak evidence to Knox.
- Say **"Ranger configuration is contract-supported"**, not **"Ranger is an integrated PDP"**, until a
  real policy-decision integration exists.
- Treat local retained two-door runs as useful technical evidence, not a release certification. Candidate
  claims require commit/tree-bound artifacts and raw output under the evidence rules in this review.

### A.5 Additional final-review gate

Before the final review closes, record a community decision that answers all of the following:

1. Is the supported deployment statement **own host**, **Knox extension**, or **both**, and which exact
   versioned host evidence supports each statement?
2. Which Knox capabilities are dependencies, which are optional integrations, and which are only design
   inspirations?
3. What token/actor representation is authoritative for Knox delegated identity, and which Knox
   maintainer-reviewed API establishes it?
4. Is the server-catalog work a portable Aegis capability, a Knox-integrated capability, or a staged
   combination of both?
5. Which capabilities are release blockers versus roadmap items, with every unsupported capability labeled
   accordingly?

Without these answers, the project can truthfully claim a working Knox 3.0 extension and a host-neutral
semantic governance core, but it should not claim a settled Knox reuse strategy or a complete enterprise
identity/catalog platform.

## 10. Delivery sequence and release gates

### Priority 0: correct security truth and evidence

1. Fix or bound wildcard matching and remove the unsupported linearity claim.
2. Select and document the approval/receipt/audit failure contract, then add fault-injection tests.
3. Bind security validation to an exact candidate commit/tree with raw evidence.
4. Rename the duplicate S19 claim and correct the SPIFFE test citation.

**Gate:** no `verified-current` threat-model or security claim remains unbound to the reviewed candidate.

**Implementation status, 2026-10-06:** the bounded matcher, fail-closed authorization/guardrail
handling, explicit at-most-once promotion outcome, H12 evidence identifier, and G03 downgrade were
reported as implemented in the later dirty working tree. Later local reports state that targeted engine
and guardrail tests passed; the original review executed no tests and inspected no candidate-bound raw
evidence. Candidate-bound evidence remains a release task.

### Priority 1: strengthen existing static MCP adoption

1. Extract common static/managed source-definition parsing.
2. Document the clear distinction between route OAuth scopes, canonical resource scope, policy, and backend
   authorization.
3. Tighten returned JWT subject checking or make the compatibility exception explicit.
4. Obtain Knox-maintainer confirmation for actor-chain mapping before expanding delegated identity claims.

**Gate:** static MCP proxy behavior stays compatible and no identity credential crosses a boundary without
verified provenance and explicit configuration.

**Implementation status, 2026-10-06:** per-tool OAuth action scopes are implemented in this review clone
(`tools[].actionScopes`, see section 5.3). Verified JWT scope names ride on `CallerIdentity`; the engine
denies `SCOPE_DENIED` at step 2 before resource scopes, policy, approval, credentials and the backend;
the requirement is in the approval and receipt binding when present. Route scopes stay a separate check,
and CONFIGURATION and SECURITY (S26) document the distinction. Item 1 (shared source-definition
extraction) and item 4 (Knox actor-chain confirmation) are not done. Item 3, stricter returned-JWT
subject checking, is implemented in the later dirty working tree as described in section 5.2.

### Priority 2: offline MCP server catalog

1. Add the durable server-record model and signed offline import.
2. Implement lifecycle states and activation through existing catalog pinning.
3. Add server-level audit, digest, expiry, drift, and revocation semantics.

**Gate:** a proposed server cannot produce network activity or callable tools until independently reviewed,
current, pinned, and auditable.

**Implementation status, 2026-10-06:** a startup-only subset is implemented in this review clone as
`serverCatalog`. It is an Ed25519-signed file, verified before parsing, whose records refer to existing
`sources[]` ids. States are ACTIVE, QUARANTINED and REVOKED. ACTIVE records pin each source's configuration
digest and its exact tool definitions; anything else is never constructed, discovered or added to egress.
Pinning becomes signed-only (no first-sight trust, pin file or run-time acceptance). Offline
`--server-catalog-create` and `--server-catalog-verify` commands and a real own-host flow test exist.
Rollback protection ships as a signed `serial` with a high-water mark in the state store
(`SignedSerial`), and a host trust anchor (`--require-server-catalog`) fixes the review key. Not
implemented: a durable server-record store, expiry, owner/reviewer attestation fields, server-level
audit events and periodic drift reconciliation. The catalog and break-glass orders were reported as run against real Flink, Iceberg, Spark and Kafka through
the own host and a stock Knox 3.0.0 gateway (17 of 17 passed, 0 skipped; see the dated local record in
VALIDATION.md). The original review did not execute that run, and the mutable dirty-working-tree output
is not candidate-bound evidence. Spark Connect was not run with a catalog.

### Priority 3: guarded runtime onboarding and break-glass

1. Add narrowly scoped, reviewer-only registry endpoints.
2. Add non-overwriting, signed break-glass draft generation.
3. Add two-person activation, expiry, revocation, incident binding, and reconciliation.

**Gate:** break-glass is demonstrably more restrictive than normal access and cannot bypass any governance
control.

**Implementation status, 2026-10-06:** only the containment foundation is implemented in this review
clone: `serverCatalog.breakGlass`, an order signed with a key that must differ from the catalog key,
naming catalog servers to force QUARANTINED or REVOKED, valid for at most 24 hours, refusing startup when
expired or not yet valid, with offline `--break-glass-create` and `--break-glass-verify` commands. It can
only remove capability. Items 1 to 3 (registry endpoints, generated break-glass adapters, two-person
activation and reconciliation) are not implemented. No admin route exists for either file, and
`AdminApiTest` asserts that.

### Priority 4: Knox identity and Jakarta host support

1. Design Knox verified-token handoff only with a stable Knox capability and real-provider evidence.
2. Implement a separate Jakarta host module only after released Knox 3.1 artifacts exist.
3. Run real Knox 3.1 integration, package-boundary, and lifecycle validation.

**Gate:** no claim of Knox delegated exchange or Knox 3.1 support appears before its separate real-host
evidence exists.

**Status, 2026-10-06: externally blocked, nothing implemented.** Maven Central's latest
`org.apache.knox:gateway-spi` was 3.0.0 when checked during this work, so no Jakarta module was built.
Knox 3.0 exposes no verified inbound-token handoff that Aegis could use, so Knox-side token exchange stays
unsupported and fails closed (Knox identities carry no OAuth scopes, so they are also denied tools with
`actionScopes`). Ranger as a policy decision point, Knox HA, Knox aliases and Knox service discovery need
external environments and remain roadmap items.

## 11. Explicit non-goals and approaches to avoid

- Do not merge this root-import review branch directly into `main`; it has no common ancestor with the
  destination branch. Port approved work into a normal branch created from current `main`.
- Do not add generic runtime backend registration, arbitrary URL import, or caller-selected `ToolSourceFactory`.
- Do not use an LLM to generate and apply executable production policy, egress, credentials, pins, or shell
  commands in one action.
- Do not revive bespoke per-engine Java adapters merely for onboarding. Prefer existing MCP proxying,
  declarative HTTP profiles, or a focused typed source for a genuinely different protocol.
- Do not forward inbound bearer or Basic credentials to backends.
- Do not use route OAuth scopes as an implicit tool/resource authorization language.
- Do not automatically accept an MCP server's class annotation, tool description, or new definition as
  trustworthy governance configuration.
- Do not label `tools/list` discovery as server provenance, ownership, attestation, or catalog onboarding.
- Do not claim current Apache Incubator status from proposal-process correspondence alone. Verify the current
  status against authoritative ASF records before publishing a present-tense affiliation claim.

## 12. Review traceability

| Area | Primary implementation paths | Primary validation/documentation paths |
| --- | --- | --- |
| Static MCP proxy | `GatewayFactory`, `McpProxyToolSource`, HTTP/stdio MCP clients | `GatewayFactoryTest`, `ExistingMcpServerProxyTest`, existing MCP profile |
| Tool integrity | `ToolCatalog`, `CatalogIntegrity`, signed manifest code | catalog, manifest, and quarantine tests |
| Server catalog proposal | new durable registry, source-definition abstraction, constrained admin routes | lifecycle, SSRF, CAS, drift, revocation, two-person review tests |
| Break-glass | new signed draft/activation artifact over existing engine | expiry, incident, replay, bypass, audit, and policy tests |
| Own-host OAuth | `AegisServer`, perimeter settings, JWT verifier, token exchange | scope, metadata, JWT, token-exchange tests |
| Knox integration | service contributor, JAX-RS resource, identity bridge | Knox extension and ACL route/body tests |
| Jakarta target | new Knox 3.1 module only | released-host process and package-boundary tests |
| Glob complexity | `Glob`, all matching consumers | oracle, adversarial complexity, integration tests |
| Commit semantics | `GovernanceEngine`, `ApprovalService`, `VrpService`, `StateStore`, audit sink | fault injection, multi-replica, crash recovery tests |
| Security evidence | threat model, validation record, claims matrix | commit/tree-bound artifact manifest and report verification |

## 13. Final review position

The existing architecture is valuable because it already demonstrates the central distinction from a
transparent MCP proxy: Aegis treats an upstream tool definition as untrusted input and applies
operator-owned semantic governance before execution. The next work should reinforce that boundary, not
weaken it through generic runtime registration or emergency bypasses.

The server catalog, generated break-glass work, delegated identity, and Jakarta integration are worthwhile,
but only after the complexity, commit semantics, and evidence provenance findings are corrected. Those
three foundations determine whether future capabilities are governed, reviewable, and trustworthy.
