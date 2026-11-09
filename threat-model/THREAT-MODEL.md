# Aegis MCP Gateway 2.0: CVE-Driven Threat Model, Findings and Fixes

Tree reviewed: `aegis-mcp-gateway-20261104-2.0-review-ready` at commit `2f7b944`
Date: 6 November 2026
Method: Find, Prove, Prioritize, Fix. Every claim below is backed by a test that ran against the real build. Nothing in this document is based on reading alone unless it is marked "by reading".

## 1. What I did, in one paragraph

I took the public MCP vulnerability record (OWASP MCP Top 10, the NVD entries and the research disclosures listed in section 4), grouped it into 20 attack classes, and replayed each class against Aegis in executable tests. Where Aegis blocked the attack I recorded BLOCKED with the deny code. Where full closure requires controls outside the gateway process I now record FULL_MANAGED_PATH or EXTERNAL_REQUIRED and name those controls. Then I went looking for weaknesses the CVE record does not describe, by attacking the controls themselves: approval binding, the rate-limit store, the injection screen, the audit journal, the listener configuration and upstream metadata. That produced six findings. I fixed all six in the source tree and kept the reproduction tests as regression tests. Later passes added eight more fixes and one non-issue reclassification, then four missing gateway controls. The latest five-module run is green; the fixes are uncommitted and the patch is in `patches/`.

## 2. Verdict summary

| Verdict | Count | Meaning |
|---|---|---|
| BLOCKED | 16 | Aegis denies the attack before the backend is reached |
| MITIGATED | 2 | Backend is reached but the harm is contained (redaction, truncation) |
| FULL_MANAGED_PATH | 2 | The current gateway narrows the path; full closure requires the mandatory endpoint/runtime/output control named in the row |
| FIXED | 14 | Weakness found by this review, fixed, and now covered by a regression test (6 in the first pass, 8 in the second) |
| NO-ISSUE | 1 | Suspected weakness re-examined at runtime and shown to be covered (admin route gating) |
| EXTERNAL_REQUIRED | see 4.3 and the census | CVEs whose vulnerable action occurs in a client, OAuth component or other process outside the gateway |

After the second pass: 35 verdict lines, 16 BLOCKED, 2 MITIGATED, 2 requiring FULL_MANAGED_PATH, 14 FIXED, 1 NO-ISSUE. The third pass (a live vulnerable MCP server behind the packaged jar, then closing what it let through) added 9 lines, `CLOSE-01` to `CLOSE-04b`: 7 FIXED, 2 MITIGATED. Total 44. The full table with CVE references and the exact evidence string each test printed is in `evidence/verdicts.md`; `verdicts.raw.txt` preserves the original test output. Module totals after the third pass, RAT check on: engine 158, mcp 54, config 75, security 25, gateway 35, all passing. The live run and the four closing fixes are written up in `DETAILED-FINDINGS-AND-FIXES.md`, Parts G, H and I. The dated NVD census of 561 MCP CVEs is in `cve-census/`.

## 3. Threat model

### 3.1 System and trust boundaries

```
 MCP client (model host)                 Aegis gateway                          Downstream
 ──────────────────────      TB1      ──────────────────────────      TB2     ─────────────────
 LLM + agent runtime  ──HTTPS/stdio──▶ perimeter (JWT/static/SPIFFE) ──▶ engine ──▶ HTTP tool / MCP server / JDBC
 browser (DNS rebind)                  McpServerHandler                 10-step chain      (stdio subprocess)
                                       AdminApi (same listener)
                                                     │ TB3
                                            audit journal, state store, OPA, remote hooks, JWKS, OTLP
```

Trust boundaries:
- TB1, client to gateway: the client is untrusted. The model is assumed to be under adversarial influence (prompt injection) and the transport may carry a replayed or forged credential.
- TB2, gateway to downstream: the downstream is semi-trusted. Its tool definitions, names, results and elicitation requests are treated as hostile input; its credentials are gateway-held.
- TB3, gateway to infrastructure: config files, keys, JWKS, OPA, remote hooks, journals. Trusted when fetched over TLS or from the local filesystem.

