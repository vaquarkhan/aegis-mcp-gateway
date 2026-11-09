# Aegis 2.0 against the OWASP MCP Top 10 and the OWASP LLM Top 10

Date: 2026-11-07. Tree: `aegis-mcp-gateway-20261104-2.0-review-ready` at base commit `2f7b944` plus the uncommitted fixes in this folder (Fixes 1 to 18, the complete-control core, and Parts K and L of `DETAILED-FINDINGS-AND-FIXES.md`).

This file answers three questions:

1. Are all 20 OWASP risks (10 MCP, 10 LLM) handled?
2. Does prompt injection or prompt redaction need another fix?
3. Which risks can a gateway close, and which need a control Aegis does not own?

Sources:

- [OWASP MCP Top 10](https://owasp.org/www-project-mcp-top-10/) (MCP01 to MCP10, still a beta list).
- [OWASP Top 10 for LLM Applications 2025](https://genai.owasp.org/llm-top-10/) (LLM01 to LLM10).

Risk names are paraphrased from those pages.

## Closure states

The same three states as the CVE census (Part J):

| State | Meaning |
| --- | --- |
| FULL_GATEWAY_PATH | Closed for every call that passes through Aegis, with default settings or one documented key. |
| FULL_MANAGED_PATH | Closed when `completeControl.enabled` is on (signed catalog, execution contracts, output policy, artifact admission, signed attestation, sandbox launcher). |
| EXTERNAL_REQUIRED | Needs a control Aegis does not own: the model, the MCP client, the identity provider, the endpoint, or the training pipeline. Aegis's contribution is still listed. |

No risk is marked "fully solved" in the absolute sense. Phrase screening, for example, catches known patterns and its normalised variants. It cannot recognise every novel wording. That is why the injection risks reach their closed state only on the managed path, where the model never receives free backend text.

## Summary

| Risk | State | Main Aegis control |
| --- | --- | --- |
| MCP01 Token mismanagement and secret exposure | FULL_GATEWAY_PATH | Caller token never forwarded; per-caller token exchange; output secret redaction; jti and revocation (durable store required, delegation chain covered) |
| MCP02 Privilege escalation via scope creep | FULL_GATEWAY_PATH | Tool classes, typed resource scope, deny-overrides policy, OAuth action scopes, bound approvals |
| MCP03 Tool poisoning | FULL_MANAGED_PATH | Definition screening, pins and quarantine, signed server catalog, output policy |
| MCP04 Supply chain and dependency tampering | FULL_MANAGED_PATH | Artifact sha256 admission, plugin code-source admission, signed deployment attestation |
| MCP05 Command injection and execution | FULL_MANAGED_PATH | Strict schemas, SQL read-only hook, execution contracts (no FREE_FORM), sandbox launcher |
| MCP06 Prompt injection via contextual payloads | FULL_MANAGED_PATH | Normalised screening of arguments, definitions, content and results; STRUCTURED_ALLOWLIST and HANDLE_ONLY |
| MCP07 Insufficient authentication and authorization | FULL_GATEWAY_PATH | Strict JWT, mTLS/SPIFFE, route groups, pre-auth limits and lockout, revocation, separate admin listener |
| MCP08 Lack of audit and telemetry | FULL_GATEWAY_PATH | Hash-chained journal with intent before execution, fail closed, checkpoints, SIEM sink SPI, OTLP |
| MCP09 Shadow MCP servers | EXTERNAL_REQUIRED | Signed server catalog and egress allow list govern what Aegis can reach; servers that bypass Aegis need endpoint and network controls |
| MCP10 Context injection and over-sharing | FULL_MANAGED_PATH | Output bounds, secret and PII redaction, HANDLE_ONLY, stateless engine, session owner binding |
| LLM01 Prompt injection | FULL_MANAGED_PATH (indirect) / EXTERNAL_REQUIRED (direct) | As MCP06; direct user-to-model injection is the model provider's and client's control |
| LLM02 Sensitive information disclosure | FULL_MANAGED_PATH | Redaction (secrets, PEM, entropy, PII), HANDLE_ONLY, arguments not journaled by default |
| LLM03 Supply chain | EXTERNAL_REQUIRED (model) / FULL_MANAGED_PATH (tools) | Tool and plugin supply chain as MCP04; model weights and model providers are outside a tool gateway |
| LLM04 Data and model poisoning | EXTERNAL_REQUIRED | Training data is outside the gateway; poisoned tool definitions and content are screened and pinned |
| LLM05 Improper output handling | FULL_GATEWAY_PATH | Model output (tool arguments) is schema-validated, typed, SQL-checked and egress-checked before any backend runs it |
| LLM06 Excessive agency | FULL_GATEWAY_PATH | READ/MUTATE/DESTRUCTIVE classes, write groups, independent approval, dry-run receipts, budgets, unlisted tools hidden |
| LLM07 System prompt leakage | EXTERNAL_REQUIRED | Aegis never holds the system prompt; secret redaction limits damage if a prompt embeds a credential |
| LLM08 Vector and embedding weaknesses | EXTERNAL_REQUIRED | Vector store access control belongs to the store; a vector-search tool can be put behind resource scope and output policy |
| LLM09 Misinformation | EXTERNAL_REQUIRED | A gateway cannot judge truth; Aegis gives provenance (result digest, journal, source id) for review |
| LLM10 Unbounded consumption | FULL_GATEWAY_PATH | Caller, tool and team rate limits, hourly budgets, per-address pre-auth limits, body bounds, deadlines, breakers |

Count: 13 of 20 reach FULL_GATEWAY_PATH or FULL_MANAGED_PATH (MCP09 and five LLM risks are EXTERNAL_REQUIRED; LLM01 and LLM03 are split and counted as external here). That number is the honest answer to "are all 20 fixed": the gateway-ownable part of every risk is implemented and tested, and seven risks have a part that no tool gateway can own.

## Correction made during this check

Earlier tables in `THREAT-MODEL.md` (section 4.1) and `DETAILED-FINDINGS-AND-FIXES.md` (row 31) labelled call floods as "OWASP MCP09". In the published list MCP09 is Shadow MCP Servers. Call floods belong to LLM10 Unbounded Consumption. Both tables were corrected.

## Detail per risk

Each row names the class and method that implement the control and the test that proves it at runtime. Test ids are the `THREATMODEL|<id>|...` lines printed by the suites in `aegis-*/src/test` and copied to `threat-model/tests`.

### MCP01 Token mismanagement and secret exposure

| Control | Where | Proof |
| --- | --- | --- |
| The caller's bearer token is never sent to a backend | `HttpToolExecutor.execute` builds headers from `ExecutionContext.credentials()` only | `GatewayHostTest.jwtAuthenticatedValidateApprovePromoteOverHttp` asserts `auth=null` at the backend |
| Per-caller delegated tokens instead of shared keys | `TokenExchangeCredentials` (RFC 8693), now over `PinnedHttp` | `TokenExchangeCredentialsTest` |
| Secrets come from env, file or Vault, never the config file | `GatewayFactory.secrets`, `VaultKvSecretProvider` | `VaultKvSecretProviderIT` (skipped without Vault), config tests |
| Secrets in results are redacted (AWS keys, bearer, JWT, PEM, optional entropy) | `OutputGuard.DEFAULT_REDACTIONS`, `redactPem`, `redactHighEntropy` | `tm14`, `CLOSE-04` |
| Stolen gateway tokens can be refused before expiry | `JwtVerifier.TokenIdPolicy`, `TokenRevocations`, `AdminApi` revocation routes | `GAP-L2a`, `GAP-L2b`, `GAP-L2c` |
| Approval tokens live at most 15 minutes and are single use | `ApprovalService.MAX_TOKEN_TTL` | `tm11` |

Residual (EXTERNAL_REQUIRED part): secrets held inside an upstream MCP server's own memory or logs, and the identity provider's token lifetime.

### MCP02 Privilege escalation via scope creep

Controls: `ToolClass` (READ, MUTATE, DESTRUCTIVE) with `writeGroups` and `destructiveGroups`; typed resource scope (`ScopeRule`, `ResourceRule`); deny-overrides `RulePolicy`; OAuth action scopes with step-up challenge (`AegisServer.stepUp`); approval tokens bound to caller, tool, argument digest and definition digest. Proof: `tm10`, `tm11`, `GatewayHostTest`.

### MCP03 Tool poisoning

Controls on the gateway path: definition screening (`InjectionScreen`, `screening.toolDefinitions` default ENFORCE); upstream name validation (Fix 6); trust-on-first-use pins and quarantine with digest-checked acceptance (`ToolCatalog`, `AdminApi` catalog accept); signed server catalog and break-glass containment (`SignedServerCatalog`, `BreakGlassContainment`). Result poisoning: `screening.results` is AUDIT by default and ENFORCE by default in complete control (`GAP-L4b`). On the managed path `OutputPolicy` STRUCTURED_ALLOWLIST rebuilds text from a validated object and HANDLE_ONLY returns only a digest handle, so a poisoned result has no free text to carry instructions (`CompleteControlEngineTest`, `CC-K2`).

### MCP04 Software supply chain and dependency tampering

Controls: `ArtifactAdmission.verify` (sha256, no symlinks, regular files); plugin providers are instantiated only when their actual code-source JAR is an admitted artifact (`GatewayFactory.codeSourceAdmitted`), now for every plugin type (tool source, hook, state store, secret provider, audit sink) through `completeControl.artifacts` (`GAP-L4b`); `deploymentAttestation` now carries an Ed25519 signature by a host-anchored admission key (`AEGIS_REQUIRE_ATTESTATION_KEY`) over source id, workload digest, evidence digest, verifier and expiry (`CC-K1`).

Residual: building, scanning and signing the upstream server image (SBOM, CVE receipt, Sigstore) is the admission system's job. Aegis verifies its signed statement; it does not rebuild or scan the image.

### MCP05 Command injection and execution

Controls: input schema validation with `strictProperties` and operator `inputSchema` overlays (Fix 15); `SqlReadOnlyHook` with function deny list (Fix 16); HTTP path placeholders refuse separators and traversal (`HttpToolExecutor.segment`); in complete control every tool needs an `ExecutionContract` that is not UNKNOWN or FREE_FORM, and stdio servers must start through a sandbox prefix with admitted command artifacts (`ProcessLauncher`, `requireCommandArtifacts`). Proof: `tm13`, `CLOSE-01c`, `ThreatModelCveSqlTest`, `CompleteControlStdioTest`.

Residual: a backend that legitimately accepts a free-form command string must be replaced by a typed adapter; the gateway cannot make a shell tool safe.

### MCP06 Prompt injection via contextual payloads (and LLM01, indirect)

What is screened, and the default mode:

| Surface | Key | Default | Complete control |
| --- | --- | --- | --- |
| Tool arguments | `screening.mode` | ENFORCE | ENFORCE |
| Tool definitions | `screening.toolDefinitions` | ENFORCE | ENFORCE |
| Resources and prompts | `screening.content` | ENFORCE | ENFORCE |
| Tool results | `screening.results` | ENFORCE (sixth pass; was AUDIT) | ENFORCE, OFF refused |

Screening normalises before matching (zero-width characters, case, whitespace, nested JSON, base64, and since the sixth pass Unicode confusables such as Cyrillic or Greek look-alikes, plus German, French, Spanish, Italian and Portuguese phrase packs) (`InjectionScreen`, Fix 9, O-1 B). Proof: `tm12`, `CLOSE-03b`, `SCAN-O1`.

The structural control is `OutputPolicy`. With STRUCTURED_ALLOWLIST or HANDLE_ONLY the model never receives free backend text, so a new phrasing cannot help. Writes also still need an independent human approval, so a successful injection cannot make a destructive call alone (`tm11`).

Is another fix needed? No code change is required for the gateway path. The phrase list is a mitigation for known wording; the managed path is the closure. Direct injection typed by the user into the model is LLM01's direct case and belongs to the model provider and the client.

### "Prompt redaction": what Aegis redacts and what it never sees

Aegis sits between the MCP client and the tools. It never receives the chat prompt or the system prompt. What it handles, and whether it is redacted:

| Data | Redacted | Notes |
| --- | --- | --- |
| Tool results to the model | Yes | Secrets, PEM, optional entropy, optional PII (`output.redactPii`, default on in complete control) |
| Resource reads and prompt templates (`resources/read`, `prompts/get`) | Yes | Same `OutputGuard`, via `ContentGovernor` |
| Retained HANDLE_ONLY results | Yes | Stored only after redaction (`CC-K2`) |
| Tool arguments to the backend | No, screened instead | Redacting arguments would change what the backend executes. Arguments are schema-checked and injection-screened. |
| Audit journal | Arguments omitted by default | `audit.includeArguments: false`; only the argument digest is recorded |

So no prompt-redaction fix is needed. The one gap found here, missing default PII patterns, was fixed in this pass (`OutputGuard.PII_REDACTIONS`, `GAP-L4c`).

### MCP07 Insufficient authentication and authorization

Controls: JWT with explicit algorithm allow list, issuer, audience, expiry, `crit` refusal and HTTPS-only JWKS (`JwtVerifier`, `tm18`); hashed static tokens; mutual TLS and SPIFFE; no anonymous mode; plaintext refused on non-loopback listeners. New in this pass:

- Per-address pre-authentication budget and failed-authentication lockout before any signature check (`ClientAddressLimiter`, `GAP-L3a`).
- `jti` requirement and gateway-side revocation (`GAP-L2a`, `GAP-L2b`, `GAP-L2c`).
- Separate admin listener; complete control requires `adminGroups`, `adminListen`, `requireJti` and `requireRevocation` (`GAP-L4a`).

### MCP08 Lack of audit and telemetry

Controls: `HashChainFileAuditSink` with intent recorded before execution and fail-closed behaviour (`tm15`); checkpoint export; administrative events journaled (catalog accept, revocation, result release); OTLP spans without identities or arguments; Prometheus metrics. New: `AuditSinkFactory` SPI (`audit.sinks`) sends every record to a SIEM after the journal and fails the call closed if a sink fails (`GAP-L4d`).

Residual: anchoring checkpoints in write-once storage is the operator's storage choice.

### MCP09 Shadow MCP servers

A gateway governs only traffic that reaches it. Inside that boundary:

- The signed server catalog decides which servers start.
- `unlistedTools: HIDE` hides tools that were not reviewed.
- The egress allow list, now pinned at connect time (`PinnedHttp`, `GAP-L1a` to `GAP-L1e`), stops the gateway from reaching anything unlisted.

A developer who runs an MCP server on a laptop and connects a client to it directly never touches Aegis. Detecting that needs endpoint management, network policy and client configuration control. This is EXTERNAL_REQUIRED by nature.

### MCP10 Context injection and over-sharing

Controls:

- 1 MiB output bound with `isError` preserved (`tm14`).
- Redaction, including PII.
- HANDLE_ONLY, so withheld data never enters the context, and release is take-once and never to the producer (`CC-K2`).
- A stateless engine keyed by caller identity, not by shared session.
- Session owner binding on legacy sessions.
- Resource scope per group.

Residual: how a client shares its own context window across users or agents is a client design decision.

### LLM02 Sensitive information disclosure

Same controls as MCP01 and MCP10. Additional: binary parts refused by default (`output.allowBinary: false`); arguments not journaled by default.

### LLM03 Supply chain

Tool, plugin and server supply chain: see MCP04 (FULL_MANAGED_PATH). Model supply chain (weights, model hubs, fine-tuning adapters) is EXTERNAL_REQUIRED for a tool gateway.

### LLM04 Data and model poisoning

Training and fine-tuning data are EXTERNAL_REQUIRED. At inference time the poisoning paths that pass through MCP (tool definitions, resources, prompts, results) are screened and pinned as in MCP03.

### LLM05 Improper output handling

In an agent, the model's output becomes tool arguments. Aegis treats those arguments as untrusted input before any backend runs them:

- Schema and type validation.
- Undeclared keys refused.
- SQL read-only checks.
- Path segment encoding.
- Every embedded URL egress-checked, now pinned at connect time.
- Approvals bound to the exact argument digest.

Proof: `tm13`, `GAP-L1e`.

### LLM06 Excessive agency

This is Aegis's core purpose. Controls:

- Tool classes.
- Write and destructive groups.
- Independent approval with separation of duties (the requester cannot approve).
- Dry-run receipts for destructive actions (`VrpService`).
- Hourly budgets weighted by class.
- `unlistedTools: HIDE`.
- Complete-control execution contracts.

Proof: `tm10`, `tm11`, `GatewayHostTest`.

### LLM07 System prompt leakage

EXTERNAL_REQUIRED. Aegis does not hold system prompts. If a system prompt embeds a credential and a tool echoes it back, output redaction applies.

### LLM08 Vector and embedding weaknesses

EXTERNAL_REQUIRED. Access control and tenant isolation inside a vector store belong to the store. When retrieval is exposed as an MCP tool, Aegis can limit which groups call it, scope the collection as a typed resource and apply an output policy.

### LLM09 Misinformation

EXTERNAL_REQUIRED. A gateway cannot decide whether an answer is true. For responsible-AI review Aegis records which source and tool produced each result, the result digest and who approved the action, so a human can trace a claim back to its source.

### LLM10 Unbounded consumption

Controls:

- Per-caller and per-tool minute windows.
- Team quotas (`rateLimits.perGroupPerMinute`, `GAP-L3b`).
- Weighted hourly budget.
- Pre-authentication per-address budget and lockout (`GAP-L3a`).
- Request, response and header bounds.
- Bounded worker pool (`OPEN-02`).
- Total deadlines on every outbound call (`BoundedHttp`, `PinnedHttp`, `GAP-L1c`).
- Circuit breakers.
- Stdio timeout that kills the process tree.

## What this check changed

Two documentation corrections: the MCP09 mislabel was fixed in both tables. No new code was needed beyond the gaps already fixed in Part L (L1 to L4) and Part K (K1, K2). The external parts above are listed again in `THREAT-MODEL.md` section 8 so they stay visible to operators.
