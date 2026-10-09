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
# Features: issue, solution, design, tests

Each entry has five parts:

- **Issue:** the problem.
- **Solution:** what 2.0 does.
- **Design:** where it lives.
- **Tests:** which tests prove it.
- **Source:** where the issue came from.

"0.1 defect" means a defect confirmed by reading the 0.1 source (`mcp-gateway-core`, now preserved
in Git history) during the Knox review work. [COVERAGE-0.1-VS-2.0.md](COVERAGE-0.1-VS-2.0.md) maps each
0.1 capability to its 2.0 replacement, and [DEFECT-LOG.md](DEFECT-LOG.md) records defects found in 2.0.

---

## F1. Strict, bounded, dependency-free JSON

- **Issue:** A gateway and a backend that parse the same body differently can be steered apart.
  One example is duplicate keys, where the gateway checks the first `"name"` and the backend runs
  the second. General-purpose parsers accept duplicates by default and bring version conflicts
  into host JVMs.
- **Solution:** A private RFC 8259 parser. It rejects duplicate keys, invalid UTF-8, lone
  surrogates and trailing content, and bounds depth, size, strings, numbers and counts during the
  parse. Numbers are exact (Long or BigDecimal, never double). A canonical form is used for digests.
- **Design:** `engine.json.Json`, `JsonLimits`, `JsonPointer`.
- **Tests:** `JsonTest`, `McpServerHandlerTest.transportLevelRejections` (a duplicate `method` returns -32700).
- **Source:** 0.1 defect (no strict inbound limits).

## F2. Identity without credentials, and no token passthrough

- **Issue:** The 0.1 `CallerIdentity` carried the caller's outbound `Authorization` header, and a
  passthrough resolver existed. That is the confused-deputy pattern the MCP security best practices
  forbid.
- **Solution:** `CallerIdentity` has actor, effective subject, groups, issuer, auth method and actor
  chain, and nothing secret. A static-credential source sends the gateway's service credential. An
  own-host source with `credentials.type: token-exchange` sends the verified inbound JWT only to the
  configured token endpoint, which returns a backend-audience token; the inbound token is never sent to
  the protected backend.
- **Design:** `engine.model.CallerIdentity`, `spi.SubjectToken`, `spi.CredentialProvider`,
  `config.credentials.StaticCredentialProvider`, `TokenExchangeCredentials`.
- **Tests:** `GatewayHostTest.jwtAuthenticatedValidateApprovePromoteOverHttp` (a static-credential
  backend sees `auth=null`, not the JWT), `TokenExchangeCredentialsTest`, and the Keycloak 26.8.0 run.
- **Source:** 0.1 defect.

## F3. Credentials resolved only after governance

- **Issue:** 0.1 resolved credentials before the chain ran and fell back when resolution failed.
- **Solution:** Credentials are resolved at step 10, after commit. If resolution fails the call is
  denied with `BACKEND_ERROR`. There is no fallback.
- **Design:** `GovernanceEngine.govern`.
- **Tests:** `GovernanceEngineTest.deniedCallNeverResolvesCredentialsOrReachesBackend`, `...auditFailureDeniesBeforeExecution`.
- **Source:** 0.1 defect.

## F4. Exposure, write unlock, per-tool groups, least-privilege listing

- **Issue:** Callers should not see tools they cannot use. Write tools need an explicit unlock.
  Per-topology tool restriction is a common requirement in Apache Knox deployments.
- **Solution:** Step 1 checks `allowedGroups`, `writeGroups` and `destructiveGroups`. `tools/list`
  applies the same filter.
- **Design:** `GovernanceEngine.exposure`, `GovernedTool.allowedGroups`.
- **Tests:** `GovernanceEngineTest.unknownToolIsNotExposedAndWriteToolsAreHiddenFromReadOnlyCallers`,
  `McpServerHandlerTest.discoverAndListArePrivateCacheableAndFilteredPerCaller`, Knox cold install K2.

## F5. Argument validation (schema subset)

- **Issue:** Malformed or unexpected arguments reach backends.
- **Solution:** Validates `type`, `properties`, `required`, `additionalProperties`, `enum`,
  `const`, string lengths, numeric bounds and array items. `pattern` is intentionally not evaluated
  (upstream regex in the gateway is a ReDoS risk). Local `$ref`s (`#`, `#/$defs/...`, `#/definitions/...`)
  are resolved and enforced, cycles included; a `$ref` to anything else is never fetched and the call is
  denied. Composition keywords (`anyOf`, `oneOf`, `allOf`) are not evaluated.
- **Design:** `engine.control.SchemaValidator`.
- **Tests:** `ControlsTest.schemaSubsetValidation`, `SchemaRefTest`, `GovernanceEngineTest.schemaViolationsAreInvalidInput`, `ExampleConfigsTest.flink` / `spark`.

## F6. Resources by JSON pointer, never widened

- **Issue:** 0.1 `Scope.resourceOf` used the first top-level argument, and a missing value became
  approval scope `*`.
- **Solution:** Operator-declared pointers. Multi-part resources are joined. Arrays produce one
  resource per element. A missing, empty, `..`, wildcard or control-character value is `SCOPE_DENIED`.
- **Design:** `engine.catalog.ResourceRule`.
- **Tests:** `GovernanceEngineTest.missingResourceIsDeniedNeverWidenedToWildcard`, `ExampleConfigsTest.spark` (path traversal).
- **Source:** 0.1 defect.

## F7. Resource scopes

- **Issue:** Some groups should never touch certain data, whatever the tool.
- **Solution:** `scopes:` list which groups may touch which resource globs. When configured, every
  resource of a call must be covered.
- **Design:** `engine.control.ScopeRule`.
- **Tests:** `ExampleConfigsTest.iceberg` (analyst to the finance namespace is denied), `RealIcebergRestCatalogTest`.

## F8. Policy: rules plus OPA, strict

- **Issue:** 0.1 OPA ignored arguments, cached per caller and tool, and parsed "allow" by substring.
- **Solution:** Built-in rules (deny overrides allow, default deny) composable with OPA (all must
  allow). OPA receives the full canonical action and allows only on a JSON boolean `true`. Nothing
  is cached. An exception denies.