### 3.2 Assets

1. Backend credentials resolved per source (never seen by the model).
2. Approval tokens and dry-run receipts (single-use authorisations for MUTATE and DESTRUCTIVE calls).
3. The tool catalog pins (what definition the model is allowed to see).
4. The audit journal (the only record of who did what).
5. The gateway's own listener identity (bearer tokens in flight).
6. Backend data reachable through tools (the thing every CVE is ultimately after).

### 3.3 STRIDE by boundary

| Threat | TB1 client side | TB2 downstream side | TB3 infrastructure |
|---|---|---|---|
| Spoofing | forged JWT (TM-18), session reuse (TM-08), unauthenticated call (TM-06) | upstream advertises a tool name that collides or confuses admin paths (TM-20) | plaintext JWKS key substitution (TM-18 part 7) |
| Tampering | argument injection past schema (TM-13), header/body desync (TM-16) | rug pull of a trusted definition (TM-01) | audit journal edit or deletion (TM-15) |
| Repudiation | caller denies a destructive call | backend result altered | journal with live approval tokens (GAP-04) |
| Information disclosure | secrets in results (TM-05), exfil URL in args (TM-02c) | binary or oversized output (TM-14) | tokens on a plaintext listener (TM-19) |
| Denial of service | oversized or malformed bodies (TM-09), call floods (TM-17) | hung backend (breaker) | rate-limit store eviction resets budgets (GAP-02) |
| Elevation of privilege | tool restriction bypass (TM-10), approval replay (TM-11), MRTR answers outside approval (GAP-01) | SSRF to metadata (TM-02, TM-02b), path traversal (TM-03), SQL injection (TM-04) | injection phrase straddling the screen window (GAP-03) |

## 4. Known MCP CVE coverage

The executable suites below replay representative attack classes. They are not a row-by-row execution of every published CVE. The dated NVD census is [`cve-census/MCP-CVE-CENSUS.md`](cve-census/MCP-CVE-CENSUS.md), with all 561 normalized rows in [`MCP-CVE-CENSUS.csv`](cve-census/MCP-CVE-CENSUS.csv). Its closure states are `FULL_GATEWAY_PATH`, `FULL_MANAGED_PATH`, and `EXTERNAL_REQUIRED`; each row names the exact Aegis and external control. Raw NVD query responses and the generator are retained under `cve-census/`.

### 4.1 CVEs and incidents replayed, with verdict

The verdict is what the test observed at runtime. "Blocked at step N" refers to the engine's governance chain (1 exposure, 2 input and scope, 3 policy, 4 approval, 5 egress, 6 rate, 7 breaker, 8 screening and hooks, 9 VRP, 10 execute).

