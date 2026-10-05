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
# Governance mapping: Aegis controls and regulatory frameworks

## Read this first

This document is a **control mapping**. It says which Aegis control produces evidence that is useful
when someone asks you a regulatory or audit question about what your AI agents did to your data
systems. It is written so an engineer and an auditor can read the same table.

It is **not** any of the following, and nothing in this repository should be read as claiming
otherwise:

- It is not a statement that Aegis makes you compliant with any law, regulation or standard.
  Compliance is a property of your organisation, your deployment, your data and your processes. A
  gateway is one technical control inside that.
- It is not a certification, an attestation, an audit opinion or legal advice. Aegis has not been
  assessed by any certification body, notified body, auditor or regulator. There is no SOC 2 report,
  no ISO certificate and no conformity assessment for this project.
- It is not a claim that installing Aegis with the shipped example configuration satisfies anything.
  Every control below is configuration. A control you did not turn on produces no evidence.
- It is not a complete list of what a framework requires. Most obligations in these frameworks are
  about your model, your training data, your risk process, your people and your documentation. Aegis
  sits at one boundary: the moment an AI agent asks a data system to do something.

Where a framework requirement is outside what a gateway can touch, this document says so in
[What Aegis does not cover](#what-aegis-does-not-cover) rather than stretching a control to fit. If
you need one sentence to give a reviewer: *Aegis governs and records agent-to-system actions, which
is evidence for access-control, logging, human-oversight and robustness requirements, and is evidence
for nothing else.*

Legal and standards references were checked against public sources on **2026-09-27** and are dated
where the date matters. Law in this area is moving quickly. Verify against the current consolidated
text before you rely on any date or article number here.

## Contents

- [The control inventory](#the-control-inventory)
- [EU: AI Act](#eu-ai-act)
- [EU: GDPR](#eu-gdpr)
- [EU: DORA and NIS2](#eu-dora-and-nis2)
- [United States: NIST AI RMF](#united-states-nist-ai-rmf)
- [United States: SOC 2 and sectoral rules](#united-states-soc-2-and-sectoral-rules)
- [International standards: ISO/IEC 42001 and ISO/IEC 27001](#international-standards-isoiec-42001-and-isoiec-27001)
- [OWASP Top 10 for LLM Applications 2025](#owasp-top-10-for-llm-applications-2025)
- [OWASP Top 10 for Agentic Applications 2026](#owasp-top-10-for-agentic-applications-2026)
- [Other jurisdictions](#other-jurisdictions)
- [What Aegis does not cover](#what-aegis-does-not-cover)
- [The evidence pack](#the-evidence-pack)
- [Keeping this document accurate](#keeping-this-document-accurate)

## The control inventory

Everything mapped later in this document refers back to a control ID here. Each row names the code
that implements it and a test that fails if it stops working, so a reviewer can go from a regulatory
question to a line of code in two hops. Paths are relative to the repository root.

| ID | Control | Implementation | A test that proves it |
| --- | --- | --- | --- |
| C1 | Caller identity, issuer, auth method, effective subject and the delegation chain are resolved by the host and recorded on every decision | `aegis-mcp-security/.../identity/JwtVerifier.java`, `StaticTokenIdentity.java`, `IdentityResolver.java`, `aegis-knox3-extension/.../KnoxIdentityBridge.java`, `aegis-engine/.../model/CallerIdentity.java` | `JwtVerifierTest`, `GatewayHostTest.rs256ViaJwksWithKeyRotationAndDelegationChain`, `KnoxExtensionTest`, `GovernanceEngineTest.separationOfDutiesHoldsAcrossDelegationAndDelegatedCallersCannotApprove` |
| C2 | Exposure: a caller sees only the tools, resources and prompts its groups allow; write tools are invisible to readers | `aegis-engine/.../catalog/ToolCatalog.java`, `ToolBinder.java` | `GovernanceEngineTest.unknownToolIsNotExposedAndWriteToolsAreHiddenFromReadOnlyCallers` |
| C3 | Operator action classification READ / MUTATE / DESTRUCTIVE, with unclassified treated as DESTRUCTIVE and upstream hints able only to raise severity | `aegis-engine/.../model/ToolClass.java`, `catalog/GovernedTool.java` | `AdapterConfigsTest.everyAdapterConfigLoadsAndEveryToolIsGoverned` |
| C4 | Input validation: schema subset checking, JSON size and depth bounds, rejection of ambiguous control values | `aegis-engine/.../control/SchemaValidator.java`, `json/JsonLimits.java` | `GovernanceEngineTest.schemaViolationsAreInvalidInput`, `ambiguousControlValuesAreRejected`, `ControlsTest.schemaSubsetValidation` |
| C5 | Typed resource scoping: the object acted on is extracted from arguments by JSON pointer into a typed identifier such as `spark:table:sales.orders`, and is never widened to a wildcard | `aegis-engine/.../control/ScopeRule.java`, `catalog/ResourceRule.java`, `json/JsonPointer.java` | `GovernanceEngineTest.missingResourceIsDeniedNeverWidenedToWildcard`, `ControlsTest.resourceScopedAllowRuleNeverGrantsACallWithoutResources`, `AdapterConfigsTest.pathTypedAdaptersAcceptDepthAndRefuseTraversal` |
| C6 | Policy decision with default deny and deny-overrides, locally or delegated to OPA | `aegis-engine/.../control/RulePolicy.java`, `aegis-config/.../policy/OpaPolicy.java` | `GovernanceEngineTest.denyRuleOverridesAllowRule`, `OpaPolicyTest` |
| C7 | Human approval bound to the exact caller and to the full action (gateway name, tool, class, source, typed resources, argument digest and the pinned tool-definition digest), single use, with separation of duties: the requester cannot approve under a differently spelled name (NFKC, case-folded comparison), a delegated caller cannot approve, the approver sees redacted arguments and the dry-run preview, and every decision is journaled before it takes effect | `aegis-engine/.../approval/ApprovalService.java`, `SignedTokens.java`, `model/Action.java` (`binding`), `aegis-mcp/.../admin/AdminApi.java` | `GovernanceEngineTest.mutateNeedsApprovalBoundToCallerToolAndExactArgumentsAndIsSingleUse`, `separationOfDutiesHoldsAcrossDelegationAndDelegatedCallersCannotApprove`, `newApprovalRequestsPerCallerAreBounded`, `SeparationOfDutiesTest` (7 adversarial cases) |
| C8 | Dry run and verified-run-plan receipt: a destructive action cannot execute without a fresh, matching, unused receipt from its own preview, bound to the same full action binding as C7, so a receipt from another gateway or from before a tool-definition change is refused | `aegis-engine/.../approval/VrpService.java`, `catalog/DryRunSpec.java` | `GovernanceEngineTest.destructiveFollowsValidateApprovePromoteAndNeverSendsControlValuesToBackend`, `SeparationOfDutiesTest.aDryRunReceiptCannotPromoteAfterTheToolDefinitionChanged`, `tokensFromAnotherGatewaySharingTheKeyAreRejected` |
| C9 | HTTP egress control over every destination presented to `EgressGuard`, including HTTP(S) URLs nested anywhere in arguments, with every DNS answer returned during that check classified and metadata/private ranges denied; non-HTTP plugin and DNS timing limits are explicit in `SECURITY.md` | `aegis-engine/.../control/EgressGuard.java`, `spi/AddressResolver.java` | `GovernanceEngineTest.nestedUrlArgumentToPrivateAddressIsDenied`, `backendTargetOffAllowListIsDenied`, `ControlsTest.wildcardHostDoesNotAllowPrivateAnswersAndEveryAnswerIsChecked`, `ipv6TransitionPrefixesAreClassifiedByTheirEmbeddedIpv4`, `embeddedUrlsAnywhereInAStringAreFound` |
| C10 | Rate limits per caller and per caller-tool, weighted hourly budgets, per-source circuit breakers | `aegis-engine/.../control/RateLimiter.java`, `CircuitBreakers.java` | `GovernanceEngineTest.rateLimitRefusalDoesNotConsumeCapacity`, `breakerOpensAfterConsecutiveFailuresAndHalfOpensWithOneProbe`, `ControlsTest.rateWindowDoesNotOverIncrementOnRefusal` |
| C11 | Catalog integrity: tool and prompt definitions are pinned, a changed definition or a poisoned description is quarantined until a non-delegated approver accepts it, the acceptance intent is journaled before the pin changes (a journal failure refuses the acceptance), duplicate names abort startup | `aegis-engine/.../catalog/CatalogIntegrity.java`, `aegis-mcp/.../admin/AdminApi.java` (`acceptDefinition`) | `GovernanceEngineTest.rugPullQuarantinesToolUntilOperatorAccepts`, `poisonedDescriptionIsQuarantinedAtLoad`, `duplicateToolNamesAbortLoad`, `ContentGovernorTest.promptsArePinnedValidatedAndServed`, `AdminApiTest.quarantinedToolIsAcceptedOnlyByAnApprover`, `aDefinitionChangeTheJournalCannotRecordIsNotAccepted` |
| C12 | Content controls: injection screening over whole argument and content strings, output size bounds, secret redaction at any depth, binary refused unless allowed | `aegis-engine/.../control/InjectionScreen.java`, `OutputGuard.java`, `content/ContentGovernor.java` | `GovernanceEngineTest.nestedAndObfuscatedInjectionIsDenied`, `outputSecretsAreRedactedAtAnyDepth`, `ControlsTest.screeningCoversTheWholeStringNotOnlyTheFirstWindow`, `ContentGovernorTest.listsFilterRedactAndReportUnavailableSources` |
| C13 | Extension hooks: a deployment adds its own control (PII detector, data classification, internal policy service) in `AUDIT`, `ENFORCE` or `OFF`, bound to operations, methods, groups and sources, fail-closed in `ENFORCE` | `aegis-engine/.../control/HookRunner.java`, `HookBinding.java`, `aegis-config/.../hooks/RemoteHook.java`, `SqlReadOnlyHook.java` | `HooksTest.enforceDeniesAndAuditOnlyRecords`, `conditionsNarrowAHookToOperationsMethodsGroupsAndSources`, `anAfterHookThatFailsWithholdsAndDoesNotRelayItsOwnMessage`, `SqlReadOnlyHookTest`, `AdapterConfigsTest.sqlToolsClassifiedReadCannotBeUsedToWrite` |
| C14 | Tamper-evident audit: intent is journaled before execution, records are hash-chained, the chain is verifiable offline, and a journal failure denies the call | `aegis-engine/.../audit/HashChainFileAuditSink.java`, `AuditChainVerifier.java` | `GovernanceEngineTest.auditFailureDeniesBeforeExecution`, `HashChainFileAuditSinkTest.chainSurvivesRestartAndVerifies`, `editingOrDeletingARecordIsDetected`, `tornLastRecordRefusesToContinueTheChain` |
| C15 | Observability: Prometheus decision counters by tool, source and outcome; W3C trace context continued, recorded and forwarded; optional OTLP span export carrying no identity, arguments or results | `aegis-engine/.../observe/DecisionMetrics.java`, `Listeners.java`, `model/TraceContext.java`, `aegis-observability-otlp/.../otlp/OtlpSpanExporter.java` | `ObservabilityTest.traceIsAuditedAndForwardedToTheBackend`, `listenersSeeEveryOutcomeAndCannotBreakEnforcement`, `OtlpSpanExporterTest`, `TracePropagationTest` |
| C16 | Fail-closed by construction: when a control cannot run or a check is ambiguous the call is denied, and a denied call never resolves credentials and never reaches a backend | the engine as a whole | `GovernanceEngineTest.deniedCallNeverResolvesCredentialsOrReachesBackend`, `HooksTest.hooksRunInConfiguredOrderAndTheFirstDenialWins` |
| C17 | Own-host perimeter: route groups admit a caller to the MCP or admin route before any request body is read; browser CORS uses exact origins only; RFC 9728 protected-resource metadata binds tokens to the MCP resource; optional per-route OAuth scopes answer a valid token that lacks one with 403 `insufficient_scope` and the scope to request | `aegis-mcp-security/.../perimeter/OwnServerPerimeter.java`, `OwnServerSecuritySettings.java`, `oauth/ProtectedResourceMetadata.java` | `OwnServerPerimeterTest.routeAdmissionUsesIdentityAndRouteGroupBeforeAnyBodyIsNeeded`, `corsPolicyUsesExactOriginsAndHeaderNormalization`, `GatewayHostTest.ownHostRouteGroupsAndCorsRunBeforeMcpDispatch`, `corsPreflightCannotAdvertiseAnOriginTheMcpHandlerRejects`, `OwnServerSecuritySettingsTest.oauthMetadataRequiresAudienceBindingUnlessExplicitlyEscaped`, `ScopeEnforcementTest`, `ProtectedResourceMetadataTest.overTheWireAValidTokenWithoutTheRequiredScopeGets403InsufficientScope` |
| C18 | Source trust: an operator can quarantine a whole source with a recorded evidence reference, and a quarantined source contributes no callable tool | `aegis-config/.../GatewayFactory.java` (`sources[].trust`) | `GatewayFactoryTest.sourceTrustQuarantineHidesEveryToolUntilOperatorConfigActivatesTheSource` |
| C19 | Durable local governance state: nonces, pending approvals, rate windows and pins survive a restart of one process | `aegis-state-derby/.../derby/DerbyStateStore.java`, `aegis-config/.../spi/StateStoreFactory.java` | `DerbyStateStoreTest.stateSurvivesRestartAndExpiredStateDoesNot`, `nonceHasExactlyOneWinnerUnderConcurrency`, `rejectedRateCostIsNotChargedAndWindowRollsOver`, `GatewayHostTest.derbyLocalStateProviderIsDiscoveredByTheOwnServer` |
| C20 | Evidence reference on high-impact actions: configured classes must carry a bounded evidence reference, and configured resources are denied to every caller | `aegis-mcp-guardrails/.../guardrails/ProjectGovernanceGuardrail.java` | `ProjectGovernanceGuardrailTest.highImpactActionsNeedBoundedEvidenceAndProtectedResourcesAlwaysDeny`, `factoryParsesOnlyKnownClassesAndBounds` |
| C21 | Two host adapters, one Aegis governance implementation: only the thin extension imports Knox public APIs and all are `provided`; the own-host executable contains no Knox adapter; neither artifact packages a Knox class; both package the shared `GovernanceEngine` | module POMs and package boundaries | `KnoxPackagingBoundaryTest`, `tools/provenance/compare_knox_source.py`, `docs/evidence/KNOX-PROVENANCE.md` |

### What one audit record contains

Several mappings below depend on what is actually written, so here is the record shape, from
`GovernanceEngine.record`:

`ts`, `gateway`, `event`, `requestId`, `clientRequestId`, `traceId`, `spanId`, `parentSpanId`,
`protocolVersion`, `actor`, `effectiveSubject`, `issuer`, `authMethod`, `actorChain`, `tool`,
`toolClass`, `source`, `resources`, `argsDigest`, `dryRun`, `roundTripRetry`, `outcome`, `code`,
`step`, `reason`, `approvedBy`, `resultDigest`, `isError`, `redactions`, `truncated`,
`screeningMatch`, `hookAudit`, `durationMs`, plus the chain fields `seq`, `prev` and `hash`.

Two properties matter for privacy review. **Arguments are not stored by default**: the record carries
`argsDigest`, a hash, and raw arguments appear only if an operator sets `audit.includeArguments: true`.
**Results are never stored**: only `resultDigest`, the redaction count and a truncation flag. So the
default journal answers who did what to which object with what outcome, without holding the payload.

## EU AI Act

Regulation (EU) 2024/1689, as amended by the AI Digital Omnibus (reported as Regulation (EU)
2026/1744), which entered into force on 27 July 2026. The dates that matter now: general application
of the Act and the Article 50 transparency duties took effect on **2 August 2026** as originally set;
the Omnibus then pushed the high-risk obligations that were also due that day out to **2 December
2027** for stand-alone Annex III systems and **2 August 2028** for high-risk AI embedded in Annex I
regulated products
([Council timeline](https://www.consilium.europa.eu/en/policies/artificial-intelligence-act/timeline-artificial-intelligence/),
[Commission guidance on high-risk systems](https://digital-strategy.ec.europa.eu/en/policies/guidelines-ai-high-risk-systems)).
Because the Omnibus amended the Act, confirm article numbers and wording against the consolidated
text before citing them in a filing.

Most Aegis users will be **deployers**, not providers. That is the relevant reading below.

| Obligation family | Article (2024/1689 numbering) | Aegis controls | Coverage |
| --- | --- | --- | --- |
| Automatically generated logs over the system's lifetime, traceable to events | Art. 12, Art. 19 | C14, C15 | **Partial.** Aegis logs the action layer: every governed call, its decision, its subject and its target, hash-chained and offline-verifiable. It does not log model inputs, outputs or inference events, and it has no retention or rotation feature (see [gaps](#what-aegis-does-not-cover)). |
| Deployer duty to keep logs and to monitor operation | Art. 26, in particular the log-keeping and monitoring paragraphs | C14, C15 | **Partial.** The journal and the metrics are the artefact; keeping it for the required period is your storage and retention process, not a gateway feature. |
| Human oversight, including the ability to intervene and to stop | Art. 14, and the deployer-side oversight duty in Art. 26 | C7, C8, C3, C13 | **Direct at the action boundary.** Approval binds a named human to one specific action, once. Dry run plus receipt means a destructive action cannot run before a human saw what it would do. An `OFF` policy or a denied rule is the stop. What Aegis cannot do is oversee the model's reasoning. |
| Accuracy, robustness and cybersecurity, including resilience against attempts to alter use or performance by exploiting vulnerabilities | Art. 15 | C4, C5, C9, C10, C11, C12, C16 | **Direct for the tool-use attack surface.** Argument validation, typed scoping, egress control, rate and budget limits, catalog pinning, injection screening, fail-closed defaults. This is the area where a gateway genuinely carries weight. |
| Risk management system | Art. 9 | C13, C15 | **Supporting only.** Aegis gives you measurable decision data to feed a risk process and a hook point to implement a mitigation you identified. It is not a risk management system. |
| Post-market monitoring and serious incident reporting | Art. 72, Art. 73 | C14, C15 | **Supporting.** The journal is the forensic record for an incident narrative: what was attempted, what was denied, at which step, on whose behalf. Detecting and reporting an incident is your process. |
| Transparency duties for AI interacting with people | Art. 50 | none | **Out of scope.** Aegis governs agent-to-system calls. It has no user-facing surface and discloses nothing to a human end user. |

## EU GDPR

Regulation (EU) 2016/679.

| Requirement | Article | Aegis controls | Coverage |
| --- | --- | --- | --- |
| Data minimisation | Art. 5(1)(c) | C12, C14 | **Direct, and it is the default.** Arguments are journaled as a digest unless an operator opts in; results are never journaled; secrets are redacted at any depth before content reaches the model; output is size-bounded. OTLP spans carry no identity, arguments or results. |
| Integrity and confidentiality | Art. 5(1)(f) | C9, C12, C14, C16 | **Partial.** Egress control limits where data can go, redaction limits what leaves, the hash chain makes log tampering detectable. Encryption at rest and in transit for your systems is outside Aegis. |
| Accountability, being able to demonstrate compliance | Art. 5(2) | C14 | **Direct as evidence.** An offline-verifiable chain of who asked for what, on which object, with which outcome, is exactly the artefact this article asks you to be able to produce. |
| Data protection by design and by default | Art. 25 | C2, C3, C5, C6, C16 | **Supporting.** Least-privilege exposure, explicit action classification, object-level scoping and default deny are by-design measures for the agent access path. |
| Records of processing activities | Art. 30 | C14 | **Input only.** An Art. 30 record is an organisational document about purposes, categories and recipients. The journal tells you which systems and objects agents actually touched, which is useful input to that document and a way to check it is still true. |
| Security of processing | Art. 32 | C1, C2, C6, C7, C9, C10, C12, C14 | **Partial.** Access control, least privilege, approval for change, limits, and logging for the agent path. One layer of an Art. 32 programme. |
| Breach detection and notification | Art. 33, Art. 34 | C14, C15 | **Supporting.** Metrics give you the signal, the journal gives you the scope of what an agent reached. Notification is your process. |
| DPIA | Art. 35 | C14 | **Supporting.** Decision data helps you describe and later verify the agent processing you assessed. |
| Automated decision-making and profiling | Art. 22 | none | **Out of scope.** Aegis governs actions on systems, not decisions about people. |
| Right to erasure | Art. 17 | none, and there is real tension here | **Not supported, and the design works against it.** The journal is append-only and hash-chained on purpose: deleting or editing a record is detected as tampering (`HashChainFileAuditSinkTest.editingOrDeletingARecordIsDetected`). If your deployment could write personal data into audit records, keep `audit.includeArguments` off, and treat the journal in your retention design as an append-only security log rather than something you can selectively erase. |

## EU DORA and NIS2

For financial entities and for operators in scope of the network and information security directive,
the relevant families are ICT risk management, third-party dependency, and incident detection and
reporting.

| Requirement family | Instrument | Aegis controls | Coverage |
| --- | --- | --- | --- |
| ICT risk management framework, including access control and change control for ICT assets | DORA (Regulation (EU) 2022/2554) ICT risk management chapter; NIS2 (Directive (EU) 2022/2555) Art. 21 | C1, C2, C6, C7, C8, C16 | **Partial.** Aegis is the access and change control for one class of actor: AI agents acting on your data platform. |
| ICT third-party risk, including monitoring what an external component can do | DORA third-party chapter | C9, C11, C13 | **Partial and genuinely useful.** A proxied external MCP server is a third-party ICT dependency. Egress allow-listing, catalog pinning against definition changes, and hooks over its responses are how you bound it. |
| Detection, logging and incident reporting | DORA incident chapter; NIS2 Art. 23 | C14, C15 | **Supporting.** Same evidence story as above. Timelines and reporting channels are yours. |
| Resilience testing | DORA testing chapter | none | **Out of scope.** Aegis ships tests of Aegis, not a testing programme for your estate. |

## United States: NIST AI RMF

NIST AI 100-1 (AI Risk Management Framework 1.0, January 2023) and the Generative AI Profile
(NIST AI 600-1). Voluntary, four functions: GOVERN, MAP, MEASURE, MANAGE
([NIST AI RMF Core](https://airc.nist.gov/airmf-resources/airmf/5-sec-core/)).

Subcategory identifiers are deliberately not cited here. They are easy to misquote; map to them
against the NIST text and Playbook when you build your own crosswalk. The function-level mapping is:

| Function | What Aegis contributes | Aegis controls |
| --- | --- | --- |
| GOVERN | A single enforcement point where organisational policy about agent actions becomes machine-checked configuration, with a stable deny taxonomy so a policy statement has a testable outcome. Roles are explicit: exposure by group, approval by a human, separation of duties across delegation. | C1, C2, C3, C6, C7, C13 |
| MAP | The tool catalog plus typed resources make the actual agent-to-system attack surface enumerable: which tools exist, what class each is, which objects each can reach, which backend each hits, which hosts it needs. That inventory is usually the missing input to agentic risk mapping. | C2, C3, C5, C11 |
| MEASURE | Prometheus counters by tool, source and outcome, and an audit journal you can query, so denial rates, approval rates, screening hits and breaker trips are measured rather than assumed. Hook `AUDIT` mode measures a proposed control before it enforces. | C13, C14, C15 |
| MANAGE | Treatments that actually execute: deny, require approval, require a dry-run receipt, rate limit, quarantine a changed tool, redact, withhold output. Plus fail-closed behaviour so an unavailable control is not a silent gap. | C6, C7, C8, C10, C11, C12, C16 |

The Generative AI Profile's concerns that Aegis speaks to most directly are information security,
data privacy, value-chain and component integration, and harmful-action risk from tool use. Its
concerns about confabulation, bias, content provenance and dangerous-capability evaluation are model
concerns and are not addressed here.

## United States: SOC 2 and sectoral rules

SOC 2 uses the AICPA Trust Services Criteria. Aegis is not audited and cannot be "SOC 2 compliant";
it can be a control you present inside your own audit.

| Criteria family | Aegis controls | How it is usually presented |
| --- | --- | --- |
| CC6, logical and physical access | C1, C2, C3, C5, C6 | Agents are principals with least-privilege exposure and object-level authorisation, not shared superusers. |
| CC6 boundary protection and transmission | C9, C12 | Egress allow list over every destination and DNS answer; redaction and size bounds on what crosses the boundary. |
| CC7, system operations and monitoring | C10, C14, C15 | Decision metrics, a tamper-evident journal, breakers and limits. |
| CC8, change management | C7, C8, C11 | A mutating or destructive action requires a bound human approval and, when destructive, a dry-run receipt. A changed upstream tool definition is quarantined until accepted. |

Sectoral US rules follow the same pattern rather than needing their own mapping. For HIPAA, Aegis
controls speak to the Security Rule's access-control, audit-control and integrity safeguards for the
agent path, and to minimum necessary only insofar as exposure and scoping restrict what an agent can
reach. For financial supervision and for the SEC cyber disclosure rules, the journal is incident
evidence. None of this substitutes for a BAA, a risk analysis or a disclosure process.

US state AI law was unsettled at the time of writing. Colorado's SB 24-205 was repealed and replaced
in May 2026 with a successor taking effect 1 January 2027, after enforcement of the original was
delayed and then challenged in federal court
([Norton Rose Fulbright](https://www.nortonrosefulbright.com/en/knowledge/publications/18733d31/colorado-enacts-revised-ai-law)).
Do not build a compliance position on a state AI statute from this document; check the current text.

## International standards: ISO/IEC 42001 and ISO/IEC 27001

ISO/IEC 42001:2023 specifies an AI management system. ISO/IEC 27001:2022 specifies an information
security management system. Both are management-system standards: they are mostly about your
processes, and a gateway is an implementation of a handful of their controls.

Clause and Annex A identifiers are **not** quoted here. The standards are paywalled and not freely
redistributable, and a misquoted identifier in a compliance document is worse than no identifier.
Map these statements onto the identifiers in your licensed copy:

**ISO/IEC 42001.** Aegis is a candidate control for the Annex A areas covering the responsible use of
AI systems in operation, the recording of AI system operation, human oversight of AI system
behaviour, and the management of third-party and supplier AI components. It contributes evidence to
the operational-control and monitoring clauses of the management system. It contributes nothing to
the AI policy, impact assessment, objectives, competence, or internal audit clauses.

**ISO/IEC 27001 Annex A.** The controls Aegis implements sit in the access control and privileged
access areas (C1, C2, C3, C5, C6, C7), the logging and monitoring areas (C14, C15), and the network
security, network segregation and web filtering areas (C9). The remaining Annex A families are
unaffected by it.

## OWASP Top 10 for LLM Applications 2025

Maintained by the OWASP GenAI Security Project ([genai.owasp.org/llm-top-10](https://genai.owasp.org/llm-top-10/)).
This is the most direct of the mappings, because these are technical risks a gateway can actually act
on. "Limits" below never means "prevents".

| Risk | Aegis controls | Coverage |
| --- | --- | --- |
| LLM01 Prompt injection | C12, C11, C13 | **Limits blast radius; does not prevent injection.** Injection screening over arguments and returned content is pattern matching and can be evaded. The real control is that a successfully injected agent still cannot call a tool it is not exposed, touch an object outside scope, reach a host off the allow list, or run a destructive action without a human. Design point, described in [SECURITY.md](SECURITY.md#what-aegis-does-not-promise). |
| LLM02 Sensitive information disclosure | C2, C5, C12, C14 | **Partial.** Exposure and scoping bound what is reachable; secret redaction and output bounds reduce what leaves; the journal records what was read. Aegis does not classify your data, so it cannot know that a permitted table is sensitive. That is what a C13 hook is for. |
| LLM03 Supply chain | C11, C9 | **Direct for the tool supply chain.** A proxied MCP server's tool definitions are pinned; a changed definition is quarantined rather than trusted, which is the rug-pull case. Model and library supply chain is out of scope. |
| LLM04 Data and model poisoning | C11, C12 | **Partial, one path only.** Poisoned tool and prompt definitions are quarantined at load, and returned content is screened. Training-data poisoning is out of scope. |
| LLM05 Improper output handling | C12, C13 | **Partial.** Output is size-bounded, redacted, binary refused unless allowed, malformed upstream results rejected, and an after-hook can rewrite or withhold. What your client does with a well-formed result is your client's problem. |
| LLM06 Excessive agency | C2, C3, C5, C6, C7, C8, C10 | **This is the risk Aegis was built for.** Capability is enumerated and classified, authority is scoped to typed objects, change requires a bound human approval, destruction requires a preview receipt, and volume is capped. |
| LLM07 System prompt leakage | C11, C12 | **Partial.** Prompts served through the gateway are pinned and screened, and content is redacted. A host-side system prompt Aegis never sees is not protected. |
| LLM08 Vector and embedding weaknesses | C5, C12, C13 | **Weak.** If retrieval happens behind a governed tool, scoping and content screening apply. Aegis has no embedding-level control and should not be presented as one. |
| LLM09 Misinformation | none | **Out of scope.** Aegis does not evaluate whether a result is true. |
| LLM10 Unbounded consumption | C10, C4, C12 | **Direct.** Per-caller and per-tool rates, weighted hourly budgets with per-class costs, circuit breakers, JSON size and depth limits, output bounds. |

## OWASP Top 10 for Agentic Applications 2026

Published by the OWASP GenAI Security Project's Agentic Security Initiative in December 2025
([OWASP GenAI](https://genai.owasp.org/)). Risk names as listed in the reference
([summary](https://www.promptfoo.dev/docs/red-team/owasp-agentic-ai/)). This list matters more than
the LLM list for anything MCP, because it is about agents acting.

| Risk | Aegis controls | Coverage |
| --- | --- | --- |
| ASI01 Agent goal hijack | C12, C2, C5, C6, C7 | **Containment, not prevention.** A hijacked goal is still executed through a governed, scoped, approval-gated tool surface. |
| ASI02 Tool misuse and exploitation | C3, C4, C5, C6, C10, C13 | **Direct.** Parameter pollution is met by schema validation and typed extraction, tool-chain abuse by per-tool authorisation and object scoping, automated abuse by rates and budgets. The `sql-readonly` hook exists precisely because a tool classified READ was able to carry a mutating statement, which is this risk exactly (see [DESIGN-REVIEW-AND-GAPS.md](DESIGN-REVIEW-AND-GAPS.md)). |
| ASI03 Identity and privilege abuse | C1, C2, C7 | **Direct.** The caller's identity, issuer, auth method and delegation chain are resolved by the host and carried into every decision and record; the agent never holds the backend credential, which is resolved only after a call is allowed. Separation of duties stops a delegated caller approving any action and stops the requester approving its own under a differently spelled name. |
| ASI04 Agentic supply chain | C11, C9, C13 | **Direct for MCP components.** Pinning, quarantine on change, egress allow-listing of the upstream server, hooks over its responses. |
| ASI05 Unexpected code execution | C3, C5, C6, C7, C8, C13 | **Partial.** A tool that submits a job or runs a statement is classified, scoped to a typed resource such as a specific jar, and gated. The `sql-readonly` hook constrains statement text. Aegis does not sandbox what the backend then runs. |
| ASI06 Memory and context poisoning | C11, C12 | **Weak.** Prompt and tool definitions are pinned and screened, returned content is screened. Aegis holds no agent memory and cannot govern one. |
| ASI07 Insecure inter-agent communication | C1, C13 | **Partial.** Delegation to an A2A agent through the `a2a` source is a governed tool call: classified, approved when `MUTATE` or `DESTRUCTIVE`, egress-checked and journaled, with the card endpoint pinned to the card's origin ([ADR-001](adr/ADR-001-A2A-AS-TOOL-SOURCE.md)). Agent-to-agent traffic that arrives as MCP gets identity and hooks. Aegis has no A2A front door and does not secure channels it is not on; card signatures are not verified. |
| ASI08 Cascading failures | C10, C16 | **Partial.** Breakers and budgets bound propagation, fail-closed avoids a failed control becoming an open door. Semantic error propagation between agents is not addressed. |
| ASI09 Human agent trust exploitation | C7, C14 | **Partial and worth stating carefully.** An approval request carries the tool, class, typed resources, the redacted arguments (bounded to 8 KiB) and, for a destructive call, the dry-run preview, so the approver reads the action rather than a digest. Each decision is journaled against a named approver before it takes effect, which makes rubber-stamping visible after the fact. It does not stop a human approving something they did not read, and redaction can hide a detail the approver needed. |
| ASI10 Rogue agents | C1, C2, C6, C10, C14 | **Partial.** A compromised agent is still an identified principal with a bounded surface and a complete record. Detecting that it has gone rogue is your monitoring job, from the signals in C15. |

## Other jurisdictions

Aegis is jurisdiction-neutral: it produces access-control and audit evidence, which is the common
technical substrate of almost every AI and data protection regime. This table is a pointer list, not
an analysis, and the status column is as at **2026-09-27**.

| Jurisdiction | Instrument | Status as at 2026-09-27 | Aegis controls that speak to it |
| --- | --- | --- | --- |
| United Kingdom | UK GDPR and Data Protection Act 2018; sector regulator AI guidance rather than a single AI act | In force; no cross-sector AI statute | C14 accountability evidence; C1, C2, C6, C9, C12 security of processing |
| Switzerland | revFADP | In force | Same as UK GDPR row |
| Canada | PIPEDA; the AIDA bill in C-27 died when Parliament was prorogued in January 2025 | No federal AI statute in force | C14, C1, C2, C6 |
| Brazil | LGPD; AI bill PL 2338 still in the legislative process | LGPD in force | C14, C1, C2, C6 |
| India | DPDP Act 2023 with DPDP Rules 2025 notified 13 November 2025, commencing in stages, with core obligations phased to 2027 ([tracker](https://www.recordinglaw.com/world-laws/world-data-privacy-laws/india-data-privacy-laws/dpdp-act-implementation-status/)) | Partially in force, staged | C12 minimisation, C14 accountability, C2 and C5 purpose-bounded access |
| South Korea | Framework Act on AI (AI Basic Act), effective 22 January 2026 with its enforcement decree ([Latham](https://www.lw.com/en/insights/ai-regulation-in-apac-diverging-approaches-across-the-region)) | In force | C14 records, C7 and C8 human oversight, C15 monitoring |
| Japan | Act on Promotion of R&D and Utilization of AI-Related Technologies, 2025; promotion-oriented rather than prescriptive | In force | C14, C15 |
| Vietnam | Law on Artificial Intelligence, effective 1 March 2026 | In force | C14, C7, C15 |
| China | Interim Measures for Generative AI Services; labelling measures for AI-generated content effective 1 September 2025 | In force | C14 records; labelling is out of scope |
| Singapore | Model AI Governance Framework and AI Verify; voluntary | Voluntary | C14, C15 as testable evidence |
| Australia | Privacy Act; voluntary AI Safety Standard | Voluntary for AI | C14, C1, C2 |

Two cautions. First, several of these regimes turn on transparency to individuals, consent, data
localisation and cross-border transfer. Aegis controls none of those. Second, an entry in this table
is a pointer for your counsel, not an assessment.

## What Aegis does not cover

Listed here so nobody has to discover it in an audit.

**Not a gateway concern at all.** Model selection, training data provenance, bias and fairness
testing, evaluation and red-teaming of the model, content provenance and watermarking, transparency
notices to end users, consent, data subject rights handling, cross-border transfer mechanisms, DPIAs
and conformity assessments, vendor contracts, staff training.

**Adjacent but not implemented.** These are the gaps in Aegis itself:

| Gap | Why it matters | Where it stands |
| --- | --- | --- |
| No audit retention, rotation or archival | Several regimes require logs kept for a defined period, and storage limitation requires that they are not kept forever. `audit.file` is a single append-only journal | Your log pipeline's job today. Tracked in [ROADMAP.md](ROADMAP.md) |
| No erasure or selective redaction of audit records | The hash chain makes deletion detectable by design, which is in tension with GDPR Art. 17 if personal data can reach a record | Mitigate by leaving `audit.includeArguments` off. No feature planned; the tension is inherent |
| No data classification | Aegis cannot know that a permitted object is sensitive, so it cannot enforce a rule expressed in terms of data sensitivity | This is the intended use of a C13 hook. Aegis ships the hook point, not the classifier |
| No PII detection | Same as above | Same as above |
| No signing of audit records with an external key | The chain proves internal consistency and detects edits, it does not prove authorship to a third party who does not trust the host | Tracked in [ROADMAP.md](ROADMAP.md) |
| No built-in report generation | An auditor gets a JSONL journal and Prometheus metrics, not a formatted control report | Out of scope for the gateway |
| No conformity assessment, certification or third-party audit of Aegis | You cannot inherit assurance from this project | True today and stated in every relevant document |

## The evidence pack

What you can actually hand a reviewer, and the command that produces it. These are real commands from
this tree, not illustrations.

**1. The configuration is the control statement.** One YAML file states every control in force:
exposure by group, classification per tool, scopes, policy rules, approval, dry-run requirements,
egress, limits, screening modes, hooks, catalog mode. It is reviewable without reading Java. Key
reference: [CONFIGURATION.md](CONFIGURATION.md).

**2. The journal, with its integrity proven offline.**

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
    --verify-audit /var/log/aegis/audit.jsonl
```

This recomputes the chain and reports the first record that fails, so a reviewer can confirm the
journal was not edited after the fact without trusting the running gateway.

**3. Decision metrics.** `GET /metrics` on the gateway's own server exposes decision counters labelled
by tool, source and outcome, so deny rates and screening hits are trendable.

**4. Traces, if you want per-call timing without identity.** Set `tracing.otlp.endpoint`. Spans carry
`aegis.request_id`, `aegis.kind`, `aegis.outcome`, `aegis.step`, `aegis.tool`, `aegis.tool_class`,
`aegis.source`, `aegis.reached_backend` and `mcp.method.name`, and nothing else. Join to the journal
on `aegis.request_id` when you need who and what, which keeps the tracing backend out of PII scope.
Read the wire-format caveat in [CONFIGURATION.md](CONFIGURATION.md) before claiming collector
compatibility.

**5. The controls are tested, and the tests are named after the behaviour.** Every row of the
inventory names a test. A reviewer can run one:

```bash
mvn -B test -Dtest=GovernanceEngineTest#destructiveFollowsValidateApprovePromoteAndNeverSendsControlValuesToBackend
```

**6. What was verified and what was not.** [VALIDATION.md](VALIDATION.md) is deliberately written as
evidence with dates, including which real-engine tests ran and which were skipped for want of a
broker or catalog. Read it before repeating any claim from this page.

## Keeping this document accurate

Rules for changing this file, because a mapping document decays faster than code:

1. **No new coverage claim without a control ID and a test.** If you cannot point at both, the row
   belongs in [What Aegis does not cover](#what-aegis-does-not-cover).
2. **Coverage words are load-bearing.** "Direct" means the control does the thing. "Partial" means it
   does part of it and the row says which part. "Supporting" means it produces evidence someone else
   uses. "Out of scope" means Aegis does not touch it. Never write "compliant", "certified",
   "satisfies" or "meets" about Aegis and a framework.
3. **Date every legal or standards statement** and link a public primary source where one exists. Do
   not quote clause identifiers from paywalled standards.
4. **Prefer the gap table to a stretched mapping.** The gaps are the reason this document is
   believable.
5. When a control changes, update the inventory row first; the framework tables reference IDs, so
   they follow.

Related reading: [HANDBOOK.md](HANDBOOK.md#threat-scenarios-and-why-the-gateway-boundary-matters) for the problem statement,
[THREAT-MODEL.md](THREAT-MODEL.md) for assets, STRIDE, and abuse scenarios,
[SECURITY.md](SECURITY.md) for property promises S1–S31 and explicit non-promises,
[evidence/SECURITY-VALIDATION.md](evidence/SECURITY-VALIDATION.md) for the latest control-suite run,
[GOVERNANCE.md](GOVERNANCE.md) for the operator control list and how the project itself is governed.