- **Design:** `engine.control.RulePolicy`, `config.policy.OpaPolicy`.
- **Tests:** `OpaPolicyTest`, `GovernanceEngineTest.denyRuleOverridesAllowRule`, `ExampleConfigsTest.iceberg` / `kafka`.
- **Source:** 0.1 defect.

## F9. Egress: every destination, every DNS answer, any depth

- **Issue:** 0.1 re-resolved DNS, let a wildcard host allow private or loopback answers, and only
  inspected only top-level argument names. A recursive walk is required because an MCP argument can
  contain arbitrary nested structured data.
- **Solution:** Backend targets are checked, plus every http(s) URL embedded anywhere in any
  string at any depth of the arguments. Every DNS answer is classified: loopback, link-local and
  metadata, private, ULA, CGNAT, multicast, reserved, IPv4-mapped, and NAT64/6to4/Teredo by their
  embedded IPv4. Non-public addresses need an explicit CIDR. Userinfo is refused. Clients never
  follow redirects, and every outbound read has a total deadline (`BoundedHttp`). Plugin sources that
  speak their own protocol report their configured endpoints (`ToolSource.configuredEndpoints`): the
  Kafka Admin source reports each bootstrap server as `tcp://host:port` and the Spark Connect source
  its server. Those hosts join the allow list like an HTTP `baseUrl`, and every call checks them and
  every DNS answer for them before the client is used.
- **Design:** `engine.control.EgressGuard`, `ValueWalker`, `ToolSource.configuredEndpoints`.
- **Tests:** `ControlsTest.wildcardHostDoesNotAllowPrivateAnswersAndEveryAnswerIsChecked`,
  `...operatorCidrCanAllowAPrivateBackendExplicitly`,
  `GovernanceEngineTest.nestedUrlArgumentToPrivateAddressIsDenied`, `ExampleConfigsTest.flink`
  (cloud-metadata URL), `ExistingMcpServerProxyTest.oversizedDownstreamResponseIsBoundedAndRedirectsAreNotFollowed`,
  `KafkaAdminEgressTest` (a broker name resolving outside `allowCidrs` is `EGRESS_DENIED` before the
  Admin client is used), `ExampleConfigsTest.kafkaConnectAndAdminClient` (a real broker reached through the check).
- **Limit:** A DNS rebinding window remains between the check and the connection (documented in VALIDATION.md).

## F10. Rate limits and budget, atomic

- **Issue:** 0.1 cache hits bypassed the budget, and `tryConsume` over-incremented on refusal.
- **Solution:** Atomic fixed-window counters in the `StateStore`. The counter that refuses a call
  is not charged. Counters checked earlier in the same call (per caller before per tool before
  budget) stay charged, because the limit is attempt-based. There is no response cache, so nothing
  bypasses the budget.
- **Design:** `engine.control.RateLimiter`, `StateStore.tryAcquire`.
- **Tests:** `ControlsTest.rateWindowDoesNotOverIncrementOnRefusal`, `GovernanceEngineTest.rateLimitRefusalDoesNotConsumeCapacity`.
- **Source:** 0.1 defect.

## F11. Circuit breaker

- **Solution:** Per source. It opens after N consecutive failures, then lets exactly one probe
  through (half-open). A probe denied later in the chain is released.
- **Design:** `engine.control.CircuitBreakers`.
- **Tests:** `GovernanceEngineTest.breakerOpensAfterConsecutiveFailuresAndHalfOpensWithOneProbe`.

## F12. Injection and poisoning screening

- **Issue:** Instructions hidden in arguments or tool descriptions (OWASP MCP Top 10, MCP03 tool poisoning).
- **Solution:** Phrase screening over every key and value at any depth, and over the whole of each
  string in overlapping windows. Text is normalised first
  (NFKC, zero-width characters removed, whitespace collapsed), and base64-looking strings are also
  checked decoded. It applies to arguments and to tool definitions (quarantine). Modes are
  ENFORCE, AUDIT and OFF.
- **Design:** `engine.control.InjectionScreen`.
- **Tests:** `GovernanceEngineTest.nestedAndObfuscatedInjectionIsDenied`, `...poisonedDescriptionIsQuarantinedAtLoad`.
- **Limit:** This is a heuristic. It limits prompt injection; it does not prevent it.

## F13. Extension hooks over every governed operation

- **Issue:** Deployments need their own controls (PII, data classification) without forking. A hook
  that only saw `tools/call` would leave the larger surface ungoverned, because resources, prompts and
  completion are how data and instructions actually reach the model's context.
- **Solution:** One `GovernanceHook` runs for all four operations (`TOOL_CALL`, `RESOURCE_READ`,
  `PROMPT_GET`, `COMPLETION`). `before` may deny; `afterTool` and `afterContent` may rewrite or
  withhold. Hooks run in configured order at step 8, after every built-in check and before approval,
  so denial order and audit output stay deterministic. Deny codes are restricted to the registry.
  Content hooks run before redaction, the size bound and the content screen, so whatever a hook adds
  is still guarded on the way out.
- **Design:** `engine.spi.GovernanceHook` (with `HookDenied` for an explained withholding),
  `engine.control.HookRunner` shared by the tool and content chains, `config.spi.GovernanceHookFactory`.
- **Tests:** `HooksTest`, `ContentHooksTest`, `GovernanceEngineTest.hooksRunInOrderAndFailClosed`.
- **Source:** the requirement for extensible controls, adapted so deny order stays deterministic and
  enforcement fails closed by default.
- **Limit:** Listings (`tools/list`, `resources/list`, `prompts/list`) do not run hooks. They are
  filtered by exposure and patterns, redacted and screened, and are not audited. Enforcement belongs on
  the read, not the listing: hiding an item does not stop a caller asking for it by name.

## F13a. Hook modes and conditions

- **Issue:** Turning on a new control in a live deployment is a gamble: enforce it and you may break
  legitimate traffic, leave it off and you learn nothing. And one hook is rarely right for every
  caller, tool and backend.