| Class | Public references | Aegis verdict | Where it stops |
|---|---|---|---|
| Tool poisoning and rug pull | Invariant Labs 2025-04; MCPTox (arXiv 2508.14925, 36.5% success across 45 servers); CSA tool-poisoning note 2026-07; CSA hidden-Unicode note 2026-03 | BLOCKED | Catalog pin mismatch quarantines the tool, `CATALOG_INTEGRITY`, hidden from `tools/list`. Poisoned descriptions are quarantined on first sight by the injection screen. |
| SSRF to cloud metadata | CVE-2026-45019 (Chainlit), CVE-2026-77822 and CVE-2026-18905 (IBM ContextForge), arXiv 2608.00150 | BLOCKED | Step 5: scheme and host allow list, every DNS answer classified, private and link-local refused. |
| DNS rebinding of an allowed name | CVE-2026-81092 (mcp-go), CVE-2026-77822 | BLOCKED | Step 5: `*.corp.example.com` resolving to 10.0.0.5 is `EGRESS_DENIED` with reason PRIVATE. |
| Exfiltration via URL in arguments | CVE-2025-34072 (Slack link unfurl), GitHub MCP issue injection 2025-05 | BLOCKED | Step 5: `urlsIn()` walks every nested string and key. |
| Path traversal in a resource argument | CVE-2025-53110 (filesystem containment), CVE-2026-73498 (mcp-atlassian) | BLOCKED for visible traversal; FULL_MANAGED_PATH for backend resolution | Step 2 refuses `..`, control characters, `*`, `?`. CVE-2025-53109 symlink resolution occurs inside the backend; complete closure requires `ResourceBroker` opaque handles plus an isolated read-only mount. |
| SQL injection and stacked statements | CVE-2025-66335 (Apache Doris MCP), CVE-2026-49257 (Apache Pinot MCP, CVSS 10.0), CVE-2026-9680 (Alibaba RDS MCP), Anthropic SQLite server, Supabase and Cursor 2025-07 | BLOCKED | `SqlReadOnlyHook`: one statement, reading first keyword, no writing keyword anywhere, unterminated quote or comment refused. |
| Side-effecting SELECT functions | same class | BLOCKED (after passes 2 and 3) | Pass 2 added a deny list of about 90 side-effecting functions (`pg_terminate_backend`, `lo_import`, `setval`, `dblink_exec`, `pg_sleep` and the MySQL, SQL Server, Oracle, SQLite and Hive equivalents). Pass 3 added `functions: DENY_UNLISTED`, which refuses any function not on an allow list, so an unknown user-defined or vendor function is denied rather than trusted. A read-only database role remains the control that holds if the hook is wrong. |
| Secrets in output, logs, telemetry | CVE-2026-44969 and CVE-2026-44970 (dbt-mcp), CVE-2026-50143 (Apify token leak) | MITIGATED | AWS key, JWT, PEM block and bearer are redacted in results; the journal carries `argsDigest` only by default. See GAP-04 for the `auditArguments: true` case I fixed. Pass 3 added opt-in `output.redactHighEntropy` for prefix-less key material (CLOSE-04). A short or lower-case-only secret still passes. |
| Unauthenticated server | CVE-2025-49596 (MCP Inspector RCE), arXiv 2608.00150 (91.8% without OAuth), January 2026 scan of 1,862 open servers | BLOCKED | 401 with no identity; configuration refuses an anonymous identity type. |
| DNS rebinding against the listener | CVE-2025-35028 (SDK protection off by default), CVE-2026-81092, CVE-2026-32632, CVE-2026-55637, CVE-2026-81315 | BLOCKED | Any `Origin` not in the exact allow list is 403 before dispatch; the default list is empty, so every browser origin is refused. |
| Session poisoning | CVE-2026-67431 (MCP Ruby SDK) | BLOCKED | Session bound to `principalKey` (issuer, actor, subject); another caller gets 404; 256-bit random ids. |
| Unbounded or malformed bodies | CVE-2026-67432 (Ruby SDK), CVE-2026-53965 (PHP SDK), CVE-2025-53366 (FastMCP) | BLOCKED | 413 over `maxBytes`; 400 for malformed JSON, depth, batches, duplicate keys. The handler never throws. |
| Tool restriction bypass, cross tenant | CVE-2026-45350 (Open WebUI), Asana cross-tenant incident 2025-06 | BLOCKED | Step 1 `READONLY_CALLER`, `NOT_EXPOSED`; step 3 deny-overrides policy on `prod.*`. |
| Approval replay or argument swap | OWASP MCP02 and MCP07 | BLOCKED | Token bound to caller, tool, argument digest and definition digest; consumed once. |
| Prompt injection in arguments and results | GitHub, Supabase, Atlassian ticket injections 2025; OWASP MCP06 | FULL_MANAGED_PATH | Current phrase screening catches known patterns in arguments, definitions, content and results. Complete closure requires managed endpoint taint propagation plus `STRUCTURED_ALLOWLIST` or `HANDLE_ONLY`, so arbitrary backend text never gains model authority. |
| Command or argument injection | CVE-2026-45018 (Chainlit), CVE-2026-44968 (dbt-mcp), CVE-2025-53967 (Figma) | FULL_MANAGED_PATH | Current schema controls refuse typed and undeclared-key attacks. Complete closure requires `ExecutionContract` to prohibit `FREE_FORM`, typed adapters only, and a sandbox with no ambient authority. |
| Hostile backend output | OWASP MCP10 | MITIGATED | 1 MiB bound with `isError` preserved; binary parts refused by default. |
| Audit tampering | OWASP MCP08 | BLOCKED | Editing or deleting a record breaks the hash chain and the verifier reports it. |
| Header and body desync | MCP 2026-07-28 server validation | BLOCKED | `Mcp-Name` and `Mcp-Method` must be single-valued and equal to the body. |
| Call floods | OWASP LLM10 Unbounded Consumption (an earlier version said MCP09, which is Shadow MCP Servers) | BLOCKED | Per-caller, per-tool and team minute windows, `RATE_LIMITED`; per-address pre-auth budget and lockout (Part L, GAP-L3a). |
| DNS rebinding between egress check and connect | Docker MCP Gateway CVE-2025-64443 class; ContextForge A2A CVE-2026-77822 class | BLOCKED | `PinnedHttp` connects only to the addresses `EgressGuard.pin` checked; TLS keeps SNI and hostname verification on the name (Part L, GAP-L1a to L1e). |
| Stolen bearer token replay | OWASP MCP01, MCP07 | BLOCKED when `requireJti` and `requireRevocation` are set | Per-token and per-subject revocation in the shared state store, fail closed (Part L, GAP-L2a to L2c). |
| JWT forgery | OWASP MCP07, alg-confusion family | BLOCKED | alg=none, wrong key, disallowed alg, audience replay, expiry and RSA-key-as-HMAC-secret are all rejected; signature is checked before claims. |

