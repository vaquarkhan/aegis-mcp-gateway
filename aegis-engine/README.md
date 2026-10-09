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
# aegis-engine

The governance core. Every MCP tool call that Aegis allows or denies is decided here, in a fixed
fail-closed order. The module is JDK-only at runtime: no third-party libraries.

## What it does

An agent asks Aegis to call a tool, read a resource or get a prompt. This module decides whether
that request may run. If it may, the engine resolves backend credentials, runs the source's
executor and cleans the result before the agent sees it. Nothing runs unless its intent was
journaled first. When a check cannot run or its answer is unclear, the request is denied.

## How it works end to end

The MCP layer passes a `ToolCall` and a host-established `CallerIdentity` to
`GovernanceEngine.call()`, which assigns a request id and trace context and runs the steps below in
order; the first denial stops the chain. Step numbers come from `DenyCodes`. Approval keeps number 4
but runs after steps 5 to 8, so a human only sees calls automation would allow
([ARCHITECTURE.md section 4](../docs/ARCHITECTURE.md#4-the-tool-chain-and-its-ordering)).

```text
  step 1   exposure             NOT_EXPOSED, CATALOG_INTEGRITY, READONLY_CALLER
  step 2   input and scope      INVALID_INPUT, SCOPE_DENIED
  step 3   policy               POLICY_DENIED
  step 5   egress               EGRESS_DENIED
  step 6   rate and budget      RATE_LIMITED, BUDGET_EXCEEDED
  step 7   circuit breaker      BREAKER_OPEN
  step 8   screening, hooks     PROMPT_INJECTION, POLICY_DENIED, INVALID_INPUT
  step 4   approval (verify)    APPROVAL_REQUIRED
  step 9   dry-run receipt      VRP_FAILED
  commit   consume nonces, journal ALLOW_INTENT       AUDIT_UNAVAILABLE
  step 10  execute, output guard  BACKEND_ERROR, TIMEOUT, POLICY_DENIED (binary)
  then     journal EXECUTION_RESULT, notify listeners, return CallOutcome
```

1. Exposure. `ToolCatalog.lookup`: unknown is `NOT_EXPOSED`, quarantined `CATALOG_INTEGRITY`, and
   an `allowedGroups` list without the caller `NOT_EXPOSED`. MUTATE needs a write group, DESTRUCTIVE
   a destructive group, else `READONLY_CALLER`. `listTools` uses the same check.
2. Input and scope. `ControlTokens.split` moves the approval token, dry-run flag and receipt out of
   `params._meta` (prefix `io.github.vaquarkhan.aegis/`) or undeclared 0.1 arguments. Bad control
   values, a `SchemaValidator` failure or a dry run of a READ tool is `INVALID_INPUT`. `ResourceRule`s
   extract typed resources by JSON pointer (missing or unsafe: `SCOPE_DENIED`). The engine builds the
   `Action` (tool, class, source, resources, argument digest, action scopes) and its
   `Action.binding(gatewayName, definitionDigest)`. A missing `actionScopes` grant is `SCOPE_DENIED`,
   and so, with scopes configured, is a resource outside the caller's `ScopeRule`s or a
   resource-scoped call that names none.
3. Policy. `PolicyDecisionPoint.decide` sees the full action. Deny, null or an exception is
   `POLICY_DENIED`. The bundled `RulePolicy` is deny-overrides with default deny.
4. Egress. Targets are `ToolExecutor.egressTargets` (the preview executor's in TOOL dry-run mode)
   plus, when argument URL checking is on, every URL `EgressGuard.urlsIn` finds in the arguments and
   MRTR `inputResponses`. Each target and every DNS answer must pass `EgressGuard.check`. A refusal
   or an exception while listing targets is `EGRESS_DENIED`.
5. Rate and budget. `RateLimiter.admit` charges atomic `StateStore.tryAcquire` windows: per caller
   and per caller per tool each minute (`RATE_LIMITED`), and an hourly budget where write classes
   cost more (`BUDGET_EXCEEDED`). A refused call is not charged.
6. Breaker. `CircuitBreakers.acquire(sourceId)` refuses while open (`BREAKER_OPEN`); half-open lets
   one probe through. A permit that never reached the backend is released on denial.
7. Screening and hooks. `InjectionScreen` checks every nested string and key of the arguments and
   `inputResponses` (ENFORCE: `PROMPT_INJECTION`; AUDIT: `screeningMatch`). `HookRunner.before` then
   runs matching `HookBinding`s in order. ENFORCE hooks deny with `POLICY_DENIED`, `INVALID_INPUT` or
   `PROMPT_INJECTION` (other codes map to `POLICY_DENIED`); AUDIT hooks only add notes to `hookAudit`.
8. Approval. For classes in `approvalRequiredFor` (default MUTATE and DESTRUCTIVE), not on a dry run.
   With no token the call is `APPROVAL_REQUIRED`, carrying the `approvalRequestId` that
   `ApprovalService.request` opened when approval is configured. With a token,
   `ApprovalService.verify` checks HMAC, expiry, caller, tool, argument digest, binding and an
   unused nonce, without consuming it.
9. Receipt. With `vrpEnabled` and a DESTRUCTIVE tool, an unconfigured `VrpService` is `VRP_FAILED`;
   a real call needs an unexpired, unused receipt bound to the same caller, tool, digest and binding.
10. Commit. Approval and receipt nonces are consumed with atomic `putIfAbsent` (losing a race is
    `APPROVAL_REQUIRED` or `VRP_FAILED`), then `AuditSink.append` writes `ALLOW_INTENT`. If that
    throws, the call is `AUDIT_UNAVAILABLE` before credentials or the backend are touched, and the
    denial data says `freshApprovalRequired` or `freshDryRunReceiptRequired`.
11. Execute. A GATEWAY dry run returns a preview here. Otherwise `CredentialProvider.credentialsFor`
    runs now, never earlier (failure: `BACKEND_ERROR`), and the executor gets an `ExecutionContext`.
    A timeout is `TIMEOUT`, any other failure or a null result `BACKEND_ERROR`; only availability
    failures count toward the breaker. Failure text passes `OutputGuard.redactAll`.
12. Output. `HookRunner.afterTool` lets ENFORCE hooks rewrite the result, or withhold it as an
    `isError: true` result. `OutputGuard.apply` redacts every string at any depth, truncates past
    `maxBytes`, and withholds a result with binary parts unless `output.allowBinary` (`POLICY_DENIED`).
    `EXECUTION_RESULT` is journaled, listeners are notified, and a `CallOutcome` is returned.

Validate, approve, promote. A DESTRUCTIVE call is first sent with `dryRun` true. All controls
except the approval and receipt checks run, `ALLOW_INTENT` is journaled, and the tool's `DryRunSpec`
picks the preview (`GATEWAY`, `ARGUMENT` or `TOOL`). A non-error preview returns a `dryRunReceipt`
(`VrpService.issue`, when `vrpEnabled`) and, when approval is configured, opens a request with the
redacted preview as evidence. An approver in an approver group, not delegated and not the requester, subject or anyone
in the delegation chain (NFKC and case folded), calls `ApprovalService.decide`, which is journaled
first. The requester resends the identical call with token and receipt. Because the binding covers
the gateway name and pinned definition digest, a token or receipt from another gateway or an older
definition is refused. `SignedTokens` uses one key per token type (`aat1`, `vrp1`).

Catalog pinning. `ToolCatalog.load()` and `refresh()` call `ToolSource.discover()` and bind each
tool into a `GovernedTool` through `ToolBinder`. `CatalogIntegrity.verify` compares the definition
digest with its pin (`TOFU` or `REQUIRE_PINS`). A changed digest, an unpinned tool under
`REQUIRE_PINS`, a late tool without the opt-in, or a poisoned description (screen in ENFORCE) is
`QUARANTINED`: hidden and denied `CATALOG_INTEGRITY` until `ToolCatalog.accept(name, digest)`
accepts the exact reviewed digest. Duplicate exposed names abort startup.

Prompts, resources and completion. `ContentGovernor` runs `resources/read`, `prompts/get` and
`completion/complete` through the same steps and codes as a READ `Action` (tool: the MCP method;
resource: `mcp:resource:<uri>` or `mcp:prompt:<name>`), with no approval or receipt step. A URI must
route to exactly one enabled source (else `NOT_EXPOSED`); prompts are pinned under `prompt:<name>`.
The result is rebuilt from spec fields, `HookRunner.afterContent` may withhold it, an oversized one
is `BACKEND_ERROR` (not truncated), and the content screen can deny it with `PROMPT_INJECTION`
([ARCHITECTURE.md section 7](../docs/ARCHITECTURE.md#7-resources-prompts-and-completion)).

## Main types

| Type | Responsibility |
| --- | --- |
| `GovernanceEngine`, `GovernanceSettings`, `CallOutcome` | The chain; secure-default settings; result of one call |
| `model.Action`, `model.ControlTokens` | Canonical action and binding; control values split from arguments |
| `model.ToolCall`, `model.CallerIdentity`, `model.ToolResult` | Call as received; caller without credentials; result body |
| `model.ToolClass`, `model.Decision`, `model.DenyCodes` | READ, MUTATE, DESTRUCTIVE; one outcome; stable codes and steps |
| `catalog.ToolCatalog`, `catalog.CatalogIntegrity`, `catalog.GovernedTool` | Exposed tools, pins, tool plus operator settings |
| `catalog.ResourceRule`, `catalog.DryRunSpec` | Typed resources by JSON pointer; preview mode |
| `catalog.SignedCatalogManifest`, `catalog.SignedServerCatalog`, `catalog.BreakGlassContainment` | Ed25519-signed pins, server register, containment |
| `catalog.SignedSerial` | Rollback protection: the highest accepted signed `serial` per document kind, kept in the `StateStore` |
| `control.SchemaValidator`, `control.ScopeRule`, `control.RulePolicy` | Steps 2 and 3 |
| `control.EgressGuard`, `control.RateLimiter`, `control.CircuitBreakers` | Steps 5, 6 and 7 |
| `control.InjectionScreen`, `control.HookRunner`, `control.HookBinding` | Step 8 screening and hooks (ENFORCE, AUDIT, OFF) |
| `control.OutputGuard` | Output redaction, size bound and binary refusal |
| `approval.ApprovalService`, `approval.VrpService`, `approval.SignedTokens` | Steps 4 and 9; HMAC-SHA256 tokens |
| `audit.HashChainFileAuditSink`, `audit.AuditChainVerifier` | Hash-chained JSONL journal and its verifier |
| `audit.AuditCheckpointPolicy`, `audit.AuditCheckpointVerifier` | Head export to separate storage, and its check |
| `content.ContentGovernor`, `content.ContentSettings` | Resources, prompts and completion |
| `state.InMemoryStateStore`, `audit.InMemoryAuditSink` | Bounded single-replica state; test sink that can be told to fail |
| `observe.DecisionMetrics`, `json.Json`, `json.JsonLimits` | Prometheus text metrics; strict bounded JSON |

## Extension points (SPI)

Other modules and hosts plug in here. Sources contribute capability only (list tools, run them);
class, resources, groups, scopes and dry-run mode come from operator configuration.

| Type | Purpose |
| --- | --- |
| `spi.ToolSource` | `discover()` returns tools, each a `ToolDescriptor` plus a `ToolExecutor`; `configuredEndpoints()` (default none) reports a plugin's configured network endpoints so their hosts join egress |
| `spi.ToolExecutor` | `egressTargets` for step 5; `execute` after commit; reports timeout and availability |
| `spi.ContentSource` | Resources, prompts and completion of an existing MCP server |
| `spi.PolicyDecisionPoint` | Allow or deny over the full `Action` |
| `spi.GovernanceHook` | Before and after controls for all four governed operations |
| `spi.StateStore` | Atomic nonces, approval requests and rate windows |
| `spi.AuditSink` | Append-only journal; `append` returns only once the record is durable |
| `spi.CredentialProvider` | Backend headers for a source, resolved only after commit |
| `spi.DecisionListener` | Read-only observer of final outcomes |
| `spi.AddressResolver` | DNS seam for the egress guard |
| `catalog.ToolBinder` | Joins an upstream tool with operator settings; may only raise the class |
| `spi.SubjectToken` | Verified inbound token for one request, for token exchange; never in `CallerIdentity` |
| `spi.ScopeChallenge` | Per-request collector of the action scopes a refused call needed, for a front door's step-up challenge |

## Failure behaviour

- `ALLOW_INTENT` cannot be journaled: `AUDIT_UNAVAILABLE`, no credentials, no backend call. Other
  audit records are best effort (logged at ERROR). If `journalAdministrative` or the approval
  decision journal fails, that change is refused.
- Policy throws or returns null: `POLICY_DENIED`. Egress targets unknown: `EGRESS_DENIED`. Any
  other runtime exception in the chain is caught by `call()`: `POLICY_DENIED` at step 3.
- No approval key or approver groups: `APPROVAL_REQUIRED`. No VRP key: `VRP_FAILED`.
- An ENFORCE hook that throws or returns null denies at step 8, or withholds output after the call.
  AUDIT hooks never deny or rewrite; OFF hooks never run.
- State: a full `InMemoryStateStore` refuses new keys. A refused rate window is `RATE_LIMITED` or
  `BUDGET_EXCEEDED`, a refused receipt consume `VRP_FAILED`; a full store at approval consume, or
  any `StateStore` exception, ends as `POLICY_DENIED`.
- Credentials fail: `BACKEND_ERROR` before the backend. Backend fails: `TIMEOUT` or `BACKEND_ERROR`
  with a redacted message. A listener that throws is logged and ignored.
- Discovery failure stops startup when `failOnSourceError` is set; on refresh the source keeps its
  previous entries. `HashChainFileAuditSink` will not open on a torn last record, and a checkpoint
  that cannot be written refuses the record (`AUDIT_UNAVAILABLE`).

## Tests

- `GovernanceEngineTest`: the full chain, credentials only after audit, validate-approve-promote,
  audit failure, redaction, quarantine, duplicate names.
- `SeparationOfDutiesTest`: self-approval under another spelling, delegated approvers, replay across
  gateways or definition changes, approver evidence, journaled decisions.
- `HooksTest`, `ContentHooksTest`, `ContentGovernorTest`: hook modes, order and fail-closed errors;
  the content chain, result shapes, URI routing, prompt pinning, completion.
- `ControlsTest`, `OutputGuardTest`, `SchemaRefTest`: egress DNS classes, atomic rate windows,
  the breaker probe slot, bounded globs, binary withholding, local-only `$ref`.
- `HashChainFileAuditSinkTest`, `AuditCheckpointTest`, `SignedCatalogManifestTest`,
  `SignedServerCatalogTest`, `SignedSerialTest`: tamper, torn tail, one writer, checkpoints; Ed25519,
  break-glass and rollback protection.
- `JsonTest`, `JsonPointerExpressionTest`, `ObservabilityTest`: strict JSON and pointers, trace
  propagation, listeners that cannot break enforcement.

## What it owns

- `GovernanceEngine.call()` and the control chain (exposure, input/scope, policy, egress, rate,
  breaker, screening/hooks, approval, dry-run receipt, audit intent, execute, output guard).
- Tool catalog pinning, signed approval and dry-run tokens, hash-chained audit journal.
- SPI used by sources, hooks, policy engines, and state stores.
- Bounded JSON parsing and in-memory state.

## Developer instructions

```powershell
mvn -B -pl aegis-engine -am test
```

Rules for changes:

1. Never reorder or skip controls to make a test pass; fail closed on ambiguity.
2. Reuse deny codes from [`docs/DENY-CODES.md`](../docs/DENY-CODES.md); do not invent wire codes
   quietly.
3. Unclassified tools are `DESTRUCTIVE`. Assert class + approval/receipt behavior for new
   destructive paths.
4. Keep this module free of third-party runtime dependencies.
5. Update [`docs/guide/modules.html`](../docs/guide/modules.html#aegis-engine) and security docs when
   a control promise changes.

## Further reading

- [Security properties S1 to S31](../docs/SECURITY.md)
- [Threat model](../docs/THREAT-MODEL.md)
- [Module reference](../docs/guide/modules.html#aegis-engine)
- [AGENTS.md](../AGENTS.md)