- **Solution:** Each hook is bound with a `mode` and conditions. `ENFORCE` denies, `AUDIT` records
  what it would have done under `hookAudit` in the audit record and changes nothing at all (it cannot
  deny and cannot rewrite the response), `OFF` never runs it. Conditions narrow a hook by
  `operations`, `methods` (globs), caller `groups` and `sources`. Host-supplied hooks always enforce
  for every operation: a host that compiled a control in did not ask for it to be advisory.
- **Design:** `engine.control.HookBinding`, reusing the existing `ControlMode`.
- **Tests:** `HooksTest.enforceDeniesAndAuditOnlyRecords`, `...offIsNeverConsulted`,
  `...conditionsNarrowAHookToOperationsMethodsGroupsAndSources`,
  `ContentHooksTest.auditModeRecordsWhatItWouldHaveDoneAndChangesNothing`.
- **Limit:** `AUDIT` proves what a hook would have blocked, not that blocking it is safe.

## F13b. Out-of-process hooks (`type: remote`)

- **Issue:** Requiring a Java class for every custom control excludes the teams most likely to have
  one (a data team's classifier, a security team's service written in Python or Go) and makes every
  small policy change a jar release.
- **Solution:** A built-in `remote` hook POSTs a description of the operation to an HTTP endpoint,
  which answers `{"allow": true}` or `{"allow": false, "code": ..., "reason": ...}`. Anything else,
  including a non-200, a malformed body, a non-boolean `allow` or a timeout, is a denial. Arguments
  and results are withheld unless the operator opts in (`includeArguments`, `includeResult`); the
  endpoint always gets the caller, operation, typed resources and argument digest. The endpoint URL is
  checked against the egress allow list **at startup**, so a configured control cannot quietly become
  an unreviewed data path.
- **Design:** `config.hooks.RemoteHook` over `BoundedHttp`, wired as a built-in `type` in
  `GatewayFactory`.
- **Tests:** `RemoteHookTest`: answer contract, unreachable and slow endpoints, what leaves the
  gateway, phases, wiring through configuration, and the startup egress refusal.
- **Limit:** The hook's own call is checked at startup, not per request, so a name that later resolves
  elsewhere is not re-checked. Prefer an IP literal or a CIDR for the endpoint if that matters.

## F13c. Read-only statement guard (`type: sql-readonly`)

- **Issue:** A tool that forwards free text to a SQL endpoint cannot be classified by reading its
  configuration. `POST /sql` with `select 1` is a read; the same tool with `drop table orders` is not. A
  `READ` tool needs no write group, no approval and no dry-run receipt, so a tool named
  `run_sql_readonly` classified `READ` carried **no control at all**. Aegis 0.1 had a `ReadOnlyGuard`
  SPI for this; 2.0 had dropped it, which was a governance regression found by the design review
  ([DESIGN-REVIEW-AND-GAPS.md](DESIGN-REVIEW-AND-GAPS.md) §1).
- **Solution:** A built-in hook that strips comments and quoted text, then requires exactly one
  statement, a reading first keyword (`SELECT`, `WITH`, `SHOW`, `EXPLAIN`, `DESCRIBE`, `DESC`,
  `VALUES`, `TABLE`) and no writing or session-changing keyword anywhere. Unterminated quotes and
  comments are refused rather than guessed at. A call carrying none of the named arguments is denied,
  so a misbound guard cannot silently pass everything. Bound to the SQL-bearing HTTP tools and reinforced
  inside `jdbc_run_query` for the Hive and Calcite JDBC configurations.
- **Design:** `config.hooks.SqlReadOnlyHook`, wired as a built-in hook `type` in `GatewayFactory`.
- **Tests:** `SqlReadOnlyHookTest`: statements that must pass include keywords inside literals,
  quoted identifiers, comments and `OFFSET` (which must not match `SET`); 29 that must be refused
  including multi-statement, `SELECT ... INTO OUTFILE`, `WITH ... INSERT` and unterminated quotes.
  `AdapterConfigsTest.sqlToolsClassifiedReadCannotBeUsedToWrite` proves it end to end through all four
  configs: the read path still works and six writing statements per tool are denied with
  `INVALID_INPUT` without reaching the backend.
- **Limit:** a keyword and shape guard, not a SQL parser, and dialect-agnostic. A
  statement that passes is not proven read-only, because `select my_function()` passes and a
  user-defined function can usually write. The control that actually enforces read-only is a **read-only
  credential** on the source; this hook narrows the gap, and an operator who wants certainty classifies
  the tool `MUTATE`.

## F14. Per-call human approval

- **Issue:** Write calls need a human decision about the exact action. The 0.1 approval consume
  was not atomic.
- **Solution:**
  - An approval request is deduplicated per caller and action.
  - An approver group decides. No identity involved in the request (actor, effective subject,
    or anyone in the delegation chain) may approve it, and delegated callers may not approve at all.
    Names are compared after Unicode NFKC normalisation and case folding, so a differently spelled
    account (`OLGA` for `olga`) is still the requester.
  - The approver sees evidence, not a digest: the redacted arguments (capped at 8 KiB) and, after a
    dry run, the redacted preview.
  - Every approve or deny is journaled (`APPROVAL_DECISION`) before it takes effect; if the journal
    cannot record it, the decision is not made (503).
  - The approver never receives the token; the requester fetches it.
  - Each caller may open at most 100 new approval requests per hour.
  - The HMAC token is bound to the caller and the full action: gateway name, tool, class, source,
    typed resources, argument digest and the pinned tool-definition digest. It expires and is
    single-use, so it cannot be replayed on another gateway sharing the key or after the tool changed.
  - It is verified at its step and consumed atomically at commit.
  - It is evaluated after the automated controls, so approvers only see calls that would pass.