### 4.2 Where the CVE record is weaker than Aegis

Four things that appear in several 2026 advisories and that Aegis gets right by construction: redirects are never followed on any outbound HTTP (so a 302 to the metadata address is not a bypass); every outbound body is streamed against a byte bound, not trusted from `Content-Length`; duplicate JSON keys are rejected rather than first-or-last-wins; credentials are resolved at step 10 and never before, so a denied call never touches a credential provider.

### 4.3 CVEs Aegis cannot fix, and I will not claim it does

- CVE-2025-6514 (mcp-remote command injection via an attacker-controlled authorisation endpoint) is a client-side bug in a different program. Aegis does not perform OAuth discovery against downstreams, so the pattern does not exist in it, but that is absence, not a fix.
- CVE-2026-24052 (Claude Code WebFetch domain validation) and the IDE auto-execution advisories (Cursor, Gemini CLI, Copilot, Amazon Q) are in the client. Aegis cannot govern what a client does before it calls the gateway.
- postmark-mcp (September 2025) was a malicious package that silently BCC'd mail. Pinning detects a changed definition, not changed behaviour behind an unchanged definition. The egress guard governs the gateway's own connections, not what the backend process connects to.
- CVE-2025-53109 (filesystem server symlink escape) and any backend-internal logic flaw: Aegis sees the argument, not the filesystem.

## 5. Novel attacks I created and what they found

These are attacks against Aegis's own controls, not replays of published CVEs. Each was written as a test first, observed to succeed against the original code (the "before" evidence is in `evidence/baseline-before-fixes.txt` and `evidence/gap02-against-original-store.log`), then fixed.

### GAP-01: approval bound less than what executed (MRTR elicitation answers)

