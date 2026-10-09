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
# Security control validation (2026-10-05)

This record documents the focused test selection and a reported historical result for the security
properties in [SECURITY.md](../SECURITY.md) and the abuse scenarios in
[THREAT-MODEL.md](../THREAT-MODEL.md). It is not immutable proof for the current review candidate because
it does not retain a commit/tree identifier or raw Maven/Surefire output. A future promotion must follow
the candidate-bound evidence rules in [CLAIMS-MATRIX.md](CLAIMS-MATRIX.md).

Companion claim row: [CLAIMS-MATRIX.md](CLAIMS-MATRIX.md) **G03**.

## 1. Validation goals

1. Re-execute the deterministic tests that anchor properties **S1-S31** (and closely related host /
   Knox / SPIFFE / secrets tests).
2. Show the mapping from each test class to the threat-model scenarios (TM01-TM10) and OWASP MCP items.
3. Record environment, exact command, module totals, and failures found during the run.
4. State what was intentionally out of scope for this suite.

## 2. Environment

| Part | Value |
| --- | --- |
| Date | 2026-10-05 |
| OS | Windows 11 x64 |
| Branch | Reported working branch: `aegis-2.0-feature-brach-for-review-10052026`; no commit or tree identifier was retained |
| JDK used by Maven surefire | Oracle JDK 17.0.7 (`mvn -version` java.home) |
| JDK on PATH | Temurin 21.0.11 LTS (not used by this Maven invocation) |
| Maven | 3.9.0 |
| External services in this suite | None (no Docker, Keycloak, Vault, or Postgres) |
| Network | Default build; tests use loopback servers started by JUnit where needed |

## 3. Method

### 3.1 Selection