- **Design:** `engine.approval.ApprovalService`, `SignedTokens`, `mcp.admin.AdminApi`.
- **Tests:** `GovernanceEngineTest.mutateNeedsApprovalBoundToCallerToolAndExactArgumentsAndIsSingleUse`,
  `...separationOfDutiesHoldsAcrossDelegationAndDelegatedCallersCannotApprove`, `...newApprovalRequestsPerCallerAreBounded`,
  `GatewayHostTest` (over HTTP, self-approval 403), `SeparationOfDutiesTest` (seven adversarial
  cases: respelled requester, respelled delegated subject, definition change, receipt after a
  definition change, cross-gateway replay, approver evidence, unrecordable decision), Knox cold
  install V1 to V7, and every `ExampleConfigsTest` flow.

## F15. Validate-run-promote receipts (DESTRUCTIVE)

- **Issue:** 0.1 fingerprinted `toString()` of values, and its receipt check was get-then-remove.
- **Solution:** The dry run issues an HMAC receipt, keyed separately from approvals, bound to the
  same full action binding as the approval token and single-use. The dry run can be performed three ways:
  - `GATEWAY`: governance preflight only.
  - `ARGUMENT`: the backend's native preview, for example Kafka `validateOnly`.
  - `TOOL`: a preview tool, for example loading the Iceberg table first.
  - Startup rejects configurations where a "preview" would really be the real call: an ARGUMENT flag
    must be in the input schema and HTTP request template; a TOOL preview must be an explicit `READ`
    tool in the same source. A converter source resolves the preview tool's own executor, rather than
    falling back to the destructive executor.
- **Design:** `engine.approval.VrpService`, `catalog.DryRunSpec`, `GatewayFactory.SourceBuild`.
- **Tests:** `GovernanceEngineTest.destructiveFollowsValidateApprovePromoteAndNeverSendsControlValuesToBackend`,
  `ExampleConfigsTest.iceberg` (TOOL), `.kafkaConnectAndAdminClient` (ARGUMENT),
  `DryRunPreviewToolTest` (converter-shaped source runs the READ preview and never the destructive
  operation), `RealKafkaTest` (a real broker validates without creating),
  `SeparationOfDutiesTest.aDryRunReceiptCannotPromoteAfterTheToolDefinitionChanged`.

## F16. Tamper-evident audit, intent before execution

- **Issue:** 0.1 audited after the backend call, failed open, and had no restart recovery.
- **Solution:**
  - Each record is canonical JSON with `prev` and `hash` (SHA-256 chain).
  - `ALLOW_INTENT` is written before execution. If that write fails, the call is not executed
    (`AUDIT_UNAVAILABLE`).
  - The last record is verified on open, and a torn tail refuses to start.
  - The verifier is available as a CLI.
  - Arguments are stored as a digest unless enabled.
- **Design:** `engine.audit.HashChainFileAuditSink`, `AuditChainVerifier`.
- **Tests:** `HashChainFileAuditSinkTest`, `GovernanceEngineTest.auditFailureDeniesBeforeExecution`,
  Knox cold install (13 records verified across a gateway restart).
- **Limit:** No external anchoring of the head hash.

## F17. Output bounding and redaction

- **Solution:** Common secret shapes are redacted in every string at any depth of `content` and
  `structuredContent`. Output is truncated past `maxBytes`. Counts are recorded in `_meta` and audit.
- **Design:** `engine.control.OutputGuard`.
- **Tests:** `GovernanceEngineTest.outputSecretsAreRedactedAtAnyDepth`.

## F18. Tool catalog integrity (rug pull defence)

- **Issue:** A server can redefine a tool mid-life, often only its description. 0.1 names could
  collide, and the last registration won.
- **Solution:** Definitions are digested. Pins come from config, or from TOFU with an optional pin
  file.
  - A mismatch, or an unpinned tool under REQUIRE_PINS, is quarantined (`CATALOG_INTEGRITY`).
  - Tools that appear late are quarantined unless opted in.
  - An operator accepts a changed definition through the admin API.
  - Duplicate names abort startup. On refresh, a source with a duplicate keeps its previous entries
    and raises `SOURCE_INVALID`.
- **Design:** `engine.catalog.ToolCatalog`, `CatalogIntegrity`.
- **Tests:** `GovernanceEngineTest.rugPullQuarantinesToolUntilOperatorAccepts`, `...duplicateToolNamesAbortLoad`,
  `ExistingMcpServerProxyTest.rugPullOnDownstreamServerIsQuarantinedOnRefresh`.

## F19. Classification can only rise

- **Issue:** The 0.1 overlay allowed a DESTRUCTIVE tool to be downgraded to READ.
- **Solution:** Operator class, otherwise `defaultClass` (DESTRUCTIVE). An upstream `destructiveHint`
  can raise the class, and nothing lowers it. `unlistedTools: HIDE` exposes only listed tools.
- **Design:** `GatewayFactory.SourceBuild.bind`, `ToolClass.max`.
- **Tests:** `GatewayFactoryTest.yamlConfigBuildsAGovernedGatewayOverRestAndAnExistingMcpServer`,
  `ExistingMcpServerProxyTest.unclassifiedDownstreamToolDefaultsToDestructive...`, `ExampleConfigsTest.existingMcpServerOverHttpAndStdio`.
- **Source:** 0.1 defect.

## F20. Dual-era MCP server (stateful 2025, stateless 2026-07-28)

- **Issue:** 0.1 left `protocolVersion` unused. The 2026-07-28 revision removes sessions and
  `initialize` and requires header and body agreement.
- **Solution:**
  - Modern requests are selected by `_meta`. `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name`
    are required, and the base64 sentinel is decoded. A mismatch returns 400 / -32020 and an
    unsupported version returns 400 / -32022 with `supported`.
  - `server/discover` is implemented. List results carry `ttlMs` and `cacheScope: private`.
  - Legacy sessions are caller-bound (a hijack attempt gets 404) and can be deleted with DELETE.
  - A stateless legacy mode assumes 2025-03-26 when the version header is missing.
  - In 2025-era requests, `Mcp-Method` and `Mcp-Name` are validated whenever they are present.
  - A principal may hold at most 32 legacy sessions.
  - Origin is checked and batches are rejected. Resources, prompts and completion are served only
    when a source enables them (F28); subscriptions and `logging/setLevel` are not served.