Issue. On a 2026-07-28 retry the client sends `inputResponses` (answers to the backend's elicitation) and `requestState`. Both were forwarded to the backend, but `argsDigest`, the approval token, the dry-run receipt and the approver's evidence covered only the plain arguments. An operator could obtain approval for `create_topic {topic: orders, partitions: 3}`, then execute it with elicitation answers like `cleanupPolicy: delete-everything` that no approver ever saw. The approval check passed because the digest matched.

Fix. `GovernanceEngine.digestInput()` now builds the digest over an envelope `{arguments, inputResponses, requestState}` whenever the call is a retry. For a plain call the digest input is unchanged, so existing approvals, receipts, pins and audit records keep their values. `approverEvidence()` now shows `inputResponses` (redacted and bounded) and a digest of `requestState`. Files: `GovernanceEngine.java`.

Validation. `gap01_...`: the old token is refused with "different action"; the backend receives nothing; a fresh request's evidence contains the elicitation content; after approval the retry runs once; the same token with different answers is refused again. The MRTR suite in `aegis-mcp` (`McpRoundTripTest`) still passes.

### GAP-02: hourly budgets silently reset when the rate-limit table filled

Issue. `InMemoryStateStore.sweepWindows(nowMs, w)` used the window length of the key being acquired to decide which entries had expired. A one-minute rate key reaching the table at capacity evicted every entry older than 60 seconds, including live one-hour budget windows, which were then recreated at zero. Reaching capacity needs `maxEntries` keys (default one million), so this is a large-tenant or small-config condition, but once reached it is a full budget bypass.

Fix. `Window` records its own `length`; the sweep removes only windows that have actually ended. Files: `InMemoryStateStore.java`.

Validation. `gap02_...` fails against the original class (`evidence/gap02-against-original-store.log`: "expected false but was true") and passes against the fixed one. A full table with nothing expired now refuses the new key instead of evicting a live budget, which is the fail-closed direction.

### GAP-03: injection phrase hidden across the screening window edge

Issue. `InjectionScreen.match()` sliced the raw string into 64 KiB windows with a 1,024-character overlap, then normalised each window. The overlap was measured in raw characters but the phrase is matched after format characters are stripped. A phrase padded with zero-width joiners to 1,148 raw characters and placed across the window edge was cut in both windows and never matched. The code comment claimed "no match spans a gap"; the claim was false.

Fix. Normalise the whole string first, then match. Normalisation is linear and inputs are already bounded by the JSON limits, so there is no cost reason to slice. The base64 branch keeps its 64 KiB prefix bound. Files: `InjectionScreen.java`.

Validation. `gap03_...`: the padded payload is now `PROMPT_INJECTION`; `ControlsTest.screeningCoversTheWholeStringNotOnlyTheFirstWindow` still passes.

### GAP-04: live approval tokens and argument secrets written to the audit journal

Issue. With `auditArguments: true`, `record()` journaled `t.call.arguments()`, the raw request map. A client using the 0.1-style argument carrier (`approvalToken`, `dryRunReceipt` as arguments, which `advertiseControlArguments` still advertises) had its token written to every DECISION record, including denials where the token had not been consumed and was still valid. Argument values were not redacted either, unlike approver evidence and error messages.

Fix. The journal now records `t.backendArgs` (control carriers already stripped) or, when preflight failed before the split, the raw map minus `LEGACY_ARGS`, and in both cases passes the result through `OutputGuard.redactAll`. Files: `GovernanceEngine.java`.

Validation. `gap04_...`: an `EGRESS_DENIED` call carrying a live token and an AWS key in its arguments produces journal records that contain neither, while `arguments` is still present.

### TM-19: bearer tokens accepted on a plaintext network listener

Issue. `OwnServerSecuritySettings` allowed `listen.host: 0.0.0.0` with no `tls.keystore`. Every identity type on this host is a bearer secret or needs TLS, so this configuration put tokens on the wire for replay. It is the single most common pattern in the 2026 MCP advisories, and the Helm chart requires `0.0.0.0`.

Fix. A non-loopback `listen.host` without `tls.keystore` is a `ConfigException` unless `listen.allowPlaintext: true` is set, which is the operator saying "TLS terminates at my ingress or sidecar". The flag is refused where it has no effect, like every other unused key in that parser. The chart CI values and the chart comment were updated. Files: `OwnServerSecuritySettings.java`, `charts/aegis/ci/test-values.yaml`, `charts/aegis/values.yaml`.

Validation. `tm19_...` covers 0.0.0.0 refused, 10.0.0.7 refused, loopback accepted, declared plaintext accepted, flag refused on loopback. `OwnServerSecuritySettingsTest` and `GatewayHostTest` still pass.

### TM-18 part 7: JWKS over plain HTTP

Issue. `jwksUri` accepted any scheme. An on-path attacker on an `http://` JWKS could substitute their own RSA or EC key and mint tokens for any subject and group, including the admin groups. Every other outbound URL in the gateway is egress-checked; this one was not.

Fix. `JwtVerifier.Settings` refuses a `jwksUri` that is not `https` unless the host is a loopback literal or `localhost`, which keeps the existing local-IdP tests working. Files: `JwtVerifier.java`.

### TM-20: hostile upstream tool names

Issue. `ClientSupport.validTool` accepted any non-blank name from a downstream server. A name with `/` could not be accepted through the admin API path (`/catalog/{tool}/accept` splits on `/`), control and zero-width characters could reach `tools/list` and audit records, and the length was bounded only by the 256 KiB string limit.

Fix. Names must be 1 to 128 characters, with no control, DEL or Unicode format characters, no `/` or `\`, and no surrounding whitespace. Non-conforming tools are dropped at discovery. Unicode letters remain allowed. Files: `ClientSupport.java`.

## 6. What is good

By reading and by test, these held up under attack and I would not change them:

- The governance order. Approval is evaluated after egress, rate, breaker, screening and hooks, so humans only see calls that automation would allow.
- Fail-closed everywhere I probed: any exception in a control denies; a journal that cannot write denies execution (`AUDIT_UNAVAILABLE`); a full state table now refuses rather than evicts.
- Credentials are resolved at step 10 and never earlier.
- Separation of duties in `ApprovalService.decide`: approver group, non-delegated identity, requester cannot approve their own request including through the actor chain, NFKC and case folding on names.
- Signed artefacts (manifest, server catalog, break-glass) are verified over the exact bytes before parsing, with unknown keys rejected and a monotonic serial.
- The JSON parser: strict UTF-8, duplicate keys rejected, lone surrogates rejected, depth and count bounds applied while parsing.
- Outbound HTTP: redirects never followed, bodies streamed against a bound, `Content-Length` never trusted, TLS never relaxed.
- `HttpToolExecutor.segment()` refuses `/`, `\`, `%`, `.` and `..` in path parameters instead of trusting percent-encoding, because some backends decode before routing.
- The Knox bridge builds identity from the Knox `Subject` only; no header is trusted.

## 7. What still needed fixing after the first pass (all closed in the second pass)

The items below were open after the first pass. Each is now fixed with a regression test, except item 3 which was re-examined and found to be covered by per-route authorisation (see `DETAILED-FINDINGS-AND-FIXES.md`, Part C and Fixes 8 to 14). The list is kept as the record of what was found. Verdict lines `OPEN-01` to `OPEN-08` in `evidence/verdicts.md` carry the proof.

1. Hook timeouts in the engine. `HookRunner` has no deadline. `RemoteHook` has its own 2 second timeout, but an in-process `GovernanceHook` from the classpath can block a request thread while holding a circuit-breaker probe permit, which marks the source `BREAKER_OPEN` for everyone until the hook returns. The javadoc promises that a hook that "times out" denies; the engine does not enforce it. Fix: run `before`, `afterTool` and `afterContent` through a bounded executor with a per-binding timeout, deny on timeout in ENFORCE mode. Confirmed by reading.
2. Pre-authentication slow-header exhaustion on the JDK `HttpServer`. Headers are parsed on the worker thread before `perimeter.admit()`. With 32 threads, a queue of 256 and `CallerRunsPolicy`, about 300 half-open connections stall the accept thread for up to `maxReqTime` (30 seconds). Fix: set `sun.net.httpserver.maxReqHeaders` and `maxReqHeaderSize`, replace `CallerRunsPolicy` with `AbortPolicy` plus a 503, and document a reverse proxy in front for internet exposure. Not reproduced at runtime in this review.
3. Admin API on the same listener and port as MCP, with `adminGroups` empty meaning "any authenticated caller". Second-level gates exist per route (approver groups, audit verifier groups, the digest header), and I found no bypass, but a stolen MCP token can enumerate pending approvals. Fix: default `adminGroups` to deny-all when unset and log a startup warning, or bind admin to a separate port.
4. Approver group membership is checked at decide time only. A token minted before an approver was removed from the group stays valid until `tokenTtl`. Fix: re-check the approver's groups at `verify` from the journal, or shorten the default token TTL.
5. `SqlReadOnlyHook` does not understand PostgreSQL dollar quoting or nested block comments. Both fail in the safe direction today (false denials), but a future change could flip that. Fix: add dollar-quote handling and nested comment depth, with tests for both directions.
6. Session capacity lock-out. 10,000 sessions at 32 per principal means about 313 distinct principals can hold the registry at capacity and every further `initialize` gets 503 for 30 minutes. Fix: evict the globally least-recently-used session when the table is full instead of refusing.
7. The private-key redaction pattern can cost about 16K steps per unterminated `BEGIN` marker. A 256 KiB argument string packed with markers reaches this through the approval-evidence path for one rate-limit unit. Fix: pre-scan for a matching `END` before applying the lazy quantifier, or cap markers per string. Cost not measured at runtime here.

## 8. Where more security is needed (design-level, not bugs)

OWASP coverage: `OWASP-TOP10-COVERAGE.md` maps all 20 OWASP MCP and LLM risks. 13 reach a closed state on the gateway or managed path. Seven have a part no tool gateway owns:

- MCP09 Shadow MCP Servers.
- LLM01 (direct injection).
- LLM03 (model supply chain).
- LLM04 Data and Model Poisoning.
- LLM07 System Prompt Leakage.
- LLM08 Vector and Embedding Weaknesses.
- LLM09 Misinformation.

Those need endpoint, model-provider or data-pipeline controls. The ContextForge side-by-side is in `AEGIS-VS-CONTEXTFORGE.md`.

- Tool results are now screened (Fix 17, `screening.results`; ENFORCE by default since the sixth pass, AUDIT is opt-in). The phrase screen is still a tripwire; the closure for result injection is `OutputPolicy` STRUCTURED_ALLOWLIST or HANDLE_ONLY.
- The injection screen is a phrase list. It stops the payloads in the public record, and GAP-03 shows how such lists fail at the edges. Treat it as a tripwire and put the real control in classification: MUTATE and DESTRUCTIVE tools need an approval token regardless of what the model was told.
- `SqlReadOnlyHook` indicates read-only; a read-only database role enforces it. The javadoc already says this. The adopter configurations should make the read-only role the default for JDBC sources, not a recommendation.
- Rate-limit and approval state in `InMemoryStateStore` is lost on restart, which also resets the signed-serial high-water mark. Multi-instance or restart-sensitive deployments should use the Postgres state store so a replayed old manifest or a burst after restart is still caught.
- The schema validator skips `pattern`, `anyOf`, `oneOf` and `allOf` on purpose (ReDoS). Operators who need a value shape should pin it with `enum` or `const` in the governed schema rather than rely on the upstream pattern.
- Telemetry exports `aegis.tool` for unknown tools from the client-supplied name (sanitised, 200 chars). It is bounded, but a caller can write chosen strings into the tracing backend. Consider exporting a fixed `unknown` for tools that are not in the catalog.

## 9. Files changed, how to validate, and what was not executed

### 9.1 Source changes (uncommitted, in the review tree)

| File | Change |
|---|---|
| `aegis-engine/.../GovernanceEngine.java` | `digestInput()` envelope for MRTR retries; approver evidence includes `inputResponses` and `requestState` digest; journaled arguments stripped of control carriers and redacted |
| `aegis-engine/.../state/InMemoryStateStore.java` | `Window` carries its length; sweep removes only ended windows |
| `aegis-engine/.../control/InjectionScreen.java` | normalise whole string before matching; `WINDOW_OVERLAP` removed |
| `aegis-mcp-security/.../identity/JwtVerifier.java` | `jwksUri` must be https or loopback |
| `aegis-mcp-security/.../perimeter/OwnServerSecuritySettings.java` | non-loopback plaintext listener refused unless `listen.allowPlaintext: true`; `isLoopback()` helper |
| `aegis-mcp/.../client/ClientSupport.java` | `safeToolName()`; `validTool` drops hostile names |
| `charts/aegis/ci/test-values.yaml`, `charts/aegis/values.yaml` | declare `allowPlaintext: true` for ingress-terminated TLS; document the rule |

The unified diff is `patches/aegis-2.0-security-fixes.patch` (358 lines). Six new test files are untracked in the tree and copied to `tests/`.

### 9.2 Required commands and expected outcomes

```powershell
# From the review tree. Reproduction suites only (about 90 s):
powershell -File ..\threat-model\run-threat-model.ps1
# expected: Maven exit 0, 28 THREATMODEL verdict lines, none with verdict GAP

# Full regression of the touched modules (about 6 min):
& C:\tools\apache-maven-3.9.11\bin\mvn.cmd -o -pl aegis-engine,aegis-mcp,aegis-config,aegis-mcp-security,aegis-gateway test
# expected: engine 149, mcp 52, config 69, security 25, gateway 34; BUILD SUCCESS
```

Observed on 6 November 2026: both outcomes as expected. Logs are in `evidence/`.

### 9.3 Invariants that must not regress

- A plain (non-retry) `tools/call` produces the same `argsDigest` as before this change. Approvals, receipts and pins issued before the patch stay valid.
- A retry carrying `inputResponses` or `requestState` must never verify against a token issued for the plain call.
- A denied call must never journal an unconsumed approval token or receipt.
- `InMemoryStateStore.tryAcquire` on a full table must refuse when nothing has expired.
- `InjectionScreen.firstMatch` must be independent of where in the string the phrase sits.
- A loopback `listen.host` works without TLS and without `allowPlaintext`; `http://127.0.0.1` JWKS remains accepted for local IdPs.

### 9.4 Not executed, and known limits of this review

- The slow-header DoS (section 7 item 2) and the private-key regex cost (item 7) were analysed by reading, not measured.
- No test ran against a real downstream MCP server over the network; the executor and client layers were exercised with in-process fakes and the existing `ExistingMcpServerProxyTest` and `GatewayHostTest` fixtures.
- The Knox extension, Derby and Postgres state stores, JDBC, A2A and ZooKeeper sources were read for the hunt (via sub-agent reports) but received no new tests.
- Nothing was committed or pushed. `git status` is in `evidence/git-status-after.txt`.

### 9.5 Review checklist for the maintainer

1. Decide whether the MRTR digest envelope is the design you want, or whether retries should instead require a fresh approval unconditionally. The patch takes the envelope route because it keeps plain-call digests stable.
2. Decide whether `allowPlaintext` is the right name and whether the chart should set it by default when `tls` is absent.
3. Confirm the 128-character tool-name bound against the downstream servers you govern; raise it if any legitimate server exceeds it.
4. Run the two commands in 9.2 and compare totals.
5. Pick the first two items of section 7 for the next iteration; both are reachable without a credential.