We selected JUnit 5 classes named in the "Tested in" column of
[SECURITY.md](../SECURITY.md#security-properties), plus host/Knox/SPIFFE classes required to exercise
perimeter and confused-deputy proofs:

- Engine: governance, SoD, controls, output, audit, hooks, schema/json, observability, content
- MCP: handler, admin API, existing-MCP proxy, content governance
- Config: gateway factory, remote hooks, token exchange, secrets (file path)
- Security module: JWT, perimeter, OAuth metadata, SPIFFE identity
- Gateway: end-to-end own-host HTTP, signed manifest flow, SPIFFE mTLS
- Knox extension: ACL route-scope (body-blind Knox vs Aegis deny), extension wiring
- Guardrails: project-governance hook

### 3.2 Reported historical execution

The exact reported 2026-10-05 selection was the following one-line command. It does not include
`ScopeEnforcementTest`, which was added to this evidence catalogue after that run.

```text
mvn -B -pl aegis-engine,aegis-mcp-security,aegis-gateway,aegis-knox3-extension,aegis-mcp,aegis-config -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=GovernanceEngineTest,SeparationOfDutiesTest,ControlsTest,OutputGuardTest,HashChainFileAuditSinkTest,JwtVerifierTest,OwnServerPerimeterTest,GatewayHostTest,AdminApiTest,HooksTest,ContentGovernanceTest,ContentGovernorTest,RemoteHookTest,SchemaRefTest,JsonPointerExpressionTest,OwnServerSecuritySettingsTest,ProtectedResourceMetadataTest,SpiffeIdentityTest,SpiffeMutualTlsTest,TokenExchangeCredentialsTest,SignedCatalogManifestTest,SignedCatalogManifestFlowTest,AuditCheckpointTest,KnoxAclRouteScopeTest,KnoxExtensionTest,ProjectGovernanceGuardrailTest,ExistingMcpServerProxyTest,McpServerHandlerTest,JsonTest,ObservabilityTest,ContentHooksTest,GatewayFactoryTest,SecretProvidersTest
```

`-Dsurefire.failIfNoSpecifiedTests=false` was required because `-am` pulled intermediate modules that
did not contain any of the named classes.

### 3.3 Pass criteria

- Every selected test method passes (Failures=0, Errors=0).
- Skipped=0 for this selection (no optional IT gates in the list).
- Reactor `BUILD SUCCESS`.

### 3.4 Result

**Recorded result, not candidate attestation.**

| Module | Selected tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| `aegis-engine` | 115 | 0 | 0 | 0 |
| `aegis-mcp` | 38 | 0 | 0 | 0 |
| `aegis-config` | 27 | 0 | 0 | 0 |
| `aegis-mcp-guardrails` | 2 | 0 | 0 | 0 |
| `aegis-mcp-security` | 23 | 0 | 0 | 0 |
| `aegis-gateway` | 17 | 0 | 0 | 0 |
| `aegis-knox3-extension` | 5 | 0 | 0 | 0 |
| **Total** | **227** | **0** | **0** | **0** |

Wall clock: about 4 minutes 28 seconds. Reported outcome: **BUILD SUCCESS**. This reported total is
`recorded-unattested` until a candidate-bound run retains the commit/tree identity and raw artifacts.

## 4. What each test class proves

This section is the detailed "how we proved it" catalogue. Assertions are summarized from the test
names and the S-property contracts they anchor; see the Java sources under each module for exact
expects.

### 4.1 Identity, tokens, and perimeter

| Test class | Module | How it is performed | What it proves | S / TM / OWASP |
| --- | --- | --- | --- | --- |
| `JwtVerifierTest` | mcp-security | Builds tokens with wrong `iss`, `aud`, expiry, skew, `alg: none`, algorithm confusion | Forged or malformed JWTs are refused fail-closed | S2; Spoofing; MCP07 |
| `OwnServerPerimeterTest` | mcp-security | Route admission and CORS settings unit checks | Perimeter config is strict before dispatch | S21 |
| `OwnServerSecuritySettingsTest` | mcp-security | OAuth metadata vs audience binding | Metadata cannot drift from `aud` without explicit escape | S24 |
| `ProtectedResourceMetadataTest` | mcp-security + gateway | Serves metadata; 401 points at it; scope challenge | Wire-level OAuth resource metadata behaviour | S24, S26 |
| `SpiffeIdentityTest` | mcp-security | Maps X.509-SVID rules (domains, SANs) | Unmapped or wrong trust domain denied | Spoofing residual path |
| `SpiffeMutualTlsTest` | gateway | Real TLS listener with `clientAuth: require` | Handshake fails without trusted cert; mapped file-based SVID becomes caller | H12, not SPIRE Workload API evidence |
| `GatewayHostTest` | gateway | Starts real `AegisServer` on loopback; HS256 caller | Auth required; forged/`alg:none` rejected; backend `auth=null`; metrics group gate; CORS cannot widen; oversized body rejected; self-approval 403; derby-local discovery | S1, S2, S3, S17, S21, S23; TM03 |
| `KnoxExtensionTest` | knox3 | Knox extension wiring with Aegis engine | Knox Subject path still requires Aegis governance | S1, S27 story |
| `KnoxAclRouteScopeTest` | knox3 | Real Knox 3.0.0 `AclsAuthz` filter | Knox admits by user/group/IP and is body-blind; Aegis still denies destructive body | TM03 confused deputy |

### 4.2 Governance engine, approval, SoD, receipts

| Test class | Module | How it is performed | What it proves | S / TM / OWASP |
| --- | --- | --- | --- | --- |
| `GovernanceEngineTest` | engine | In-memory engine with fixture tools | Control order; default deny; approval/receipt binding; intent before execute; credential after allow; pin change; rates/breakers | S3-S7, S9; TM01-TM03; MCP01/02/03/08 |
| `SeparationOfDutiesTest` | engine | Requester vs approver under NFKC/case; delegation | Self-approval refused; delegated cannot approve; journal-first decisions | S5, S6; TM06; MCP02 |
| `ControlsTest` | engine | Egress CIDRs, nested URLs, screening phrases, sql-readonly shapes, output bounds | SSRF deny; injection screen; READ mutate denied; redaction | S4, S8, S11, S12; TM01, TM04, TM05; MCP05/06 |
| `OutputGuardTest` | engine | Secret-shaped strings and size limits | Redaction and bounds before model delivery | S11; MCP01/MCP10 |
| `HooksTest` / `ContentHooksTest` | engine | Hook throws, empty, timeout, AUDIT mode | Fail-closed; cannot invent deny codes; AUDIT cannot alter | S19; MCP07 containment |
| `ObservabilityTest` | engine | Failing listener / metrics shape | Observability cannot change decisions or leak args | S18; MCP08 |
| `SchemaRefTest` | engine | Remote `$ref`, cycles, local refs | Remote ref never fetched; deny | S30; Tampering |
| `JsonPointerExpressionTest` | engine | Pointer filters without functions | No expression-language filters | S30 |
| `JsonTest` | engine | Duplicate keys, depth/size | Parse bounds | S10 |
| `ProjectGovernanceGuardrailTest` | guardrails | Protected resources + evidence refs | High-impact evidence requirements | S25 |

### 4.3 Catalog, sources, MCP proxy

| Test class | Module | How it is performed | What it proves | S / TM / OWASP |
| --- | --- | --- | --- | --- |
| `SignedCatalogManifestTest` | engine | Ed25519 manifest create/verify | Bad signature / wrong gateway refused | S9; TM02; MCP03 |
| `SignedCatalogManifestFlowTest` | gateway | Own-host flow with signed pins | REQUIRE_PINS activates only signed tools | S9; TM02 |
| `GatewayFactoryTest` | config | `sources[].trust: QUARANTINED` | No callable tools until activated | S22; TM02/TM09; MCP04/09 contain |
| `ExistingMcpServerProxyTest` | mcp | Local upstream MCP; redirect to metadata; sampling | Egress on redirect; no sampling/elicitation to client; pin behaviour | S8, S9, S15; TM02, TM04, TM08 |
| `ContentGovernanceTest` / `ContentGovernorTest` | mcp / engine | resources/prompts/complete paths | Governed like tools; URI routing; bounds | S7, S11, S12, S13 |
| `McpServerHandlerTest` | mcp | Era headers, sessions, capacity | Caller-bound sessions; header/body agreement | S10, S14; MCP07/10 |
| `AdminApiTest` | mcp | Origin, non-approver, delegated accept, journal fail, verify | CSRF Origin; SoD on catalog accept; verify API shape | S17, S28; TM10 |

### 4.4 Audit chain

| Test class | Module | How it is performed | What it proves | S / TM / OWASP |
| --- | --- | --- | --- | --- |
| `HashChainFileAuditSinkTest` | engine | Append, verify, second writer | Chain verifies; second writer refused | S7, S28; TM10; MCP08 |
| `AuditCheckpointTest` | engine | Export head; truncated reopen | Checkpoint detects truncation/regeneration | S7; TM10 |

### 4.5 Credentials and secrets

| Test class | Module | How it is performed | What it proves | S / TM / OWASP |
| --- | --- | --- | --- | --- |
| `TokenExchangeCredentialsTest` | config | Mock token endpoint; subject mismatch; failure | Inbound JWT only to exchange URL; no fallback; subject check | S29; MCP01 |
| `SecretProvidersTest` | config | File secret source; failure shapes | Strict replacement; no secret echo on failure | claims S18 (file half) |
| `RemoteHookTest` | config | Hook URL off egress list; payload flags | Start-up refuse; args not included by default | S20 |

### 4.6 Added after the recorded run

These classes and methods were added after the 2026-10-05 selection. They are **not** part of its 227
reported tests or its result. Add them to the `-Dtest` list for the next candidate-bound run.

| Test class or method | Module | How it is performed | What it proves | S / TM / OWASP |
| --- | --- | --- | --- | --- |
| `GovernanceEngineTest.missingActionScopeDeniesBeforeCredentialsOrBackendAndFullGrantProceeds` | engine | Caller with no, partial (case-changed) and full scopes on a tool with two `actionScopes` | `SCOPE_DENIED` names the missing scopes; no credential lookup or backend call until every exact scope is present | S26; MCP02 |
| `SeparationOfDutiesTest.tokensFromAnotherGatewaySharingTheKeyAreRejected` (extended) | engine | Same action with and without an action-scope requirement | A changed scope requirement is a different binding; scope order is canonical; unscoped bindings keep their earlier digest | S5 |
| `JwtVerifierTest.validTokenMapsSubjectGroupsIssuerAndVerifiedScopes`, `ScopeEnforcementTest` | mcp-security | Verified `scope` and `scp` claims | The admitted caller carries verified scope names, never token material | S26 |
| `TokenExchangeCredentialsTest` subject-token cases | config | Readable issued JWTs with missing, blank, non-string, or mismatched `sub`; compact five-part JWE; opaque tokens; `checkSubject: false` | Current readable JWT subject checks fail closed, compact JWE is refused, opaque tokens remain accepted, and the explicit compatibility escape is tested | S29; MCP01 |
| `GatewayFactoryTest.actionScopesAreTrustedToolRequirementsAndDenyBeforeBackendAccess` | config | `tools[].actionScopes` in configuration | Wiring, denial, and refusal of malformed scope tokens | S26 |
| `SignedServerCatalogTest` | engine | Ed25519 server catalog and break-glass order: tamper, wrong key, replay as another document kind, malformed records, window checks; signed-only `CatalogIntegrity` | Verify-before-parse; ACTIVE needs digest and pins; non-ACTIVE vouches for nothing; orders only contain; no learning or acceptance under a catalog | S22; TM02/TM09; MCP03/MCP04/MCP09 |
| `ServerCatalogConfigTest` | config | Two sources, signed catalog, break-glass orders | Unlisted, changed or duplicated sources refuse start; non-ACTIVE sources are never started; unpinned tools are never bound; `catalog` cannot weaken pinning; separate incident key; expired order refuses start | S22 |
| `ServerCatalogFlowTest` | gateway | Real own host: reviewed catalog reply, `--server-catalog-create`, `--manifest-sign`, `--server-catalog-verify`, restart, `--break-glass-*` | The jar's offline generator and verifier produce files the gateway enforces | S22 |
| `AdminApiTest.noRouteRegistersABackendAtRunTime` (extended) | mcp | Every method on `/server-catalog` and `/break-glass` paths | No run-time route reads, replaces or bypasses either file | S30 |

## 5. Mapping: threat scenarios to proof

| Threat ID | Scenario | Primary proof in this suite |
| --- | --- | --- |
| TM01 | Prompt-injected destructive call | `ControlsTest` + `GovernanceEngineTest` + SoD |
| TM02 | Tool poisoning | Catalog pin/quarantine + signed manifest tests |
| TM03 | Confused deputy | `KnoxAclRouteScopeTest` + credential-after-allow tests |
| TM04 | SSRF | `ControlsTest` + `ExistingMcpServerProxyTest` |
| TM05 | READ mutates | sql-readonly / controls paths |
| TM06 | Self-approval | `SeparationOfDutiesTest` |
| TM07 | Shadow MCP outside Aegis | Not positively testable here (external) |
| TM08 | Over-sharing after allow | `OutputGuardTest`, content + proxy tests |
| TM09 | Dependency provenance | Partial (quarantine/SBOM); scanner not run |
| TM10 | Audit truncation | Hash-chain + checkpoint + verify API tests |

## 6. Mapping: OWASP MCP item to proof

| OWASP item | Proof in this suite | Not claimed |
| --- | --- | --- |
| MCP01 | Token non-passthrough, redaction, exchange unit tests | IdP storage/rotation |
| MCP02 | Default deny, SoD, scopes | IdP group lifecycle |
| MCP03 | Pin/quarantine/manifest | TOFU first-definition truth |
| MCP04 | Quarantine + egress unit tests | Vuln gate, signing, SCANOSS |
| MCP05 | Typed/safe converters + sql-readonly | OS sandbox of stdio/jobs |
| MCP06 | Screening + approval gates | Prevention of novel injection |
| MCP07 | JWT, perimeter, admin, Knox | Production IdP policy |
| MCP08 | Journal + metrics non-interference | Off-host SIEM |
| MCP09 | Containment for configured sources only | Discovery of bypass servers |
| MCP10 | Output bounds, no sampling | Client memory after allow |

## 7. Findings during this validation

### 7.1 D32 Oversized-body client abort message (fixed)

On the first suite attempt, `GatewayHostTest.transportBindingRejectsUnexpectedPathsAndMethodsAndProtectsMetrics`
failed inside `assertLargeBodyRejected`. The server rejects bodies over the configured max (413 path
or connection close before the backend is touched). The JDK HTTP client sometimes surfaces that close
as `IOException: HTTP/1.1 header parser received no bytes` instead of a status code or a message
containing `content-length` / `body`.

**Fix:** widen the accepted abort messages in the test helper (see [DEFECT-LOG.md](../DEFECT-LOG.md)
D32). Control behaviour was already correct. After the fix, the full 227-test selection passed.

### 7.2 Intermediate module surefire empty match

Without `-Dsurefire.failIfNoSpecifiedTests=false`, `-am` failed on modules such as
`aegis-observability-otlp` that contain none of the named classes. That is a harness detail, not a
product defect.

## 8. Explicitly not validated by this run

| Item | Where it lives instead |
| --- | --- |
| Keycloak live token exchange | [TOKEN-EXCHANGE-KEYCLOAK.md](TOKEN-EXCHANGE-KEYCLOAK.md) |
| OpenBao / Vault KV live | [SECRETS-OPENBAO.md](SECRETS-OPENBAO.md) |
| PostgreSQL shared state | [POSTGRES-RDS-VALIDATION.md](POSTGRES-RDS-VALIDATION.md), [POSTGRES-TWO-GATEWAYS.md](POSTGRES-TWO-GATEWAYS.md) |
| `KnoxPackagingBoundaryTest` jar contents | `examples/aegis-examples` (not in this `-pl` set) |
| `A2aToolSourceTest` | `aegis-source-a2a` module suite / FrontDoors |
| AI provenance scanner | Required before first release ([AGENTS.md](../../AGENTS.md)) |
| OWASP "all eliminated" | Not a claim; see threat model |

## 9. Current-tree reproduction command

From the repository root, on JDK 17+ with Maven 3.9+, run the current selection. This command adds the
post-2026-10-05 classes to the historical selection:

```text
mvn -B -pl aegis-engine,aegis-mcp-security,aegis-gateway,aegis-knox3-extension,aegis-mcp,aegis-config -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=GovernanceEngineTest,SeparationOfDutiesTest,ControlsTest,OutputGuardTest,HashChainFileAuditSinkTest,JwtVerifierTest,ScopeEnforcementTest,OwnServerPerimeterTest,GatewayHostTest,AdminApiTest,HooksTest,ContentGovernanceTest,ContentGovernorTest,RemoteHookTest,SchemaRefTest,JsonPointerExpressionTest,OwnServerSecuritySettingsTest,ProtectedResourceMetadataTest,SpiffeIdentityTest,SpiffeMutualTlsTest,TokenExchangeCredentialsTest,SignedCatalogManifestTest,SignedCatalogManifestFlowTest,AuditCheckpointTest,KnoxAclRouteScopeTest,KnoxExtensionTest,ProjectGovernanceGuardrailTest,ExistingMcpServerProxyTest,McpServerHandlerTest,JsonTest,ObservabilityTest,ContentHooksTest,GatewayFactoryTest,SecretProvidersTest,SignedServerCatalogTest,ServerCatalogConfigTest,ServerCatalogFlowTest
```

Confirm the current reactor succeeds and review the totals produced by that run. Do not expect or use
the historical 227 total as confirmation for the changed tree. Before promoting G03 to
`verified-current`, retain a candidate-bound evidence bundle with the full commit and tree SHA, raw
stdout/stderr, selected Surefire reports or a content-addressed archive, and a SHA-256 manifest as
required by [CLAIMS-MATRIX.md](CLAIMS-MATRIX.md).

## 10. Related evidence

- Property catalogue: [SECURITY.md](../SECURITY.md)
- Deep threat model: [THREAT-MODEL.md](../THREAT-MODEL.md)
- Release claims index: [CLAIMS-MATRIX.md](CLAIMS-MATRIX.md)
- Broader E2E day record: [E2E-VALIDATION-2026-10-05.md](E2E-VALIDATION-2026-10-05.md)
- Defect D32: [DEFECT-LOG.md](../DEFECT-LOG.md)