- **Design:** `mcp.server.McpServerHandler`, `SessionRegistry`, `protocol.*`.
- **Tests:** `McpServerHandlerTest`, Knox cold install K6 (header smuggling refused inside Knox).

## F21. Hooking existing MCP servers (HTTP and stdio)

- **Solution:**
  - `AUTO` era detection, per the 2026-07-28 backward-compatibility procedure.
  - An expired legacy session is re-established once.
  - SSE responses are parsed, and server-to-client requests on a stream are refused.
  - Responses are bounded and redirects are not followed.
  - stdio children get a minimal environment, so gateway secrets do not leak to them.
  - Control values are never forwarded.
- **Design:** `mcp.client.HttpMcpClient`, `StdioMcpClient`, `McpProxyToolSource`.
- **Tests:** `ExistingMcpServerProxyTest`, against a handler-independent test server,
  covering modern, legacy with session, legacy stateless, and stdio in both eras).

## F22. Declarative REST tools (configuration only)

- **Issue:** 33+ Apache projects must not each need a Java adapter.
- **Solution:**
  - Path placeholders become single percent-encoded segments. They must be required properties,
    and `.` and `..` are refused.
  - A path-typed placeholder `{arg/}` carries several segments, for APIs whose identifier is itself a
    path (HDFS files, ZooKeeper znodes, object-store keys). Each segment is validated and encoded
    separately, so depth is allowed but climbing is not.
  - Query templates are literal or exact `{arg}`, in deterministic order.
  - Body templates are typed with no string splicing.
  - Response pointers select what the model sees.
  - 5xx and 429 responses count against the breaker.
- **Design:** `config.http.HttpToolExecutor`, `HttpToolSpec`.
- **Tests:** `GatewayFactoryTest.httpTemplatesAreInjectionSafe`,
  `GatewayFactoryTest.pathTypedPlaceholderAcceptsDepthButNotTraversal`, `ExampleConfigsTest` (Flink,
  Spark, Iceberg, Kafka Connect), `AdapterConfigsTest` (**25 deployable adapter configurations and 116 tools**),
  `RealFlinkTest`, `RealIcebergRestCatalogTest`, `RealKafkaTest`, `RealSparkTest`.

## F22a. The 0.1 Java adapter modules become configuration

- **Issue:** 0.1 shipped one Maven module per engine: 33 modules, each with a Java class that built
  URLs by string concatenation, its own `tools.yaml`, its own test and its own release surface. Every
  new engine meant new code to review, and every adapter was a place a governance mistake could hide.
- **Solution:** Twenty-four usable 0.1 adapter surfaces plus the new Apache Burr (incubating) integration are now **25
  deployable adapter configurations and 116 tools** under `adopters/configurations/adapters/`. REST surfaces use
  declarative `http` tools. Hive and Calcite use the JDBC converter because their earlier HTTP paths did
  not exist; ZooKeeper uses its client-protocol converter for znodes and HTTP only for its real
  AdminServer commands. The Arrow, Beam, Hudi, Kudu and Ozone files were removed: a product-named
  configuration whose backend routes do not exist is not a feature. Their 18 old tools have explicit
  absent verdicts until real typed sources exist. The shared governance engine remains the sole policy owner: arguments are
  schema-validated; named objects become typed resources; writes need approval; destructive calls need a
  dry-run receipt.
- **Where the mapping is not one to one**, stated rather than hidden:
  - The 0.1 `<engine>://status` MCP resources are represented by governed READ tools where the product
    has an equivalent supported surface.
  - ActiveMQ's `purge_queue` no longer lets the caller influence the JMX MBean name: the MBean is fixed
    by the operator and only `destinationName` comes from the caller.
  - Pulsar's namespace and topic are separate arguments, so each is validated and the typed resource is
    precise.
  - Arrow Flight and Beam JobService need typed gRPC sources. Hudi table services need engine
    procedures, Kudu needs a version-specific API/client, and Ozone needs a real protocol source.
    None is represented by an HTTP configuration until that implementation exists.
  - Flink, Kafka, Iceberg and Spark remain reference-depth integrations; the Kafka Admin API, JDBC and
    ZooKeeper sources demonstrate the typed converter seam.
- **Design:** `adopters/configurations/adapters/*/*.yaml`, `aegis-source-jdbc` and
  `aegis-source-zookeeper`.
- **Tests:** `AdapterConfigsTest` loads all **25 deployable adapter configurations and 116 tools** and exercises
  their governance flow. `DocumentationClaimsTest` binds the published inventory to those files.
  `JdbcToolSourceTest` and `ZooKeeperToolSourceTest` exercise converter behavior against actual Apache
  software. `ZooKeeperAdminCommandsTest` checks command names against ZooKeeper's own registry.
- **Evidence rule:** Contract tests prove Aegis renders and governs the configured request. A real target
  run certifies a product version. [VALIDATION.md](VALIDATION.md) and
  [evidence/CLAIMS-MATRIX.md](evidence/CLAIMS-MATRIX.md) record that distinction.

## F22b. OpenTelemetry span export without an OpenTelemetry dependency

- **Issue:** Aegis already continued and propagated W3C trace context and exposed Prometheus metrics, but
  exported no spans, so the gateway was invisible in a tracing backend: an operator could see that a
  call was denied but not where the hop sat in the agent's trace. Adding the OpenTelemetry SDK would put
  a second, heavier dependency graph in front of every deployment and break the zero-dependency property
  that lets the engine drop onto a Knox or engine classpath.
- **Solution:** One span per governed request, exported over OTLP/HTTP with a JSON body, built from the
  existing JSON writer and `BoundedHttp`. Off unless `tracing.otlp.endpoint` is set; the endpoint is
  checked against the egress allow list at startup. The request thread only offers the span to a bounded
  queue, a daemon thread batches and posts, and a full queue drops and counts spans, so a broken, slow or
  absent collector cannot add latency to a governed call or fail one. Span attributes deliberately carry
  no caller identity, arguments or results; correlation to the audit journal is by `aegis.request_id`.
- **Design:** `OtlpSpanExporter` in the `aegis-observability-otlp` module, as a `DecisionListener`; `DecisionListener.Event` gained a
  `Span` component (span id, parent span id, start time) so an exporter has the identity it needs.
- **Tests:** `OtlpSpanExporterTest`: document shape against the trace service contract, denial to an
  error span with the stable deny code, a text assertion that no caller or argument field appears in the
  document, root spans omitting `parentSpanId`, spans reaching a local collector test endpoint with drain on
  close, a 500 and an absent collector counted rather than thrown, and a full queue dropping instead of
  blocking.
- **Limit:** the OTLP specification encodes trace and span ids as hex in OTLP/JSON,
  departing from the protobuf JSON mapping that would use base64, and several OpenTelemetry SDKs emitted
  base64 for years. Aegis emits hex per the specification and is verified against a local test endpoint that
  asserts the document shape, **not** against a live collector. Collector compatibility is something to
  confirm in your environment, not a tested claim here.

## F23. Plugin sources and hooks via ServiceLoader

- **Solution:** `type: <name>` selects a `ToolSourceFactory`. A provider whose own dependencies are
  missing is skipped with a warning and does not break other sources.
- **Design:** `config.spi.*`, `examples.kafka.KafkaAdminToolSourceFactory`.
- **Tests:** `ExampleConfigsTest.kafkaConnectAndAdminClient` (a real Kafka 3.9.1 KRaft broker in its own JVM, default build), `RealKafkaTest` and `KafkaFrontDoorsTest` (real broker and Connect worker, `-Preal-engines`).

## F24. Strict configuration

- **Solution:**
  - Unknown keys anywhere are fatal, and the message includes the path.
  - Secrets come only from named environment variables, and startup fails fast if one is missing.
  - YAML uses the safe constructor, rejects duplicate keys, and bounds aliases, depth and size.
  - A policy with no rules is rejected.
  - The audit journal opens only after the whole document validated.
- **Design:** `config.ConfigNode`, `ConfigLoader`, `GatewayFactory`.
- **Tests:** `GatewayFactoryTest`.

## F25. The gateway's own front door

- **Solution:**
  - JDK HttpServer or HttpsServer (no servlet container), binding to 127.0.0.1 by default.
  - A bounded worker pool and bounded bodies.
  - JWT: an algorithm allow list (no `none`, no RS/HS confusion), exact issuer, required audience,
    bounded JWKS with fail-closed expiry and kid rotation, and RFC 8693 `act` as the actor chain.
  - Static tokens hashed with SHA-256.
  - A stdio host, and the admin API.
- **Design:** `host.*`.
- **Tests:** `GatewayHostTest`: HS256 over real HTTP, RS256 JWKS rotation and delegation, and stdio.

## F26. Knox 3.0 extension

- **Solution:**
  - A native JAX-RS service (the KnoxToken pattern) on `aegis-mcp/v1/**?**`.
  - Identity comes from the Knox Subject.
  - Route-local 503 when the config is bad, and lifecycle close on undeploy.
  - Compiled against exact Knox 3.0.0 artifacts, `provided` scope.
- **Design:** `knox.*`.
- **Tests:** `KnoxExtensionTest`, and the stock Knox 3.0.0 cold-install evidence linked from [VALIDATION.md](VALIDATION.md).

## F27. Multi Round-Trip Requests (2026-07-28)

- **Solution:** For `tools/call`, `resources/read` and `prompts/get`: the client's declared
  elicitation/sampling/roots capabilities are forwarded; an `input_required` result passes to a
  2026 client redacted (content-screened for resources and prompts); the retry is governed again as
  a new request, with `inputResponses` screened and URL-checked like arguments and `requestState`
  bounded and forwarded unchanged. A 2025 client receives an explanatory error, and MRTR fields in a
  2025 request are rejected.
- **Design:** `RoundTrip`, `McpServerHandler.callTool/roundTrip/legacySafe`, `McpProxyToolSource`,
  `McpContentSource`, `ContentGovernor.inputRequired`.
- **Tests:** `McpRoundTripTest.toolInputRequiredIsRelayedRedactedAndTheRetryIsGovernedAndForwarded` (a backend tool that returns an `input_required` result: passed through to a
  2026 client with redaction applied, explanatory error for a 2025 client, and the retry governed
  as a new call).

## F28. Governed resources, resource templates, prompts and completion

- **Issue:** Resources and prompts put data and instructions straight into the model's context.
  2.0 first refused them entirely; adopters with existing MCP servers need them, governed.
- **Solution:** Off by default per source. Each `resources/read`, `prompts/get` and
  `completion/complete` is a READ action through exposure, input, scope, policy (tool = the MCP
  method, resource `mcp:resource:<uri>` / `mcp:prompt:<name>`), egress, rate, breaker, argument
  screening, intent audit, execution, then rebuilt results (spec fields only), redaction, binary
  refusal unless allowed, size bound and content screening. A URI routes to exactly one source;
  returned content URIs must stay inside that source's patterns. Lists are filtered, redacted,
  screened and rate limited. Capabilities advertise only what is served; subscriptions and
  `logging/setLevel` stay unsupported and unadvertised. Denials are JSON-RPC -32010 with the stable code.
- **Design:** `ContentGovernor`, `ContentSettings`, `ContentSource` SPI, `McpContentSource`,
  `McpServerHandler.contentRequest`, `GatewayFactory.contentSettings`.
- **Tests:** `ContentGovernanceTest`, covering both eras and stdio against the handler-independent test server,
  `GatewayFactoryTest.resourcesAndPromptsAreOptInStrictAndServedThroughTheHandler`.
- **Source:** 2.0 scope gap (review request: "all MCP ... should be in the project").

## F29. Prompt definition pinning (rug pull for prompts)

- **Issue:** A server can change a prompt's description or arguments after it was trusted.
- **Solution:** Prompt definitions are digested and pinned exactly like tools (key `prompt:<name>`);
  with a signed server catalog, the pin must be in the record of the source that advertised the prompt;
  a changed or poisoned definition is quarantined, hidden and denied with `CATALOG_INTEGRITY` until
  an approver accepts the exact reviewed digest using `POST /prompts/{name}/accept` with one
  `Aegis-Definition-Digest: sha256:...` header. Prompt message text is templated per call, so it is
  screened rather than pinned.
- **Design:** `ContentGovernor.buildPrompts/classify/acceptPrompt`, `CatalogIntegrity`, `AdminApi`.
- **Tests:** `ContentGovernanceTest.promptRugPullIsQuarantinedUntilAccepted`, `promptsArePrefixedValidatedAndScreened`.

## F30. W3C trace context

- **Issue:** An agent request crosses the client, the gateway and a backend; operators need one id
  to join them, and the audit record must name it.
- **Solution:** `traceparent` from `_meta` (or the HTTP header as fallback) is strictly parsed per
  W3C (invalid values start a new root), continued with the gateway's own span id, written to audit
  (`traceId`, `spanId`, `parentSpanId`), returned in the decision `_meta`, and forwarded to HTTP
  backends and downstream MCP servers (header and `_meta`). No exporter: correlation only.
- **Design:** `TraceContext`, `ExecutionContext.traceparent`, `HttpToolExecutor`, `McpProxyToolSource`,
  `McpContentSource`, `McpServerHandler.post`.
- **Tests:** `ObservabilityTest` (parsing rules, audit, forwarding), `TracePropagationTest` (downstream
  MCP server sees it, both carriers), `GatewayHostTest.metricsAreAuthenticatedAndTraceReachesTheRestBackend`.

## F31. Decision metrics (Prometheus)

- **Issue:** Operators need rates of allowed and denied decisions per tool and source.
- **Solution:** `DecisionListener` SPI (read-only; failures ignored) and `DecisionMetrics`: counters
  by kind, method, source and outcome, backend calls, latency histogram, Prometheus text 0.0.4,
  zero dependencies. Label values are bounded by configuration (unknown tools fold into `_unknown`);
  no caller, resource or argument is ever a label. The gateway's own `/metrics` is authenticated (optional
  group restriction); Knox serves `aegis-mcp/v1/metrics` behind the topology's providers.
- **Design:** `spi/DecisionListener`, `observe/DecisionMetrics`, `AegisServer.metrics`, `AegisMcpResource.metrics`.
- **Tests:** `ObservabilityTest`, `TracePropagationTest`, `GatewayHostTest`.

## F32. Spark Connect source

- **Issue:** Spark Connect is the modern way to reach Spark data and SQL, but it is gRPC, not REST.
- **Solution:** `type: spark-connect` plugin using the official Spark Connect JVM client; Spark runs
  in its own process. Five READ-by-construction tools, `spark_sql` and `spark_drop_table` as
  DESTRUCTIVE with TOOL previews (`EXPLAIN`, describe). Identifiers validated and back-quoted; rows
  capped; timeouts interrupt the Spark operation by tag; Spark analysis errors are caller errors,
  transport failures trip the breaker; a token is only sent over TLS; the endpoint passes the
  egress guard as `http(s)://host:port`.
- **Design:** `examples/aegis-spark-connect` (`SparkConnectToolSource`, `SparkConnectSettings`, factory);
  `examples/aegis-gateway-spark` (runnable jar).
- **Tests:** `SparkConnectSourceTest`, `RealSparkConnectServerTest` against a real Spark
  4.0.1 Spark Connect server in a separate JVM), the tutorial run.

## F33. One-file Knox install

- **Issue:** Five jars in `ext/` is easy to get partly wrong.
- **Solution:** An optional `knox-bundle` classifier that merges exactly the five Aegis modules Knox
  needs (engine, mcp, observability-otlp, config, extension) and their ServiceLoader files, and nothing of Knox, Jersey, Jackson or servlet.
- **Design:** `aegis-knox3-extension/pom.xml` (shade with an explicit include list).
- **Tests:** jar content check (`KnoxPackagingBoundaryTest`) and the Knox cold-install evidence linked from [VALIDATION.md](VALIDATION.md).

## F34. Per-caller backend credentials (RFC 8693 token exchange)

- **Issue:** A static source credential makes every caller the same account to the backend, so the
  backend's own authorization and audit cannot tell users apart.
- **Solution:** `credentials.type: token-exchange` sends the caller's verified inbound JWT to the
  identity provider with the gateway's client credentials and presents the returned backend-audience
  token. The inbound token is held in a request-scoped `SubjectToken`, outside `CallerIdentity`, so it
  never reaches audit, hooks or policy input, and it is never sent to a backend. No fallback, and a
  per-token cache. An issued token that parses as a JWT must carry a non-blank string `sub` equal to
  the caller's subject; opaque tokens are passed on as issued.
- **Design:** `config.credentials.TokenExchangeCredentials`, `SourceCredentialProvider`,
  `engine.spi.SubjectToken`, bound by `AegisServer` for JWT callers.
- **Tests:** `TokenExchangeCredentialsTest`; Keycloak 26.8.0 run ([evidence](evidence/TOKEN-EXCHANGE-KEYCLOAK.md)).
- **Limit:** own host only; inside Knox such a source refuses every call.

## F35. A2A agents as governed tools

- **Solution:** `type: a2a` (`aegis-source-a2a`) offers one A2A 1.0 agent as one tool over JSON-RPC
  `SendMessage`. The card's endpoint must be on the card's origin or pinned, and it is part of the tool
  description, so a moved endpoint quarantines the tool.
- **Tests:** `A2aToolSourceTest`; `A2aAgentFrontDoorsTest` against an `a2a-sdk` 1.2.1 agent, both front doors.
- **Limit:** no A2A front door; no 0.3 cards, streaming, task continuation or card signature check.

## F36. OpenAPI to a reviewable draft

- **Solution:** `--openapi-draft` turns an OpenAPI 3.x document into an `http` source with every tool
  `DESTRUCTIVE` and a deny-all policy, listing what it could not express as warnings.
- **Tests:** `OpenApiDraftTest`, `OpenApiDraftCommandTest`, `OpenApiDraftIcebergTest` (Iceberg 1.10.1 document and server).

## F37. Reference hook services

- **Solution:** `adopters/hooks/` has a PII guard, a secrets guard and a change window in Python's
  standard library, speaking the `type: remote` contract.
- **Tests:** `test_hooks.py`; `e2e_gateway.py` against the running gateway jar (also in CI).

## F38. Container image and Helm chart

- **Solution:** a non-root image with digest-pinned bases, and a StatefulSet chart whose templates refuse
  unsafe values (loopback listener, journal off the volume, several replicas without shared state).
- **Tests:** helm lint, kubeconform, hadolint and `charts/aegis/ci/start_rendered.py`; the image is built
  and started in CI ([chart README](../charts/aegis/README.md)).

## F39. Per-tool OAuth action scopes

- **Issue:** route scopes say who may reach `/mcp`, not which tool a token may call.
- **Solution:** `tools.<name>.actionScopes` lists OAuth scopes the caller's verified JWT must carry.
  Matching is exact; every listed scope is required. The check runs at step 2, before resource scopes,
  policy, approval, credentials or the backend, and a miss is `SCOPE_DENIED`. The scopes are part of
  the approval and dry-run binding only when non-empty, so unscoped tools keep their digests. On the
  own host a JWT caller refused for missing action scopes gets HTTP 403 with
  `WWW-Authenticate: Bearer error="insufficient_scope", scope="<route scopes> <tool scopes>"` (the MCP
  step-up flow); the body is still the JSON-RPC result with `SCOPE_DENIED`.
- **Design:** `JwtVerifier.verifyWithScopes`, `CallerIdentity.scopes`, `GovernedTool.actionScopes`,
  `Action.requiredActionScopes`, `GovernanceEngine` (step 2), `engine.spi.ScopeChallenge`,
  `AegisServer.stepUp`.
- **Tests:** `GovernanceEngineTest.missingActionScopeDeniesBeforeCredentialsOrBackendAndFullGrantProceeds`,
  `GatewayFactoryTest.actionScopesAreTrustedToolRequirementsAndDenyBeforeBackendAccess`, `JwtVerifierTest`,
  `ProtectedResourceMetadataTest.aToolCallWithoutTheToolsActionScopesGetsAStepUpChallengeNamingThem`.
- **Limit:** only the own host's JWT identity carries scopes. Static, SPIFFE, stdio and Knox callers
  carry none and are denied a scoped tool. Values must be RFC 6749 scope tokens.

## F40. Signed server catalog and break-glass containment

- **Issue:** whoever can write the configuration could add a server, repoint one or add a tool.
- **Solution:** `serverCatalog` is an offline Ed25519-signed register of which `sources[]` may start,
  each ACTIVE one bound to its configuration digest and its exact tool pins. It forces
  `REQUIRE_PINS` and refuses run-time acceptance. A break-glass order signed with a separate key can
  quarantine or revoke listed servers for at most 24 hours; it cannot activate anything, and an
  expired order is a startup error. The host can pin the review key outside the configuration
  (`--require-server-catalog <review.pub>`, `AEGIS_REQUIRE_SERVER_CATALOG_KEY` or
  `-Daegis.requireServerCatalogKey`, an absolute path): the gateway then refuses a configuration without
  `serverCatalog` or with a different `publicKeyFile`. Catalogs, manifests and orders can carry a
  signed `serial` (`--serial <n>` on the create commands); the highest accepted serial per kind is kept
  in the state store and an older or unnumbered file is then refused.
- **Design:** `engine.catalog.SignedServerCatalog`, `BreakGlassContainment`, `SignedSerial`,
  `GatewayFactory`, `gateway.ServerCatalogCommands` (`--server-catalog-create/-verify`,
  `--break-glass-create/-verify`).
- **Tests:** `SignedServerCatalogTest`, `SignedSerialTest`, `ServerCatalogConfigTest` (including
  `aHostPinnedReviewKeyCannotBeDroppedOrSwappedByEditingTheConfiguration` and
  `anOlderSignedCatalogOrOrderIsRefusedOnceANewerOneWasAccepted`), `ServerCatalogFlowTest`; the
  real-engine `FlinkFrontDoorsTest`, `IcebergFrontDoorsTest`, `SparkFrontDoorsTest` and
  `KafkaFrontDoorsTest` run with a signed catalog through both doors (local, not retained); four runnable servers in
  [examples/break-glass-mcp-servers](../examples/break-glass-mcp-servers/README.md).
- **Limit:** startup only (no hot reload, no runtime registration). Rollback protection lasts as long
  as the state store: across restarts with `derby-local` or `postgres` state, for one process with
  in-memory state. The host trust anchor applies when the host sets it.

## F41. Offline policy test

- **Issue:** a policy change should be checked before it deploys, without a backend.
- **Solution:** `--policy-test <aegis.yaml> <cases.json>` loads a configuration that contains only
  declarative `type: http` sources, moves audit and state to a temporary directory, removes tracing
  and source credentials, then evaluates each case (caller, tool, arguments) through the same exposure,
  schema, resource, scope and built-in rule-policy steps as a live call. It refuses a remote OPA PDP,
  MCP and plugin sources before they could contact a backend. It prints each case with
  the deny code, `APPROVAL_REQUIRED` or `ALLOWED`, and exits 0 when every case matches its `expect`,
  1 when one does not, 2 on a usage error. Nothing is executed, journaled or approved.
- **Design:** `gateway.PolicyTestCommand`, `GovernanceEngine.explain` (shares `preflight` with `call`).
- **Tests:** `PolicyTestCommandTest`, `GovernanceEngineTest.explainGivesTheDecisionACallMeetsWithoutJournalingStateOrBackend`.

## Deviations from 0.1 conventions (for maintainer review)

1. **AGENTS.md rule:** every MUTATE and DESTRUCTIVE tool must list `approvalToken` in `required[]`.
   2.0 carries control values in `_meta` and advertises the 0.1 argument names as optional. If the
   token were `required`, a strict client could not make the first call that opens the approval
   request.
2. **Evaluation order:** approval is evaluated after steps 5 to 8, as explained in F14. The step
   number (4) is unchanged.
3. **New deny codes:** `CATALOG_INTEGRITY` and `AUDIT_UNAVAILABLE` (see DENY-CODES.md).
