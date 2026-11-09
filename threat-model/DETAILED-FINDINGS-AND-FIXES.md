# Aegis MCP Gateway 2.0: Detailed Attack Log, Findings, Fixes and Capability Assessment

Companion to `THREAT-MODEL.md`. This document answers, in order: what I attacked, what held, what needs fixing, what I fixed and exactly how (class, method, code), which CVEs each item maps to, what extra security is still needed, and whether Aegis controls MCP security completely or not.

Tree: `aegis-mcp-gateway-20261104-2.0-review-ready`, commit `2f7b944`. All runtime claims come from tests in `tests/` that ran against this build; logs are in `evidence/`.

---

## Part A. Everything I attacked

I attacked six surfaces. For each, the table lists the attack, the public CVE or incident it reproduces, the test that ran it, and the observed result. "Result" is what the running code did, not what the documentation says it does.

### A.1 Transport and protocol layer (`aegis-mcp`)

| # | Attack | Reproduces | Test | Result |
|---|---|---|---|---|
| 1 | POST `tools/call` with no identity | CVE-2025-49596 MCP Inspector unauthenticated RCE; 1,862 open servers (Jan 2026 scan); 91.8% of 640 internet servers without OAuth (arXiv 2608.00150) | `ThreatModelCveTransportTest.tm06` | 401, backend untouched |
| 2 | Authenticated POST with `Origin: http://attacker-rebind.example:8080` (browser DNS rebinding) | CVE-2025-35028 SDK rebinding protection off by default; CVE-2026-81092 mcp-go; CVE-2026-32632 Glances; CVE-2026-55637 genieacs; CVE-2026-81315 ash_ai | `tm07` | 403 before dispatch; `Origin: https://app.example.evil` also 403 against an allow list containing `https://app.example` |
| 3 | `initialize` as Alice, then `tools/list` with Alice's `Mcp-Session-Id` as Olga | CVE-2026-67431 Ruby SDK session poisoning (no owner binding) | `tm08` | owner 200, thief 404 |
| 4 | Body larger than `maxBytes` | CVE-2026-67432 Ruby SDK unbounded JSON-RPC body | `tm09` | 413 |
| 5 | Truncated JSON `{"jsonrpc":"2.0","id":` | CVE-2025-53366 FastMCP validation error becomes 500 until restart | `tm09` | 400, no exception |
| 6 | 500-level nested object | depth DoS class | `tm09` | 400 or 413, no stack overflow |
| 7 | JSON-RPC batch array | spec says batches are not supported | `tm09` | 400 |
| 8 | Duplicate `method` key (`tools/call` then `tools/list`) | parser-differential class | `tm09` | 400 "duplicate object key"; no first-wins or last-wins ambiguity |
| 9 | `Mcp-Name: harmless_tool` with body `name: echo` | MCP 2026-07-28 server validation; proxy routes on header, server executes body | `tm16` | 400 HEADER_MISMATCH |
| 10 | `Mcp-Method: tools/list` with body `tools/call` | same | `tm16` | 400 |
| 11 | Downstream advertises tool names `../admin`, `list/accept`, `wipe<NUL>`, zero-width `list\u200bTopics`, 129 chars, backslash | MCP03 metadata poisoning; CSA hidden-Unicode note 2026-03 | `ThreatModelCveUpstreamNameTest.tm20` | **Accepted before the fix**; dropped after (see Part D, Fix 6) |

### A.2 Governance engine (`aegis-engine`)

| # | Attack | Reproduces | Test | Result |
|---|---|---|---|---|
| 12 | Backend redefines `list_topics` description after it was trusted (rug pull) | Invariant Labs 2025-04-01; MCPTox 36.5% average success; CSA tool-poisoning note 2026-07 | `ThreatModelCveEngineTest.tm01` | `CATALOG_INTEGRITY`, hidden from `tools/list`, backend never called |
| 13 | New tool whose description contains `<IMPORTANT> ... ignore previous instructions ... do not tell the user` | Invariant Labs tool poisoning; CSA hidden-instruction research | `tm01b` | quarantined on first sight |
| 14 | `fetch_url` whose target is `http://169.254.169.254/latest/meta-data/` | CVE-2026-45019 Chainlit SSRF; CVE-2026-77822 ContextForge; arXiv 2608.00150 metadata SSRF | `tm02` | `EGRESS_DENIED` step 5 |
| 15 | Allow-listed name `internal.corp.example.com` that resolves to 10.0.0.5 | CVE-2026-77822 / CVE-2026-18905 ContextForge DNS rebinding; CVE-2026-81092 | `tm02b` | `EGRESS_DENIED`, reason names PRIVATE |
| 16 | URL `https://exfil.attacker.example/collect` buried three levels deep in a free-text argument | CVE-2025-34072 Slack link-unfurl exfiltration; GitHub MCP issue injection 2025-05 | `tm02c` | `EGRESS_DENIED` |
| 17 | `topic: "../../etc/passwd"` on a resource-scoped tool | CVE-2025-53110 filesystem containment bypass; CVE-2026-73498 mcp-atlassian traversal | `tm03` | `SCOPE_DENIED` |
| 18 | `topic: "ok\u0000.hidden"` (NUL truncation differential) | same class | `tm03` | `SCOPE_DENIED` |
| 19 | Backend returns AWS key, JWT, PEM private key, bearer token | CVE-2026-44969 / CVE-2026-44970 dbt-mcp; CVE-2026-50143 Apify | `tm05` | all four redacted to `[REDACTED]`; audit records carry `argsDigest` only |
| 20 | Analyst (read-only group) calls `create_topic` | CVE-2026-45350 Open WebUI tool-restriction bypass | `tm10` | `READONLY_CALLER` |
| 21 | Call to a tool not in the catalog | Asana cross-tenant class | `tm10` | `NOT_EXPOSED` |
| 22 | Operator deletes `prod.orders` where policy denies `prod.*` | MCP02 scope creep | `tm10` | `POLICY_DENIED` (deny overrides) |
| 23 | Approval token reused for different `partitions` | MCP02/MCP07 | `tm11` | `APPROVAL_REQUIRED` "different action" |
| 24 | Approval token replayed after one successful use | same | `tm11` | `APPROVAL_REQUIRED` "already been used" |
| 25 | `IGNORE  previous\u200b instructions` nested in an array inside an object | GitHub, Supabase, Atlassian ticket injections 2025 | `tm12` | `PROMPT_INJECTION` |
| 26 | Same phrase base64-encoded | same | `tm12` | `PROMPT_INJECTION (base64)` |
| 27 | `partitions: "1; rm -rf /"` on an integer field | CVE-2026-45018 Chainlit; CVE-2025-53967 Figma | `tm13` | `INVALID_INPUT` wrong type |
| 28 | Extra key `--target-path` where schema has `additionalProperties: false` | CVE-2026-44968 dbt-mcp CLI argument injection | `tm13` | `INVALID_INPUT` unexpected property |
| 29 | 2 MB tool result | MCP10 over-sharing | `tm14` | truncated to bound with `isError` preserved |
| 30 | Edit the `actor` field of a journal record; delete the middle record | MCP08 audit tampering | `tm15` | verifier fails on both: "record 1: record content does not match its hash" |
| 31 | Third call in a minute against a limit of 2 | LLM10 unbounded consumption (corrected; MCP09 is Shadow MCP Servers) | `tm17` | `RATE_LIMITED` |
| 32 | Approved call replayed with MRTR `inputResponses` the approver never saw | novel | `gap01` | **Allowed before the fix**, backend received the answers; refused after (Fix 1) |
| 33 | Fill the rate-limit table with one-minute keys while a one-hour budget is live | novel | `gap02` | **Budget evicted and reset before the fix**; survives after (Fix 2) |
| 34 | Injection phrase stretched with 40 zero-width joiners per letter across the 64 KiB window edge | novel (CSA hidden-Unicode class) | `gap03` | **Allowed before the fix**; `PROMPT_INJECTION` after (Fix 3) |
| 35 | `auditArguments: true` with a live approval token in the legacy `approvalToken` argument on a denied call | novel (CVE-2026-44969 class) | `gap04` | **Token and AWS key written to the journal before the fix**; both absent after (Fix 4) |

### A.3 Authentication perimeter (`aegis-mcp-security`)

| # | Attack | Reproduces | Test | Result |
|---|---|---|---|---|
| 36 | JWT `alg: none`, empty signature, groups `approvers, admins` | alg-none CVE family | `ThreatModelCveAuthTest.tm18` | rejected |
| 37 | HS256 signed with attacker key | key forgery | `tm18` | rejected |
| 38 | `alg: RS256` against an HS256-only gateway, `kid: ../../etc/passwd` | alg confusion, kid injection | `tm18` | rejected; kid is only a map key |
| 39 | Valid signature, `aud` for another MCP server | token replay across resources | `tm18` | rejected |
| 40 | Expired token | | `tm18` | rejected |
| 41 | RSA-only gateway given HS256 signed with the RSA public key bytes | classic RSA/HMAC confusion | `tm18` | rejected; keys live under `kid\|RS256` and never satisfy HS256 |
| 42 | `jwksUri: http://idp.internal/jwks` | key substitution by an on-path attacker; CVE-2025-6514 class (trusting attacker-controlled auth metadata) | `tm18` step 7 | **Accepted before the fix**; config error after (Fix 5) |
| 43 | `listen.host: 0.0.0.0` with no TLS keystore | bearer tokens in cleartext; the most common 2026 advisory pattern | `ThreatModelCvePerimeterTest.tm19` | **Accepted before the fix**; config error after unless declared (Fix 7) |

### A.4 SQL hook (`aegis-config`)

| # | Attack | Reproduces | Test | Result |
|---|---|---|---|---|
| 44 | `SELECT * FROM users WHERE id = 1; DROP TABLE users` | CVE-2025-66335 Apache Doris MCP; CVE-2026-49257 Apache Pinot MCP (CVSS 10.0); CVE-2026-9680 Alibaba RDS MCP; Anthropic SQLite server; Supabase/Cursor 2025-07 | `ThreatModelCveSqlTest.tm04` | denied, "more than one statement" |
| 45 | `WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x` | CTE-DML | `tm04` | denied, keyword DELETE |
| 46 | `SELECT * FROM t INTO OUTFILE '/tmp/x'` | MySQL file write | `tm04` | denied, keyword INTO |
| 47 | `SELECT * FROM t FOR UPDATE` | lock escalation | `tm04` | denied, keyword UPDATE |
| 48 | `EXPLAIN ANALYZE DELETE FROM t` | EXPLAIN ANALYZE executes | `tm04` | denied |
| 49 | `SELECT 1 /* unterminated`, `SELECT 'unterminated` | parser desync | `tm04` | denied rather than guessed |
| 50 | `SELECT * FROM t WHERE a = 'x'; DELETE FROM t; -- '` | quote-comment confusion | `tm04` | denied |
| 51 | `SELECT pg_terminate_backend(4242)`, `lo_import('/etc/passwd')`, `setval(...)`, `dblink_exec('...', 'DELETE FROM customers')`, `pg_sleep(30)` | side-effecting SELECT | `tm04b` | **Passed the hook before the fix**; denied after (Fix 11) |
| 52 | `SELECT $$a; b$$ ...`, `$q$it's; DELETE$q$`, `/* a /* b */ c */`, unterminated `$$` | dollar quoting, nested comments | `open06` | parsed like PostgreSQL after Fix 12; unterminated forms fail closed |

### A.6 Second-pass attacks against Aegis's own controls

| # | Attack | Test | Result |
|---|---|---|---|
| 53 | In-process hook that sleeps 30 s while holding the breaker permit | `ThreatModelCveEngineTest.open01` | **Request thread held before the fix**; denied in 300 ms after (Fix 8) |
| 54 | 18 half-open requests fill a 2-worker pool, then one more | `ThreatModelCveServerTest.open02` | **Ran on the accept thread before the fix**; rejected immediately after (Fix 9) |
| 55 | Analyst (no admin group) probes `/approvals`, `/approvals/{id}`, `/approvals/{id}/approve`, `/catalog`, `/catalog/x/accept`, `/audit/verify`, `/prompts` | `ThreatModelCveTransportTest.open03` | 403 / 404 / 403 / 403 / 428 / 404 / 404: every route gated on its own; **no issue** |
| 56 | Approval service configured with a 2-hour token TTL | `open04` | **Accepted before the fix**; rejected after (Fix 10) |
| 57 | Fifth principal against a full 4-slot session table | `open07` | **503 before the fix**; admitted with LRU eviction after (Fix 13) |
| 58 | 9,700 unterminated `-----BEGIN RSA PRIVATE KEY-----` markers in one 256 KiB string | `open08` | **2,805 ms before the fix**; 138 ms after (Fix 14) |

### A.5 Attacks analysed by reading only (not run)

These were examined in code but not reproduced at runtime. They are listed so the reader knows what was and was not executed.

| Attack | Where | Conclusion |
|---|---|---|
| Slow-header connection exhaustion before authentication | `AegisServer` constructor, `ThreadPoolExecutor` with `CallerRunsPolicy` | Confirmed by reading; pool behaviour then reproduced at runtime in the second pass (A.6 #54) and fixed |
| Private-key redaction regex cost (`[\s\S]{0,16384}?` per `BEGIN` marker) | `OutputGuard.DEFAULT_REDACTIONS` | Measured in the second pass at 2,805 ms per call (A.6 #58) and fixed |
| In-process hook that never returns | `HookRunner.before` | Reproduced at runtime in the second pass (A.6 #53) and fixed |
| HTTP tool path traversal via `{arg}` placeholders | `HttpToolExecutor.segment()` | Safe: refuses `/`, `\`, `%`, `.`, `..` outright, then percent-encodes |
| Header injection via tool arguments | `HttpToolExecutor.execute` | Safe: arguments never reach headers; JDK `HttpClient` rejects CR/LF |
| Redirect to metadata address from a downstream | `HttpMcpClient`, `RemoteHook`, `OpaPolicy`, `JwtVerifier`, `OtlpSpanExporter` | Safe: every client is built with `Redirect.NEVER` |
| Secret file path traversal | `FileSecretProvider.apply` | Safe: name regex, `..` rejected, real path must stay under the directory |
| YAML deserialisation gadget | `ConfigLoader.YamlSupport`, `OpenApiDraft.SpecYaml` | Safe: `SafeConstructor`, duplicates rejected, alias and depth limits |
| OPA response parsing | `OpaPolicy.decide` | Safe: only literal `true` or `{allow: true}` allows; anything else denies |
| Remote hook response | `RemoteHook.ask` | Safe: non-200, non-boolean `allow`, oversized body and timeout all throw; the hook cannot rewrite a result |
| Token exchange cache confused deputy | `TokenExchangeCredentials.headers` | Safe: cache key is the SHA-256 of the caller's own subject token; `sub` of the issued token must equal the caller |
| Approver approves own request through delegation chain | `ApprovalService.decide` | Safe: requester actor, effective subject, every chain member and principal key are refused; names compared after NFKC and case folding |
| Signed manifest replay | `SignedCatalogManifest`, `SignedSerial` | Safe while the state store persists; in-memory store loses the high-water mark on restart (Part E) |
| Knox identity from headers | `KnoxIdentityBridge` | Safe: identity comes from the Knox `Subject` only |

---

## Part B. What had no issue

Controls that were attacked and held, with the mechanism that made them hold. These need no change.

| Control | Class | Why it held |
|---|---|---|
| Catalog pinning | `CatalogIntegrity`, `ToolCatalog` | SHA-256 of the definition pinned on first sight (TOFU) or required from config; any change quarantines; descriptions screened before pinning |
| Egress guard | `EgressGuard` | scheme allow list, host allow list with wildcard that never matches the bare domain, every DNS answer classified (loopback, link-local, private, unique-local, CGNAT, multicast, reserved), IPv4-mapped and transition prefixes unwrapped, `urlsIn` walks keys and values at any depth |
| Resource scoping | `ResourceRule.hygiene`, `ScopeRule` | rejects empty, over 512 chars, `..`, control characters, `*`, `?`; glob matching is bounded |
| Approval binding | `Action.binding`, `SignedTokens`, `ApprovalService` | HMAC-SHA256 with a per-type derived key, payload carries caller principal key, tool, args digest, definition digest and nonce; constant-time compare; nonce consumed atomically |
| Separation of duties | `ApprovalService.decide` | approver group, non-delegated identity, requester and chain excluded |
| JWT verification | `JwtVerifier` | algorithm allow list from config, signature before claims, iss/aud/exp/nbf/iat, per-algorithm key namespace, JWKS fail-closed when stale, RSA under 2048 bits ignored |
| Session registry | `SessionRegistry` | 256-bit random ids, bound to principal key, per-caller cap with LRU self-eviction |
| JSON parser | `Json.Parser` | strict UTF-8, duplicate keys rejected, lone surrogates rejected, depth, entry, element, string and number bounds checked during parse |
| Header-body consistency | `McpServerHandler.modernHttp` | `Mcp-Name`, `Mcp-Method`, `MCP-Protocol-Version` single-valued and equal to the body |
| Output guard | `OutputGuard` | redaction of AWS keys, PEM blocks, bearer tokens, JWTs at any depth including `_meta`; binary refused by default; truncation preserves `isError` |
| Audit journal | `HashChainFileAuditSink`, `AuditChainVerifier` | each record carries the previous hash; checkpoint file anchors the head; execution denied if the journal cannot write |
| Rate limiting | `RateLimiter`, `InMemoryStateStore.tryAcquire` | per-principal fixed windows; key is self-delimiting JSON so it cannot be spoofed |
| Credential handling | `GovernanceEngine.govern` step 10 | credentials resolved only after ALLOW_INTENT is journaled; never cached across callers; never visible to the model |
| Outbound HTTP | `BoundedHttp` | streaming byte bound, `Content-Length` untrusted, total deadline watchdog, redirects never followed |
| Configuration parsing | `ConfigNode`, `OwnServerSecuritySettings.parse` | unknown keys refused; `*` origin refused; anonymous identity refused; scopes require JWT |

---

## Part C. What still needs fixing

**After the second pass: nothing in this list remains open.** The eight items below were the open findings after the first pass. Seven are now fixed (Part D, Fixes 8 to 14) and one was re-examined and found to be a non-issue (item 3). The table is kept as the record of what was found and how each was closed.

| # | Issue | Class and location | Evidence | Status after second pass |
|---|---|---|---|---|
| 1 | No hook timeout in the engine | `HookRunner.before`, `afterTool`, `afterContent` | By reading: the loop called `h.before(context)` with no executor or deadline, despite the javadoc promising a timed-out hook denies | **FIXED** (Fix 8): `HookBinding.timeout`, default 5 s, max 60 s, enforced in `HookRunner.invoke`; runtime test `open01` shows a 30 s hook denied in 300 ms |
| 2 | Pre-authentication slow-header exhaustion | `AegisServer`: `CallerRunsPolicy`, no header-size bound | By reading: the JDK parses headers before `perimeter.admit()` | **FIXED** (Fix 9): header-count, header-size and connection bounds set; saturated pool rejects instead of running on the accept thread; runtime test `open02` |
| 3 | Admin API on the MCP listener; `adminGroups` empty means any authenticated caller | `OwnServerPerimeter.admit` | By reading | **NO-ISSUE** on re-examination. I first implemented "empty means deny-all" and then found it breaks a legitimate flow: a requester must reach `GET /approvals/{id}` to collect their own token (`GatewayHostTest`, `TwoGatewayProcessesPostgresIT`). The perimeter admits to the admin *path*; every admin *route* carries its own gate. Runtime test `open03` proves an analyst gets 403 on `/approvals`, 404 on another's request, 403 on approve, 403 on `/catalog`, 428 without the digest header even as an approver. The change was reverted. |
| 4 | Approver group checked at decide time only | `ApprovalService.decide` vs `verify` | By reading | **FIXED** (Fix 10): the gateway has no directory to re-check membership against, so the exposure window is bounded instead: token TTL capped at 15 minutes in `ApprovalService` and in configuration; runtime test `open04` |
| 5 | SQL hook passes side-effecting functions | `SqlReadOnlyHook.firstProblem` | Runtime (`tm04b` first pass): `pg_terminate_backend`, `lo_import`, `setval`, `dblink_exec`, `pg_sleep` passed | **FIXED** (Fix 11): `SIDE_EFFECT_FUNCTIONS` deny list covering PostgreSQL, MySQL, SQL Server, Oracle, SQLite, Hive; `tm04b` now asserts denial of 12 functions |
| 6 | SQL hook does not understand dollar quoting or nested block comments | `SqlReadOnlyHook.stripCommentsAndQuoted` | By reading | **FIXED** (Fix 12): `$tag$...$tag$` literals and nested `/* */` depth; unterminated forms fail closed; runtime test `open06` |
| 7 | Session table lock-out | `SessionRegistry.create` | By reading | **FIXED** (Fix 13): global LRU eviction when full; runtime test `open07` |
| 8 | Private-key redaction regex cost | `OutputGuard.DEFAULT_REDACTIONS[1]` | By reading, then measured: the regex form took 2,805 ms on a 9,700-marker flood | **FIXED** (Fix 14): linear scanner `OutputGuard.redactPem`; same flood now 138 ms; runtime test `open08` |

---

## Part D. What I fixed and exactly how

Seven changes, all uncommitted, all covered by a regression test that fails on the original code and passes on the fixed code. The unified diff is `patches/aegis-2.0-security-fixes.patch`. Line numbers refer to the file after the change.

### Fix 1. Approval and receipt now bind MRTR elicitation answers (GAP-01)

**Class:** `io.github.vaquarkhan.aegis.engine.GovernanceEngine`
**Methods:** `preflight` (step 2), new `digestInput`, `approverEvidence` (overloaded), two call sites in `govern` and `completeDryRun`

**The issue.** MCP 2026-07-28 lets a backend ask the client for more input (elicitation). The client retries the call with `inputResponses` and `requestState`. Aegis forwarded both to the backend (`ExecutionContext` carries `call.roundTrip()`) but computed `argsDigest` over the plain arguments only. The approval token, the dry-run receipt and the approver's evidence all derive from that digest. So an operator could obtain approval for `{topic: orders, partitions: 3}`, then execute it with `inputResponses` carrying `cleanupPolicy: delete-everything`, and the approval check would pass because the digest matched. The `Action` javadoc promised that "what was approved" and "what executed" cannot drift apart; for retries that promise was false.

**Before:**
```java
Action action = new Action(tool.exposedName(), tool.toolClass(), tool.sourceId(), resources,
        Digests.canonicalSha256(args), List.copyOf(tool.actionScopes()));
```

**After:**
```java
Action action = new Action(tool.exposedName(), tool.toolClass(), tool.sourceId(), resources,
        Digests.canonicalSha256(digestInput(args, call.roundTrip())), List.copyOf(tool.actionScopes()));
```

**New method:**
```java
static Object digestInput(Map<String, Object> args, RoundTrip rt) {
    if (rt == null || !rt.isRetry()) {
        return args;                      // plain call: digest unchanged, old approvals stay valid
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("arguments", args);
    envelope.put("inputResponses", rt.inputResponses());
    envelope.put("requestState", rt.requestState() == null ? "" : rt.requestState());
    return envelope;
}
```

**Approver evidence** gained the same fields so a human sees what will execute:
```java
if (rt != null && rt.isRetry()) {
    ev.put("inputResponses", boundedEvidence(settings.outputGuard().redactAll(rt.inputResponses()).value()));
    if (rt.requestState() != null) {
        ev.put("requestStateDigest", Digests.canonicalSha256(rt.requestState()));
    }
}
```

**Why this shape.** The envelope is used only on retries, so the digest of every plain call is byte-identical to before. Existing approvals, receipts, catalog pins and audit records keep their values. A retry is a different action from the plain call and gets its own approval, which is the correct semantics: the approver must see the answers.

**Proof.** `gap01_mrtrInputResponsesReachBackendOutsideApprovedDigest`: before the fix the call was ALLOWED and `executor.calls.get(0).roundTrip().inputResponses()` was non-empty (`evidence/baseline-before-fixes.txt`). After the fix: the stale token is refused with "different action", the backend receives nothing, the fresh request's evidence contains `delete-everything`, the approved retry runs once, and the same token with different answers is refused again. `McpRoundTripTest` (the existing MRTR suite) still passes.

### Fix 2. Rate-limit sweep no longer evicts live budget windows (GAP-02)

**Class:** `io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore`
**Members:** record `Window`, `tryAcquire`, `sweepWindows`

**The issue.** When the window table reached `maxEntries`, `tryAcquire` swept it using the window length `w` of the key being acquired. A one-minute rate key therefore removed every window whose start was more than 60 s ago, including one-hour budget windows that were still running. The budget key was then recreated with `used = 0`, a full budget bypass.

**Before:**
```java
private record Window(long start, long used) {}
...
if (!windows.containsKey(key) && windows.size() >= maxEntries) {
    sweepWindows(nowMs, w);
...
private void sweepWindows(long nowMs, long w) {
    windows.entrySet().removeIf(e -> e.getValue().start() + w <= nowMs);
}
```

**After:**
```java
private record Window(long start, long used, long length) {
    boolean expired(long nowMs) {
        return start + length <= nowMs;
    }
}
...
if (!windows.containsKey(key) && windows.size() >= maxEntries) {
    sweepWindows(nowMs);
...
Window base = (cur == null || cur.start() != start) ? new Window(start, 0, w) : cur;
...
return new Window(start, base.used() + cost, w);
...
private void sweepWindows(long nowMs) {
    windows.entrySet().removeIf(e -> e.getValue().expired(nowMs));
}
```

**Proof.** `gap02_inMemoryStateStoreSweepResetsHourBudgetWhenFull`. I stashed the fix and ran the test against the original class: it fails with "no window has ended, so a full table must refuse rather than evict a live budget, expected false but was true" (`evidence/gap02-against-original-store.log`). Against the fixed class it passes (`evidence/gap02-against-fixed-store.log`). The behaviour when the table is full and nothing has expired is now to refuse the new key, which is the fail-closed direction.

### Fix 3. Injection screen cannot be bypassed at the window edge (GAP-03)

**Class:** `io.github.vaquarkhan.aegis.engine.control.InjectionScreen`
**Method:** `match`; constant `WINDOW_OVERLAP` removed

**The issue.** The screen sliced the raw string into 64 KiB windows with a 1,024-character overlap, then normalised each window (NFKC, format characters removed, whitespace collapsed, lower-cased) and searched for phrases. The overlap was counted in raw characters; the phrase is matched after format characters are stripped. A phrase padded with 40 zero-width joiners per letter spans 1,148 raw characters. Placed so it starts before the overlap region and ends after the window edge, it was cut in both windows and matched in neither. The comment "overlap exceeds any phrase, so no match spans a gap" was false.

**Before:**
```java
for (int start = 0; start < raw.length() || start == 0; start += MAX_SCREENED_CHARS - WINDOW_OVERLAP) {
    String window = raw.substring(start, Math.min(raw.length(), start + MAX_SCREENED_CHARS));
    String n = normalise(window);
    for (String p : phrases) {
        if (n.contains(p)) {
            return p;
        }
    }
    if (start + MAX_SCREENED_CHARS >= raw.length()) {
        break;
    }
}
```

**After:**
```java
String n = normalise(raw);
for (String p : phrases) {
    if (n.contains(p)) {
        return p;
    }
}
```

**Why this is safe to do.** `normalise` is linear in the input and every input string is already bounded by `JsonLimits.maxStringChars` (256 KiB default). There was never a cost reason to slice before normalising. The base64 branch keeps its 64 KiB prefix bound unchanged.

**Proof.** `gap03_injectionScreenWindowOverlapMissesFormatPaddedPhrase`: before, outcome `ALLOWED`; after, `PROMPT_INJECTION` and the backend is never called. `ControlsTest.screeningCoversTheWholeStringNotOnlyTheFirstWindow` still passes.

### Fix 4. Audit journal never records live approval tokens or raw secrets (GAP-04)

**Class:** `io.github.vaquarkhan.aegis.engine.GovernanceEngine`
**Method:** `record`

**The issue.** With `auditArguments: true`, every DECISION, ALLOW_INTENT and EXECUTION_RESULT record contained `t.call.arguments()`, the raw request map. A client using the 0.1-style argument carrier (`approvalToken`, `dryRunReceipt` as arguments, which `advertiseControlArguments` still advertises) had its token written to the journal. On a denied call (egress, rate, breaker) the token has not been consumed and remains valid until `tokenTtl`. Argument values were also not redacted, unlike approver evidence and error messages, which already go through `OutputGuard`.

**Before:**
```java
if (settings.auditArguments() && t.call.arguments() != null) {
    r.put("arguments", t.call.arguments());
}
```

**After:**
```java
if (settings.auditArguments() && t.call.arguments() != null) {
    Map<String, Object> journaled;
    if (t.backendArgs != null) {
        journaled = t.backendArgs;                       // control carriers already stripped by ControlTokens.split
    } else {
        journaled = new LinkedHashMap<>(t.call.arguments());
        journaled.keySet().removeAll(ControlTokens.LEGACY_ARGS);   // denied before the split: strip by name
    }
    r.put("arguments", settings.outputGuard().redactAll(journaled).value());
}
```

**Proof.** `gap04_auditArgumentsNeverJournalControlSecretsOrRawCredentials`: an `EGRESS_DENIED` call carrying a live token and `AKIAIOSFODNN7EXAMPLE` in its arguments produces journal records that contain neither string, while the `arguments` field is still present (redacted).

### Fix 5. JWKS must be fetched over TLS (TM-18 part 7)

**Class:** `io.github.vaquarkhan.aegis.security.identity.JwtVerifier.Settings`
**Members:** compact constructor; new static `jwksTransportTrusted`

**The issue.** `jwksUri` accepted any scheme. Over plain HTTP an on-path attacker can serve their own RSA or EC key and mint tokens for any subject and group, including the admin and approver groups. Every other outbound URL in the gateway (OTLP, OPA, remote hooks, token endpoint) is checked against the egress allow list at startup; the JWKS URL was not.

**Added to the constructor:**
```java
if (jwksUri != null && !jwksTransportTrusted(jwksUri)) {
    throw new IllegalArgumentException("jwksUri must use https (plain http is accepted only for a loopback address): " + jwksUri);
}
```

**New method:**
```java
static boolean jwksTransportTrusted(URI u) {
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if ("https".equals(scheme)) {
        return true;
    }
    if (!"http".equals(scheme) || u.getHost() == null) {
        return false;
    }
    String host = u.getHost().toLowerCase(Locale.ROOT);
    if ("localhost".equals(host) || "[::1]".equals(host) || "::1".equals(host)) {
        return true;
    }
    try {
        return InetAddress.getByName(host).isLoopbackAddress() && host.matches("[0-9a-fA-F:.\\[\\]]+");
    } catch (UnknownHostException e) {
        return false;
    }
}
```
The IP-literal regex guard means a host name is never resolved here, so this check cannot itself become a DNS lookup an attacker influences.

**Proof.** `tm18` step 7: `http://idp.internal/jwks` throws; `http://127.0.0.1:8443/jwks` is accepted. `JwtVerifierTest` and `GatewayHostTest`, which use a loopback test IdP, still pass.

### Fix 6. Hostile upstream tool names are dropped at discovery (TM-20)

**Class:** `io.github.vaquarkhan.aegis.mcp.client.ClientSupport`
**Members:** `validTool`; new `safeToolName`, `MAX_TOOL_NAME_CHARS`

**The issue.** A downstream server could advertise any non-blank name. A name containing `/` could never be accepted through `POST /catalog/{tool}/accept` (the path is split on `/`); control and Unicode format characters reached `tools/list`, audit records and the `Mcp-Name` header; length was bounded only by the 256 KiB JSON string limit.

**Before:**
```java
static boolean validTool(Object o) {
    Map<String, Object> m = JsonValues.asObject(o);
    return m != null && m.get("name") instanceof String n && !n.isBlank()
            && (m.get("inputSchema") == null || m.get("inputSchema") instanceof Map);
}
```

**After:**
```java
static final int MAX_TOOL_NAME_CHARS = 128;

static boolean validTool(Object o) {
    Map<String, Object> m = JsonValues.asObject(o);
    return m != null && m.get("name") instanceof String n && safeToolName(n)
            && (m.get("inputSchema") == null || m.get("inputSchema") instanceof Map);
}

static boolean safeToolName(String n) {
    if (n.isBlank() || n.length() > MAX_TOOL_NAME_CHARS || !n.equals(n.strip())) {
        return false;
    }
    for (int i = 0; i < n.length(); i++) {
        char c = n.charAt(i);
        if (c < 0x20 || c == 0x7f || c == '/' || c == '\\' || Character.getType(c) == Character.FORMAT) {
            return false;
        }
    }
    return true;
}
```
Unicode letters remain allowed (`résumé` passes), matching the existing handler test for Unicode names.

**Proof.** `tm20`: `../admin`, `list/accept`, `wipe<NUL>`, `a<TAB>b`, `list<ZWSP>Topics`, leading space, 129 characters and a backslash are all dropped; `list_topics`, `kafka.describe-topic`, `résumé`, `ListTopics_v2` pass.

### Fix 7. Plaintext listener on a network interface must be declared (TM-19)

**Class:** `io.github.vaquarkhan.aegis.security.perimeter.OwnServerSecuritySettings`
**Members:** `parse`; new static `isLoopback`; new config key `host.listen.allowPlaintext`
**Also:** `charts/aegis/ci/test-values.yaml`, `charts/aegis/values.yaml`

**The issue.** `listen.host: 0.0.0.0` with no `tls.keystore` parsed without complaint. Every identity type on this host is a bearer secret (JWT, static token) or needs TLS (SPIFFE), so this configuration puts credentials on the wire for replay. It is the single most common pattern across the 2026 MCP advisories, and the Helm chart requires `0.0.0.0` inside the pod.

**Added to `parse`:**
```java
boolean allowPlaintext = listen.bool("allowPlaintext", false);
...
if (keystore == null && !isLoopback(host) && !allowPlaintext) {
    throw new ConfigException("config.host.listen.host " + host + " is not a loopback address and config.host.tls.keystore"
            + " is not set: bearer tokens would travel in cleartext. Configure tls.keystore, or set"
            + " config.host.listen.allowPlaintext: true when TLS is terminated by a trusted sidecar or ingress");
}
if (allowPlaintext && (keystore != null || isLoopback(host))) {
    throw listen.error("allowPlaintext", "has no effect with tls.keystore set or a loopback listen.host; remove it");
}
```

**New helper:**
```java
static boolean isLoopback(String host) {
    String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    if (h.isEmpty() || "localhost".equals(h)) {
        return true;
    }
    if (!h.matches("[0-9a-f:.\\[\\]]+")) {
        return false;                          // a host NAME is never resolved here
    }
    try {
        return InetAddress.getByName(h.replace("[", "").replace("]", "")).isLoopbackAddress();
    } catch (UnknownHostException e) {
        return false;
    }
}
```

**Chart changes.** `charts/aegis/ci/test-values.yaml` now sets `allowPlaintext: true` with a comment that TLS terminates at the ingress; `charts/aegis/values.yaml` documents the rule. The flag is refused where it has no effect, consistent with how that parser treats every other unused key.

**Proof.** `tm19`: `0.0.0.0` refused with a message containing "cleartext"; `10.0.0.7` refused; `127.0.0.1` accepted; `0.0.0.0` with `allowPlaintext: true` accepted; `allowPlaintext` on loopback refused. `OwnServerSecuritySettingsTest` and `GatewayHostTest` still pass.

### Second pass: the seven remaining items

### Fix 8. Hook invocations run under a wall-clock budget (OPEN-01)

**Classes:** `io.github.vaquarkhan.aegis.engine.control.HookBinding` (new component `timeout`, constants `DEFAULT_TIMEOUT` 5 s and `MAX_TIMEOUT` 60 s, `bounded()`), `io.github.vaquarkhan.aegis.engine.control.HookRunner` (new `invoke`, `HookTimeoutException`, per-runner daemon thread pool, shutdown in `close`), `io.github.vaquarkhan.aegis.config.GatewayFactory` (new `hooks[].timeoutMs`)

**The issue.** `HookRunner` called `h.before(context)` directly. A hook that blocked (lock, socket, slow directory) held the request thread and, because hooks run after step 7, a circuit-breaker PROBE permit; the source stayed `BREAKER_OPEN` for every caller until the hook returned. The class javadoc promised "a hook that throws, times out or returns nothing denies", but nothing enforced the middle clause.

**Core of the change:**
```java
private <T> T invoke(HookBinding b, Callable<T> work) throws Exception {
    if (!b.bounded() || hookThreads == null) {
        return work.call();                                  // timeout 0: operator opted out, in-thread
    }
    Future<T> f = hookThreads.submit(work);
    try {
        return f.get(b.timeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        f.cancel(true);
        throw new HookTimeoutException(b.hook().name(), b.timeout().toMillis());
    } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception ex) { throw ex; }    // HookDenied and hook errors keep their type
        throw new IllegalStateException(cause);
    } ...
}
```
All three phases (`before`, `afterTool`, `afterContent`) go through `invoke`. A timeout is an ordinary hook failure: deny in ENFORCE, audit note in AUDIT. The denial reason names the hook and the budget ("hook stuck-dlp did not answer within 300 ms"). Configuration: `hooks[].timeoutMs`, default 5000, 0 disables (documented as only safe for hooks that cannot block).

**Proof.** `open01_hookThatNeverAnswers_isDeniedWithinItsBudget`: a hook that sleeps 30 s under a 300 ms budget yields `POLICY_DENIED` in well under 5 s, the backend is not called, and the same hook in AUDIT mode lets the call through. `HooksTest` and `ContentHooksTest` (18 tests) still pass, including the "hook cannot close" case.

### Fix 9. Pre-authentication exhaustion of the own HTTP server (OPEN-02)

**Class:** `io.github.vaquarkhan.aegis.gateway.AegisServer` (`applyServerTimeLimits`, new `boundedPool`)

**The issue.** The JDK `HttpServer` reads and parses the request line and headers on a worker thread before `AegisServer.route()` runs `perimeter.admit()`. With no header bound a client could send a very long header line; with `CallerRunsPolicy` a saturated pool ran the next request on the accept thread, so a few hundred half-open connections stopped the server accepting anyone. Neither needs a credential.

**Change:**
```java
static void applyServerTimeLimits() {
    setIfAbsent("sun.net.httpserver.maxReqTime", "30");
    setIfAbsent("sun.net.httpserver.maxRspTime", "120");
    setIfAbsent("sun.net.httpserver.maxReqHeaders", "100");
    setIfAbsent("sun.net.httpserver.maxReqHeaderSize", "16384");
    setIfAbsent("jdk.httpserver.maxConnections", "1024");
}

static ThreadPoolExecutor boundedPool(int threads) {
    return new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(threads * 8), daemonFactory, (r, executor) -> {
                LOG.log(WARNING, "HTTP worker pool saturated (...); refusing a connection");
                throw new RejectedExecutionException("aegis worker pool saturated");
            });
}
```
On rejection the JDK dispatcher logs and closes that one connection; the accept loop keeps running. Operator-set properties are never overridden.

**Proof.** `open02_saturatedWorkerPool_refusesInsteadOfBlockingTheAcceptThread`: with 2 workers and 16 queued slow clients, the 19th `execute` throws immediately (under 1 s) instead of running on the caller. The three new properties are present. `GatewayHostTest` and `ProtectedResourceMetadataTest` (real server, real sockets) still pass.

### Fix 10. Approval token lifetime is capped (OPEN-04)

**Classes:** `io.github.vaquarkhan.aegis.engine.approval.ApprovalService` (new `MAX_TOKEN_TTL` 15 min, constructor check), `GatewayFactory` (`approval.tokenTtlSeconds` validated 1..900)

**The issue.** An approver's group membership is checked when they decide. A token they minted stays valid until `tokenTtl`, so an approver removed from the group a minute later has still authorised a call that may run later. The gateway has no directory to re-check membership at verify time, so the window is bounded instead.

**Change:** `tokenTtl` must be 1 s to 15 min; anything else is an `IllegalArgumentException` in the engine and a `ConfigException` naming `approval.tokenTtlSeconds` in configuration. The shipped default (600 s) is unchanged.

**Proof.** `open04_approvalTokenLifetime_isCapped`: 2 h and 0 s rejected, exactly 15 min accepted. All existing approval tests use 10 min and still pass.

### Fix 11. Side-effecting functions are denied by the SQL hook (TM-04b)

**Class:** `io.github.vaquarkhan.aegis.config.hooks.SqlReadOnlyHook` (new `SIDE_EFFECT_FUNCTIONS`, checked in `firstProblem`)

**The issue.** `SELECT pg_terminate_backend(1)`, `SELECT lo_import('/etc/passwd')`, `SELECT setval(...)`, `SELECT dblink_exec('...', 'DELETE ...')`, `SELECT pg_sleep(30)` all passed: first keyword SELECT, no writing keyword. The javadoc deferred to a read-only database role.

**Change:** a token deny list of 90 function names grouped by dialect (PostgreSQL sequences, large objects, server files, signals, config, dblink, sleep; MySQL `load_file`, `sys_exec`, `sleep`, `benchmark`; SQL Server `xp_cmdshell`, `sp_executesql`, `openrowset`, `waitfor`; Oracle `utl_http`, `utl_file`, `dbms_sql`; SQLite `load_extension`, `writefile`; Hive `reflect`, `java_method`). Matching is on whole tokens, so `sleep_log` is unaffected and function names inside string literals are already replaced by the quote stripper. Names likely to collide with ordinary column names (`system`, `edit`, `bulk`) were deliberately left out.

**Proof.** `tm04b_sideEffectFunctions_areNowDenied`: 12 side-effecting statements denied; `SELECT customer_id, sleep_minutes FROM sleep_log` and a literal containing `pg_sleep` still allowed. The existing `SqlReadOnlyHookTest` (allow list of benign statements) still passes.

### Fix 12. Dollar quoting and nested block comments (OPEN-06)

**Class:** `SqlReadOnlyHook.stripCommentsAndQuoted`, new `dollarTagEnd`

**The issue.** PostgreSQL treats `$$ ... $$` and `$tag$ ... $tag$` as string literals and nests `/* */` comments. The stripper did neither. Both failed in the safe direction (a `;` inside `$$` was counted as a second statement), but the tokenizer disagreed with the database, and a future edit could flip that into a bypass.

**Change:** block comments track depth; a dollar-quote opener (`$`, optional identifier tag, `$`; a leading digit such as `$1` is a positional parameter, not an opener) is matched to its closing tag and replaced by the `'x'` placeholder. Unterminated forms throw, which the hook reports as a denial.

**Proof.** `open06_dollarQuotesAndNestedCommentsAreParsedLikeTheDatabase`: `SELECT $$a; b$$ ...` allowed, `$q$it's; DELETE$q$` allowed, `$1, $2` allowed, unterminated `$$` denied, `/* a /* b */ c */` treated as one comment, unbalanced nesting denied, a `; DELETE` after a nested comment still denied.

### Fix 13. Session table never locks everyone out (OPEN-07)

**Class:** `io.github.vaquarkhan.aegis.mcp.server.SessionRegistry.create`

**The issue.** 10,000 sessions at 32 per principal: about 313 principals could hold the table full, and every further `initialize` from anyone got 503 until the 30-minute idle TTL passed.

**Change:** after sweeping idle sessions and the caller's own share, if the table is still full the globally least-recently-used session is evicted. The evicted client re-initialises on its next call (one round trip) instead of every new client being refused.

**Proof.** `open07_fullSessionTable_evictsLeastRecentInsteadOfLockingEveryoneOut`: with capacity 4 and four live principals, a fifth is admitted, the table stays at 4, and the oldest session is gone. Session binding tests (TM-08) still pass.

### Fix 14. PEM private-key redaction in linear time (OPEN-08)

**Class:** `io.github.vaquarkhan.aegis.engine.control.OutputGuard` (new `redactPem`, `indexOfWithin`, `labelOnly`; PEM pattern removed from `DEFAULT_REDACTIONS`)

**The issue.** The regex `-----BEGIN [A-Z ]{0,40}PRIVATE KEY-----[\s\S]{0,16384}?-----END ...` walks up to 16K positions per unterminated BEGIN marker. Measured on a 256 KiB string of 9,700 markers (the JSON string bound, reachable through approver evidence for one rate-limit unit): 2,805 ms per call. My first scanner replacement still used an unbounded `String.indexOf` for the END marker and took 1,128 ms, so the test earned its keep.

**Change:** a scanner that (a) bounds every search to its window with a hand-written `indexOfWithin` and (b) caches the position of the next `-----END ` so that the END search is amortised across all BEGIN markers (one forward pass). Found blocks are replaced by `[REDACTED]`; an unterminated block carries no complete key and is left as is. The scanner always runs, independent of the configurable regex list.

**Proof.** `open08_pemRedactionIsLinearUnderMarkerFlood`: real RSA and PKCS#8 blocks redacted exactly; the 9,700-marker flood now takes 138 ms. TM-05 (PEM in backend output) and `OutputGuardTest` still pass.

### Regression totals after both passes

| Module | Tests | Result |
|---|---|---|
| aegis-engine | 152 | pass |
| aegis-mcp | 54 | pass |
| aegis-config | 70 | pass |
| aegis-mcp-security | 25 | pass |
| aegis-gateway | 35 | pass |

`evidence/module-test-totals.txt`, `evidence/maven-pass2-all-modules.log`. 17 source and chart files changed (492 insertions, 58 deletions); patch at `patches/aegis-2.0-security-fixes.patch`.

---

## Part E. Extra security still needed (design level)

These are not bugs. They are places where the current design stops short of what the threat record now demands.

1. **Tool results are not screened for injection.** Closed in the third pass (Part H, Fix 17): `screening.results` runs the phrase screen over every string of a result, AUDIT by default, ENFORCE on request. Kept here as the record of why it was added.

2. **Phrase lists are a tripwire, not a control.** `InjectionScreen` stops the payloads in the public record and GAP-03 shows how such lists fail at the edges. The real control is classification: a MUTATE or DESTRUCTIVE tool needs an approval token regardless of what the model was told. Adopter configurations should default unknown tools to DESTRUCTIVE (the `TestEngines` fixture already does) rather than READ.

3. **Read-only SQL needs a read-only role.** `SqlReadOnlyHook` indicates read-only; a database role enforces it. The third pass added `functions: DENY_UNLISTED` (Part H, Fix 16) so an unknown function is refused rather than trusted, which narrows the indication a lot. The role is still the control that holds when the hook is wrong.

4. **State persistence.** Rate limits, approval nonces, VRP receipts and the signed-serial high-water mark live in `InMemoryStateStore` by default and are lost on restart. A restart therefore accepts a previously superseded signed manifest and resets every budget. Deployments with more than one instance, or that cannot tolerate that window, should use `aegis-state-postgres`.

5. **Schema validation skips `pattern`, `anyOf`, `oneOf`, `allOf` by design** (ReDoS avoidance). Operators who need a value shape should pin it with `enum` or `const` in the governed schema rather than rely on an upstream `pattern`.

6. **Telemetry attribute from the client.** `OtlpSpanExporter` exports `aegis.tool` from the client-supplied name for unknown tools (sanitised, 200 characters). It is bounded, but a caller can write chosen strings into the tracing backend. Export a fixed `unknown` for tools not in the catalog.

7. **Admin network separation.** Beyond Part C item 3, the Knox-hosted mode gates metrics and admin only by Knox authentication, not by group, so `AclsAuthz` must be mandatory in the topology for the `AEGIS_MCP` service. Document it as a requirement, not a recommendation.

8. **Downstream behaviour is invisible.** Pinning detects a changed definition, not changed behaviour behind an unchanged definition (postmark-mcp, September 2025). Egress governs the gateway's own connections, not what the backend process connects to. For stdio sources, run the child under a network namespace or seccomp profile; for HTTP sources, place them behind their own egress policy.

---

## Part F. Is Aegis in control of MCP security, or not?

Straight answer: Aegis controls the part of MCP security that a gateway can see, and it does so well. It does not, and cannot, control the rest. Here is the line.

### What Aegis controls completely (verified at runtime)

- Who may call which tool (authentication, groups, exposure, policy with deny-overrides).
- Whether a tool definition is the one that was reviewed (pinning, quarantine, signed catalogs, break-glass containment).
- Where a call may connect (egress allow list with full DNS-answer classification; redirects never followed).
- Whether a write or destructive call was approved by a second human for exactly these arguments and, after Fix 1, exactly these elicitation answers (approval tokens, VRP receipts, single use).
- Whether the call fits the declared shape (schema type, required, enum, const, bounds, `additionalProperties`).
- What leaves toward the model (secret redaction, binary refusal, size bound).
- Whether the record of what happened can be altered unnoticed (hash chain, checkpoints, fail-closed journaling).
- Whether one principal can exhaust the gateway (rate and budget windows, breaker, session caps).

### What Aegis narrows but cannot finish

- Prompt injection. It stops known phrases including obfuscated and base64 forms. It cannot judge intent. The backstop is that injected intent still has to pass exposure, policy, approval and egress.
- Command and SQL injection inside the backend. It constrains the argument shape and refuses obvious SQL DML. A backend that concatenates a string argument into a shell or into SQL remains its own bug; Aegis reduces the input space, it does not fix the concatenation.
- Secret leakage. Pattern redaction catches the shapes in the public record (AWS keys, PEM, JWT, bearer). A secret with no recognisable shape passes.

### What Aegis cannot see at all

- Client-side CVEs (mcp-remote CVE-2025-6514, Claude Code CVE-2026-24052, IDE auto-execution of project MCP servers). The client acts before it reaches the gateway.
- Backend-internal flaws (filesystem symlink escape CVE-2025-53109, Asana's tenant-isolation logic). Aegis sees the argument, not the backend's filesystem or database.
- Malicious backend behaviour behind an unchanged definition (postmark-mcp). A BCC inserted into the backend's own mail client never passes through Aegis.
- The backend's own outbound connections. Egress governs the gateway.

### Residual weaknesses inside Aegis itself after this review

None open. Part C listed eight after the first pass; seven are fixed with before-and-after proof and one (admin groups) was re-examined and shown to be covered by per-route authorisation. The live run in Part G then showed four places where a call the operator did not want still crossed the gateway because the configuration had no knob to stop it. Part H adds those knobs (Fixes 15 to 18) and Part I lists what is left, which is all outside the gateway.

### Overall

Of the 20 attack classes replayed before the census, Aegis blocked 16 outright and contained 2 more at runtime. The two formerly described as limited now have explicit closure states: prompt injection and free-form backend command execution are `FULL_MANAGED_PATH`, meaning the gateway controls already narrow them and complete closure requires the mandatory endpoint, execution-contract, sandbox and output controls in Part I. Side-effecting SQL functions are now denied. The 13 weaknesses found across the first two passes were all in Aegis's own controls, none were in the CVE record, and all are either fixed with regression tests or shown to be non-issues. Part G put a deliberately vulnerable MCP server behind the packaged gateway jar, and Part H closed the four gaps that run exposed, again with regression tests and a second live run. The dated NVD census in Part J maps 561 individual CVEs without an ambiguous status.

---

## Part G. Live server behind the gateway jar

The unit tests in Parts A to D call the engine in process. This part starts the real shaded jar (`aegis-gateway-2.0.0-SNAPSHOT-all.jar`, `io.github.vaquarkhan.aegis.gateway.Main`) in front of a separate process that is a vulnerable MCP server on purpose (`threat-model/e2e/vulnerable_mcp.py`). The driver (`drive.py`) sends the same `tools/call` to the server directly and then through `http://127.0.0.1:18772/mcp`. The server writes every `tools/call` it actually executes to `hits.json`. "Backend reached" below means a new hit appeared. The full table is `threat-model/e2e/RESULTS.md`.

The server really does the dangerous thing when called directly: it ran `SELECT 1; DROP TABLE users`, it requested `http://169.254.169.254/latest/meta-data/`, it returned the text `payroll-secret` from `secret.txt`, it ran `echo pwned & echo injected` and returned both lines, it returned `AKIAIOSFODNN7EXAMPLE` and a PEM block, and it executed `admin_exec` and a tool whose name contains U+200B.

Configuration used for the gateway leg: `threat-model/e2e/aegis.yaml`. Analyst token is in group `analysts` (READ only). Operator token is in group `operators`. `unlistedTools: HIDE`. `query_sql` is READ and bound to the `sql-readonly` hook. `read_file` is resource-scoped to `file:path:reports/*`. `run_shell` is DESTRUCTIVE, so it needs an approval. Catalog mode is TOFU with a 5 second refresh. Egress allows CIDR `127.0.0.0/8` and no other host. The first run, with that CIDR omitted, denied every call to the upstream itself (`EGRESS_DENIED`, loopback not allowed) and reached the backend zero times. That is the fail-closed default. The table below is the second run, after the CIDR was declared, which is what lets a local server be reached at all.

No source file changed because of this run. The classes below are the ones that produced each line.

| Attack, sent through the gateway | Backend reached | Class and method | What the response said |
|---|---|---|---|
| `SELECT 1` on `query_sql` | yes | `GovernanceEngine` allowed it; `McpProxyToolSource.ProxyExecutor.execute` proxied it | `ALLOWED`, body `executed statement: SELECT 1` |
| `SELECT 1; DROP TABLE users` | no | `SqlReadOnlyHook` (bound in `GatewayFactory` as `type: sql-readonly`) | `INVALID_INPUT` step 8, "more than one statement" |
| `SELECT pg_sleep(30)` | no | `SqlReadOnlyHook` side-effect deny list | `INVALID_INPUT`, calls `PG_SLEEP` |
| `fetch_url` to `169.254.169.254` | no | `EgressGuard` | `EGRESS_DENIED` step 5, host not on the allow list |
| `fetch_url` to `exfil.attacker.example` | no | `EgressGuard` | `EGRESS_DENIED`, host not on the allow list |
| `read_file` of `reports/ok.txt` | yes | `ResourceRule` plus `ScopeRule.firstOutOfScope` | `ALLOWED`, body `quarterly-report` |
| `read_file` of the absolute secret path | no | `ScopeRule.firstOutOfScope` | `SCOPE_DENIED`, resource out of scope |
| `read_file` of `reports/../../secret.txt` | no | `ResourceRule.hygiene` | `SCOPE_DENIED`, resource value contains `..` |
| analyst `run_shell` of `echo pwned & echo injected` | no | `GovernanceEngine` group check | `READONLY_CALLER` step 1 |
| operator `run_shell` of `echo pwned` | no | `ApprovalService` (default `requiredFor` includes `DESTRUCTIVE`) | `APPROVAL_REQUIRED` step 4, request left pending |
| `show_config` | yes, but the secret did not come back | `OutputGuard.redactAll` | body `key=[REDACTED] pem=[REDACTED]`, `redactions: 2`. Direct call returned the raw key and PEM |
| `admin_exec` (offered by the server, not in the config) | no | `GatewayFactory` `unlistedTools: HIDE`, then `GovernanceEngine` | `NOT_EXPOSED`, unknown tool |
| `loose_echo` with extra `--target-path` | yes | `SchemaValidator` accepted it because the server's own `inputSchema` says `additionalProperties: true` | `ALLOWED`, and the backend echoed the extra key. Aegis does not invent a stricter schema than the one the server published and the catalog pinned |
| `poison_note` whose description says "ignore previous instructions" | no | `InjectionScreen` on tool definitions, then `CatalogIntegrity` | startup log `TOOL_QUARANTINED`; call is `CATALOG_INTEGRITY` |
| `query_sql` plus an undeclared `note` argument | no | schema check (`additionalProperties: false` on the schema the server advertised) | `INVALID_INPUT`, unexpected property `/note` |
| tool name `list` + U+200B + `topics` | no | `ClientSupport.safeToolName` dropped it at discovery, so it was never pinned | the HTTP client cannot put U+200B in the `Mcp-Name` header either. Direct call ran the tool |
| no bearer token | no | `OwnServerPerimeter` via `AegisServer` | HTTP 401 `authentication required` |
| after `POST /flip` changed the `query_sql` description, and the 5 second refresh ran | no | `CatalogIntegrity` TOFU pin | `CATALOG_INTEGRITY`, "definition changed since it was pinned" |

So a vulnerable server behind this gateway is not the same as a safe server. The calls that still reach it are the ones the configuration allows: a read that matches policy, scope, schema and egress, and a destructive call after a second person approves it. The extra `--target-path` on `loose_echo` is the case to remember. If the upstream schema says extra properties are allowed, the gateway allows them. Pin that schema (this run did, mode TOFU) so a later loosening is quarantined the same way the `query_sql` description change was. Do not treat the gateway as a patch for a tool whose published schema is open.

Re-run:

```powershell
powershell -File threat-model\e2e\run-e2e.ps1
```

That script packages nothing. It expects `aegis-gateway\target\aegis-gateway-2.0.0-SNAPSHOT-all.jar` from `mvn -o -pl aegis-gateway -am package -DskipTests` in `aegis-mcp-gateway-20261104-2.0-review-ready`.

---

## Part H. Third pass: closing what the live run let through

The Part G table had four lines a strict operator would not accept. One call carried an argument nobody declared and the backend echoed it. One SQL function the deny list had never heard of would have run. A result carrying "ignore previous instructions" would have reached the model unscreened. A 40 character token with no recognisable prefix came back in clear. None of these was a defect in a control that existed; each was a control that did not exist. This part adds the four controls, each with a regression test that shows the old behaviour and the new one, and re-runs the live server behind the rebuilt jar.

Tests: `ThreatModelClosureEngineTest` (engine), `ThreatModelClosureConfigTest` (configuration keys end to end against a `TestMcpServer`), and `ThreatModelCveSqlTest.close02` (SQL hook). Verdict ids `CLOSE-01` to `CLOSE-04b` in `evidence/verdicts.md`. Full regression after the changes, RAT check on: engine 158, mcp 54, config 75, security 25, gateway 35, `BUILD SUCCESS` (`evidence/maven-pass3-all-modules.log`).

### Fix 15. Operator input schema overlay and `strictProperties` (CLOSE-01)

**Classes:** `io.github.vaquarkhan.aegis.engine.catalog.GovernedTool`, `io.github.vaquarkhan.aegis.engine.GovernanceEngine` (step 2), `io.github.vaquarkhan.aegis.config.GatewayFactory` (`ToolSettings`, `toolSettings`, `SourceBuild.bind`, `source`)

**The issue.** For a proxied (`type: mcp`) tool the only schema Aegis enforced was the one the server published. The server in Part G published `additionalProperties: true` for `loose_echo`, so `--target-path` was forwarded and echoed (CVE-2026-44968 class). The operator had no key to say "forward only these properties", short of editing the server.

**Change in `GovernedTool`.** An eleventh component, `operatorSchema`, with a compatibility constructor so existing callers compile unchanged:

```java
public record GovernedTool(
        String exposedName, String sourceId, ToolDescriptor descriptor, ToolClass toolClass,
        List<ResourceRule> resources, DryRunSpec dryRun, Set<String> allowedGroups, Set<String> actionScopes,
        ToolExecutor executor, long timeoutMillis,
        Map<String, Object> operatorSchema) {

    /** The schema a client is told about and that the call must satisfy. */
    public Map<String, Object> effectiveInputSchema() {
        return operatorSchema != null ? operatorSchema : descriptor.inputSchema();
    }

    /** An operator schema that forbids every property the upstream schema does not declare. */
    public static Map<String, Object> strictProperties(Map<String, Object> upstreamSchema, Map<String, Object> operatorSchema) {
        Map<String, Object> base = operatorSchema != null ? operatorSchema : upstreamSchema;
        Map<String, Object> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        out.putIfAbsent("type", "object");
        Map<String, Object> props = JsonValues.object(out, "properties");
        if (props == null) {
            Map<String, Object> upstreamProps = JsonValues.object(upstreamSchema, "properties");
            out.put("properties", upstreamProps == null ? Map.of() : upstreamProps);
        }
        out.put("additionalProperties", false);
        return out;
    }
```

`declaredProperties()` now unions both schemas, so the control-token split still recognises every declared name. `advertise()` publishes the operator schema when one exists, so `tools/list` shows the contract the gateway enforces rather than the looser one upstream published.

**Change in `GovernanceEngine.preflight`, step 2.** Both schemas must hold:

```java
String schemaError = SchemaValidator.validate(tool.descriptor().inputSchema(), args);
if (schemaError != null) {
    return Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE, "arguments do not match the tool schema: " + schemaError);
}
if (tool.operatorSchema() != null) {
    String operatorError = SchemaValidator.validate(tool.operatorSchema(), args);
    if (operatorError != null) {
        return Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE,
                "arguments do not match the operator schema: " + operatorError);
    }
}
```

**Change in `GatewayFactory`.** `ToolSettings` gained `inputSchema`. `toolSettings()` reads it for proxied and plugin tools only (for an `http` source the configured schema is the tool definition itself, so there is nothing to overlay), refuses a non-object, and sanity-checks it with `SchemaValidator`. A new source key `strictProperties: true` sets `SourceBuild.strictProperties`, and `bind()` applies it to every tool of the source, listed or not:

```java
Map<String, Object> operatorSchema = s == null ? null : s.inputSchema();
if (strictProperties) {
    operatorSchema = GovernedTool.strictProperties(st.descriptor().inputSchema(), operatorSchema);
}
return Optional.of(new GovernedTool(namePrefix + upstream, id, st.descriptor(), cls, ..., operatorSchema));
```

**Configuration:**

```yaml
sources:
  - id: vulnerable
    type: mcp
    strictProperties: true            # every tool: undeclared properties refused
    tools:
      loose_echo:
        class: READ
        inputSchema:                  # or per tool, tighter than upstream
          type: object
          additionalProperties: false
          properties: {note: {type: string, maxLength: 64}}
```

**Proof.** `close01_openUpstreamSchema_extraPropertyReachesBackendWithoutOperatorSchema` is the baseline: with no overlay the extra key is forwarded (`executor.calls.get(0).arguments()` contains `--target-path`). `close01_operatorSchemaOverlay_refusesUndeclaredProperty`: `INVALID_INPUT`, reason names the operator schema and the property, `executor.calls` is empty, a 21 character `note` is "string too long", `tools/list` advertises `additionalProperties: false`. `close01b` covers `strictProperties()` including a server that publishes no properties at all (empty allow list). `ThreatModelClosureConfigTest.close01` drives both keys through YAML against a live `TestMcpServer` and shows a tool on the same source without an overlay keeps its open schema until `strictProperties` is set. Live run: `loose_echo` with `--target-path` is now `INVALID_INPUT (step 2). arguments do not match the operator schema: unexpected property /--target-path`, backend not reached.

### Fix 16. `sql-readonly` function allow list (CLOSE-02)

**Class:** `io.github.vaquarkhan.aegis.config.hooks.SqlReadOnlyHook`; wiring in `GatewayFactory.sqlReadOnlyHook`

**The issue.** Fix 11 added a deny list of about 90 side-effecting functions. A deny list cannot know a user-defined function, a vendor function added last month, or `pg_catalog.pg_read_server_files_v2`. `SELECT write_to_s3(name, 'bucket') FROM customers` passed the hook and would have run (the formerly limited side-effecting-function row in THREAT-MODEL 4.1).

**Change.** A second mode. `functions: DENY_UNLISTED` turns the question around: every `name(` in the statement must be on an allow list, which is `DEFAULT_ALLOWED_FUNCTIONS` (about 130 aggregate, string, numeric, date, window, hash and JSON built-ins, none of which write, read files, connect or sleep) plus the operator's `allowFunctions`. The deny list still applies, and the allow list cannot re-admit anything on it.

```java
public SqlReadOnlyHook(String name, Set<String> arguments, int maxChars, Set<String> allowedFunctions) {
    ...
    if (allowedFunctions != null) {
        upper = new HashSet<>();
        for (String f : allowedFunctions) {
            upper.add(f.toUpperCase(Locale.ROOT));
        }
        upper.removeAll(SIDE_EFFECT_FUNCTIONS);   // the two controls stack
        upper = Set.copyOf(upper);
    }
    this.allowedFunctions = upper;
}

public static String firstProblem(String sql, int maxChars, Set<String> allowedFunctions) {
    ... existing single-statement, first-keyword, WRITING_KEYWORDS and SIDE_EFFECT_FUNCTIONS checks ...
    if (allowedFunctions != null) {
        for (String f : calledFunctions(bare)) {
            if (!allowedFunctions.contains(f)) {
                return "it calls '" + f + "', which is not on the allowed function list (functions: DENY_UNLISTED)";
            }
        }
    }
    return null;
}

/** Upper-case names of every name( call, excluding SQL words that take a parenthesis without being a call. */
static List<String> calledFunctions(String bare) {
    // scans identifier (with dots) followed by optional whitespace and '('; reports the last dotted segment;
    // skips NOT_FUNCTIONS: IN, EXISTS, VALUES, AND, OR, NOT, ON, OVER, AS, FROM, WHERE, JOIN, SELECT, HAVING, ...
}
```

`calledFunctions` runs on the bare statement, after `stripCommentsAndQuoted`, so a function name inside a string literal is data, as before.

**Configuration:**

```yaml
hooks:
  - type: sql-readonly
    methods: [query_sql]
    settings:
      arguments: [sql]
      functions: DENY_UNLISTED          # default is DENY_LISTED, the Fix 11 behaviour
      allowFunctions: [my_safe_fn]      # added to DEFAULT_ALLOWED_FUNCTIONS
      # useDefaultAllowedFunctions: false   # to start from an empty list
```

`allowFunctions` without the mode is a configuration error, so a half-written setting cannot read like protection.

**Proof.** `close02_unknownFunction_passesDenyListButIsRefusedByAllowList`: the UDF and the qualified vendor name pass `firstProblem(sql, 8192)` (baseline) and are refused with the allow list; five ordinary analytics statements with aggregates, `CAST`, `OVER (PARTITION BY ...)`, `IN (...)`, `EXISTS (...)` and `WITH ... AS (...)` pass; a hook built with `PG_SLEEP` on its allow list still denies `SELECT pg_sleep(30)`. `ThreatModelClosureConfigTest.close02` does the same through YAML. Live run: `SELECT write_to_s3(name, 'bucket') FROM customers` is `INVALID_INPUT (step 8) ... it calls 'WRITE_TO_S3', which is not on the allowed function list`; `SELECT count(*), lower(region) FROM orders GROUP BY region` still reaches the server.

### Fix 17. Injection screen over tool results (CLOSE-03)

**Classes:** `io.github.vaquarkhan.aegis.engine.control.OutputGuard`, `io.github.vaquarkhan.aegis.engine.GovernanceEngine` (step 10), `GatewayFactory` (`screening.results`)

**The issue.** Part E item 1. The phrase screen covered arguments, tool definitions, resources and prompts. A tool result is model-controlled text in exactly the same way, and a hostile or compromised backend can put "ignore previous instructions" in it. Nothing read it.

**Change in `OutputGuard`.** A fifth constructor argument, `InjectionScreen resultScreen`, and a fifth component on `Guarded`, `injectionMatch` (four-argument compatibility constructor kept). The screen runs after redaction over every string the client would receive:

```java
public OutputGuard(int maxBytes, List<String> redactionPatterns, boolean allowBinary,
                   InjectionScreen resultScreen, boolean redactHighEntropy) { ... }

public record Guarded(ToolResult result, int redactions, boolean truncated, boolean binaryRefused, String injectionMatch) { ... }

// in apply(), after the binary check:
String injection = resultScreen == null ? null : resultScreen.firstMatch(body).orElse(null);
...
return new Guarded(new ToolResult(body), count[0], truncated, false, injection);
```

**Change in `GovernanceEngine.govern`, step 10.** The backend already ran, so this is a withhold, not a deny before execution, and the call is recorded with `reachedBackend: true`:

```java
if (guarded.injectionMatch() != null) {
    t.governance.put("resultScreeningMatch", guarded.injectionMatch());
    if (settings.outputGuard().resultScreenMode() == ControlMode.ENFORCE) {
        return fail(t, DenyCodes.PROMPT_INJECTION,
                "the backend result matches an injection pattern and was withheld (screening.results)", true);
    }
}
```

`record()` writes `resultScreeningMatch` into every audit record that has it, so AUDIT mode leaves a trail an operator can read before switching to ENFORCE.

**Configuration:** `screening.results: ENFORCE | AUDIT | OFF`. The default is AUDIT, on purpose: withholding a legitimate result after the backend has already acted is the costlier mistake, so the operator is asked to look at the journal first. A typo is a configuration error.

**Proof.** `close03_injectionPhraseInToolResult_isWithheldInEnforceAndJournaledInAudit`: baseline guard passes the phrase through; ENFORCE returns `PROMPT_INJECTION`, `executor.calls` grew by one (the backend was called), the result body does not contain `attacker@example`; AUDIT allows the call and the last journal record carries `resultScreeningMatch`. `ThreatModelClosureConfigTest.close03` checks the default and the typo. Live run: `poison_result` is `PROMPT_INJECTION (step 10). the backend result matches an injection pattern and was withheld (screening.results)`; the direct call returned the instruction in clear.

### Fix 18. Opt-in high-entropy redaction (CLOSE-04)

**Class:** `io.github.vaquarkhan.aegis.engine.control.OutputGuard`; wiring `output.redactHighEntropy`

**The issue.** The default redactions match shapes with a prefix: `AKIA`, `eyJ`, `Bearer `, `-----BEGIN`. A token with no prefix (most internal service tokens, many vendor API keys) came back in clear. Part G showed `service_token=q7Fm2Zp...` leaving untouched.

**Change.** When `redactHighEntropy` is on, `redact()` also scans runs matching `[A-Za-z0-9+/_-]{32,}={0,2}` and replaces those that look like key material. The `=` is not in the body class so a `key=value` label stays readable. "Looks like key material" is three tests, all needed:

```java
static final Pattern HIGH_ENTROPY_TOKEN = Pattern.compile("[A-Za-z0-9+/_-]{32,}={0,2}");
static final double MIN_ENTROPY_BITS = 4.0;
static final double MIN_ENTROPY_RATIO = 0.85;

public static boolean looksRandom(String run) {
    // upper case, lower case and a digit must all be present (a lower-case hex digest fails here)
    ... count character frequencies, skipping '=' padding ...
    double bits = Shannon entropy per character;
    double max = log2(min(n, 64));
    return bits >= MIN_ENTROPY_BITS && bits >= MIN_ENTROPY_RATIO * max;
}
```

The ratio is what makes it usable. A 61 character camel-case identifier reaches 4.5 bits from its length alone, but only 76% of its possible maximum; random base64 of 32 to 64 characters sits at 88% to 100%. Measured on the test strings: the 40 character random token 5.32 bits, ratio 1.00; AWS secret-key shape 4.66, 0.88; GitHub token shape 4.82, 0.91; the camel-case identifier 4.52, 0.76; a shorter one 4.15, 0.75; a SHA-256 hex digest excluded by the case rule. It is a heuristic, which is why it is off by default and why the verdict is MITIGATED, not FIXED. A secret made only of lower-case letters and digits, or shorter than 32 characters, still passes.

**Proof.** `close04_highEntropyRedaction_catchesPrefixlessSecretsAndLeavesProseAlone` and `ThreatModelClosureConfigTest.close04`. Live run: `leak_token` returns `service_token=[REDACTED]` with `redactions: 1`; the direct call returned the token.

### Live run after the four fixes

Same server, same driver, hardened `threat-model/e2e/aegis.yaml` (`strictProperties: true`, the `loose_echo` overlay, `screening.results: ENFORCE`, `output.redactHighEntropy: true`, `functions: DENY_UNLISTED`). Full table: `evidence/e2e-pass3-RESULTS.md`. Every Part G line that was already denied is unchanged. The lines that changed:

| Call through the gateway | Part G | After Part H | Class |
|---|---|---|---|
| `loose_echo` with `--target-path` | reached the server, echoed the key | `INVALID_INPUT` operator schema, server not reached | `GovernedTool.operatorSchema`, `GovernanceEngine` step 2 |
| `SELECT write_to_s3(name, 'bucket') FROM customers` | would have reached the server | `INVALID_INPUT`, `WRITE_TO_S3` not on the allowed list | `SqlReadOnlyHook.calledFunctions` |
| `poison_result` (clean description, poisoned result) | result reached the client | `PROMPT_INJECTION` step 10, result withheld | `OutputGuard` result screen, `GovernanceEngine` step 10 |
| `leak_token` (40 character prefix-less token) | token in clear | `service_token=[REDACTED]` | `OutputGuard.looksRandom` |
| `SELECT count(*), lower(region) FROM orders GROUP BY region` | (not in Part G) | reached the server, as a read should | allow list accepted `COUNT`, `LOWER` |

### New configuration keys from this pass

| Key | Default | Meaning |
|---|---|---|
| `sources[].strictProperties` | `false` | Refuse any argument property the upstream or operator schema does not declare, on every tool of the source |
| `sources[].tools.<name>.inputSchema` | none | Operator schema a proxied tool's arguments must also satisfy; advertised in `tools/list` |
| `screening.results` | `AUDIT` | Phrase screen over tool results: `ENFORCE` withholds, `AUDIT` journals, `OFF` disables |
| `output.redactHighEntropy` | `false` | Also redact 32+ character mixed-alphabet runs at 85% or more of maximum entropy |
| `hooks[].settings.functions` (sql-readonly) | `DENY_LISTED` | `DENY_UNLISTED` refuses every function call not on the allow list |
| `hooks[].settings.allowFunctions` | `[]` | Added to `DEFAULT_ALLOWED_FUNCTIONS` under `DENY_UNLISTED` |
| `hooks[].settings.useDefaultAllowedFunctions` | `true` | Set false to start from an empty allow list |

None of the defaults changed behaviour for an existing configuration. The hardened profile in `threat-model/e2e/aegis.yaml` turns all of them on.

---

## Part I. What cannot be closed by the gateway process alone, and how to bring it under control

You asked for complete control with no vulnerability passing through the gateway. After Part H that is the state for traffic the gateway process sees. The items below run before the request exists, after the response has left, or in a process the gateway does not own. They cannot be closed by adding another `if` inside `GovernanceEngine`, but most can be brought under an expanded Aegis control plane.

The full design is [`COMPLETE-CONTROL-EXPLORATION.md`](COMPLETE-CONTROL-EXPLORATION.md). It adds a managed endpoint, artifact admission, a sandboxed server runtime, default-deny backend networking, call-scoped data handles, typed execution contracts, output declassification and live revocation. Under that boundary, the defensible claim is: **no unadmitted request and no raw untrusted result crosses the Aegis-controlled path**.

| Item | Why the gateway cannot close it | Best control | What Aegis contributes |
|---|---|---|---|
| CVE-2025-6514 mcp-remote command injection; CVE-2026-24052 Claude Code WebFetch; IDE auto-execution of project-level MCP servers (Cursor, Gemini CLI, Copilot, Amazon Q advisories) | The bug executes inside the client, before any request is sent | Patch the client; pin client versions in the IDE policy; forbid project-level MCP config files in repositories; have clients reach MCP servers only through the gateway URL so a compromised client still meets authentication, policy and egress | Every call that does arrive is governed; a client that has been taken over still cannot call an unlisted tool, an out-of-scope resource, or a write without approval |
| CVE-2025-53109 filesystem server symlink escape, Asana tenant-isolation logic, and any backend-internal flaw | Aegis sees the argument string; the symlink is resolved on the server's disk, the tenant check runs in the server's code | Patch the server; run it with least privilege (read-only mount, dedicated user, no network); resource scopes (`file:path:reports/*`) refuse anything the operator has not allowed by name | Scope and `..` hygiene stop every traversal that is visible in the argument; `strictProperties` stops a hidden argument from reaching the server |
| postmark-mcp class: malicious behaviour behind an unchanged definition | Pinning detects a changed definition; a BCC added inside the server's own mail client never crosses the gateway | Supply-chain review and signed server catalogs (`REQUIRE_PINS` plus `serverCatalog`) so only reviewed servers start; vendor SBOM review; run the server in a sandbox with its own egress policy | `--server-catalog-create` and the break-glass containment order give an operator a reviewed, signed list and a fast kill switch |
| The backend's own outbound connections (exfiltration from inside the server process) | Egress governs the gateway's connections; the server process has its own socket | Network policy around the server: Kubernetes NetworkPolicy, security groups, or for stdio children a network namespace or seccomp profile with no network | `fetch_url`-style tools are still bounded because the URL argument is checked before the call; `screening.results` and redaction limit what a leak carries back |
| Shell or SQL concatenation inside the server after a human approves | The approver saw the exact arguments and said yes; the server then runs them | Fix the concatenation in the server; classify such tools `DESTRUCTIVE` so every call needs a dry-run receipt and an approval; for SQL, a read-only database role | Schema type, `strictProperties`, resource hygiene and the function allow list shrink the input space; approval evidence shows the approver exactly what will run, including MRTR answers (Fix 1) |
| Prompt injection in new wording | A phrase list cannot judge intent | Keep the model's authority low: unknown tools default `DESTRUCTIVE`, writes need approval, results screened; review `resultScreeningMatch` and `screeningMatch` in the journal and extend `screening.phrases` from what you see | Arguments, definitions, content and now results are screened; injected intent still has to pass exposure, policy, approval and egress |
| Secrets with no recognisable shape and no entropy signal | A short or lower-case-only token looks like a word | Keep secrets out of tool results at the source (the server should return references, not credentials); add `output.redact` patterns for your own token formats | Default shapes, PEM scanner and the entropy rule cover the public record; anything you can name as a regex is one line of configuration away |
| Apache Kafka, Flink, Doris, Pinot and other server CVEs that are not MCP calls | Traffic that does not pass through the gateway is not governed | Patch the servers; put every MCP path to them behind the gateway and close the direct path with network policy | The MCP-level classes (stacked SQL, side-effect functions, SSRF, traversal) are blocked for every call that does go through |

So the honest statement for the report is: after Part H, no attack in this review that travels through the gateway reaches the backend under the hardened profile. The expanded design in `COMPLETE-CONTROL-EXPLORATION.md` brings most events outside the process into a managed Aegis boundary. Its strongest mode refuses free-form execution, isolates every server, blocks direct routes, admits only signed and policy-clean artifacts, and releases arbitrary output only as an opaque handle. The remaining limit is epistemic: signatures, scans and sandboxes cannot prove that admitted software contains no unknown defect. The claim is complete control of the managed path, not "100% no vulnerability" in all software.

---

## Part J. Dated public MCP CVE census

The original review grouped public disclosures into attack classes and replayed representative exploits. It did not list every CVE row. I corrected that gap with a repeatable NVD census under [`cve-census/`](cve-census/).

Snapshot time: `2026-11-07T06:02:03Z`. Seven NVD CVE API 2.0 keyword queries were used: `Model Context Protocol`, `MCP server`, `MCP client`, `MCP SDK`, `MCP tool`, `mcp-remote`, and `MCP`. The exact-phrase query returned 161 records. The union, after excluding unrelated expansions of the MCP acronym and deduplicating by CVE ID, contains **561 unique CVEs**, all published in 2025 or 2026. All seven raw JSON responses are preserved. This is a dated census, not an eternal claim that no record can be published or amended later.

| Closure state | CVEs | Meaning |
|---|---:|---|
| `FULL_GATEWAY_PATH` | 22 | Current hardened Aegis closes the identified ingress path when it is the only reachable MCP front door and direct server routes are blocked |
| `FULL_MANAGED_PATH` | 297 | Full closure requires the gateway plus the endpoint, artifact-admission, sandbox, network, credential/resource broker, execution-contract or output controls named in the row |
| `EXTERNAL_REQUIRED` | 242 | The action occurs before or outside the gateway, or public metadata is insufficient for a safe automatic control claim; the row names the required patch, endpoint policy, route closure or quarantine |
| Ambiguous status | 0 | No row is left with a vague limited verdict |

Files:

- [`cve-census/MCP-CVE-CENSUS.csv`](cve-census/MCP-CVE-CENSUS.csv): every CVE ID, date, NVD status, vendor, product, affected version range, CWE, CVSS, attack classes, execution boundary, closure state, current gateway protection, external/expanded control, why it is external, query membership and NVD link.
- [`cve-census/MCP-CVE-CENSUS.md`](cve-census/MCP-CVE-CENSUS.md): all 561 linked rows in reviewable form plus query and class summaries.
- `cve-census/census-metadata.json`: query receipt and CSV SHA-256 `c9f1dd5edee395ffe2c4a278664e83f0fad32f8573662ad1273238d2c38f32f4`.
- `cve-census/raw/*.json`: exact NVD responses used by this snapshot.
- `cve-census/build_census.py`: explicit relevance filter, normalization rules and conservative strongest-control mapping. Set `CENSUS_USE_CACHE=1` to regenerate from the retained snapshots.

The census is a control map, not 561 exploit reproductions. The 44 runtime verdicts in `evidence/verdicts.md` prove representative mechanisms. A row marked `FULL_MANAGED_PATH` does not claim that the control-plane design is already implemented. Until its named component exists and its negative test passes, the source remains quarantined. A row marked `EXTERNAL_REQUIRED` never becomes an Aegis code fix by relabeling it; the external control must provide evidence, and Aegis admission must verify that evidence.

The admission rule is fail closed: if a CVE cannot be safely mapped, if its required patch is absent, if a scan receipt is stale, or if the external control cannot be proven, the artifact or endpoint does not enter the managed path.

---

## Part K. Complete-control implementation (fourth pass)

Part I said complete control needs more than another `if` in the engine. This pass implemented the parts that live in Aegis itself. Everything below is switched on by `completeControl.enabled: true`. Startup refuses the configuration when any part is missing.

### K.0 What complete control now enforces at startup

| Requirement | Class and method | Refusal message (abridged) |
| --- | --- | --- |
| Host-anchored review key and a signed server catalog | `GatewayFactory.requiredCatalogKey` | `requires host-side AEGIS_REQUIRE_SERVER_CATALOG_KEY` |
| Host-anchored admission key for attestations (new, K1) | `GatewayFactory.requiredAttestationKey` | `requires host-side AEGIS_REQUIRE_ATTESTATION_KEY` |
| `unlistedTools: HIDE`, `strictProperties: true` on every source | `GatewayFactory.source` | `... is required when completeControl.enabled is true` |
| Every tool has an `executionContract` that is not UNKNOWN or FREE_FORM | `ExecutionContract.allowedInCompleteControl` | `refuses execution kind FREE_FORM` |
| Every tool has `outputPolicy` STRUCTURED_ALLOWLIST or HANDLE_ONLY | `OutputPolicy.allowedInCompleteControl` | `requires outputPolicy` |
| Stdio servers start through a sandbox prefix with admitted command artifacts | `ProcessLauncher.prefixed`, `GatewayFactory.requireCommandArtifacts` | `commandPrefix is required` |
| Remote sources carry a signed, unexpired (30 days max) deployment attestation | `GatewayFactory.deploymentAttestation` | `signature does not verify` |
| Plugin JARs (sources, hooks, state, secrets, audit sinks) are hash-admitted before `Provider.get()` | `GatewayFactory.provider(..., admitted, true)`, `codeSourceAdmitted` | `unknown or unadmitted ... type` |
| `screening.results` defaults to ENFORCE and may not be OFF (new) | `GatewayFactory.build` screening section | `must not be OFF when completeControl.enabled is true` |
| `output.redactPii` defaults on (new) | `OutputGuard.PII_REDACTIONS` | none (default) |
| Own host: `adminGroups`, `adminListen`, `requireJti`, `requireRevocation` (new) | `AegisServer.checkCompleteControlPerimeter` | `completeControl.enabled requires host.adminListen ...` |
| Helm: image digest, NetworkPolicy, runtimeClassName, non-root, read-only root, no escalation, seccomp | `charts/aegis/templates/_helpers.tpl` | chart `fail` |

### K1. Deployment attestation is verified cryptographically

Before: `deploymentAttestation` was shape-checked only (digest format, verifier identity, RFC 3339 expiry, 30-day bound). A configuration editor could write any digest.

After: the host names the deployment admission verifier's Ed25519 public key in `AEGIS_REQUIRE_ATTESTATION_KEY` (or JVM property `aegis.requireAttestationKey`), never in the config file. Each attestation needs `signature`. That is a base64 Ed25519 signature over this statement:

```java
// GatewayFactory.attestationStatement
"aegis-deployment-attestation-v1\n" + sourceId + "\n" + subjectDigest + "\n" + evidenceDigest + "\n"
        + verifier + "\n" + validUntil + "\n"
```

```java
// GatewayFactory.deploymentAttestation (new tail)
if (attestationKey != null) {
    if (signature == null || signature.isBlank()) {
        throw a.error("signature", "is required: " + REQUIRED_ATTESTATION_KEY + " names the admission verifier key");
    }
    byte[] statement = attestationStatement(source.requiredString("id"), subject, evidence, verifier, expiresText);
    if (!SignedCatalogManifest.verifySignature(statement, signature, attestationKey)) {
        throw a.error("signature", "does not verify with the admission key named by " + REQUIRED_ATTESTATION_KEY);
    }
}
```

The review key (who may add a server) and the admission key (which workload digest is running) are different keys. One person with one key cannot both review a server in and attest a different image.

Proof: `CompleteControlConfigTest.attestationSignatureIsVerifiedWithTheHostAnchoredAdmissionKey` (verdict `CC-K1`) checks four cases:

- No host key: startup refused.
- Tampered subject digest, even though the reviewer signed the block containing it: refused.
- Signature by a rogue key: refused.
- Missing signature: refused.

### K2. HANDLE_ONLY results have a take-once, separated release path

Before: HANDLE_ONLY returned `result:<sha256>` to the model and discarded the data. A human had no way to see what was withheld.

After: with `completeControl.handleRetentionSeconds` (1 to 86400; 0, the default, keeps the old behaviour) the engine stores the **redacted** original in the governance state store. `ResultHandleStore` is new. `GovernanceEngine.call` stores the entry after the release step:

```java
// GovernanceEngine, after OutputPolicy.release
ResultHandleStore handles = resultHandles;
if (handles != null && tool.outputPolicy().mode() == OutputPolicy.Mode.HANDLE_ONLY && !tokens.dryRun()) {
    OutputGuard.Guarded original = settings.outputGuard().apply(result);   // redact before storing
    boolean kept = !original.binaryRefused() && handles.put(released.handle(), tool.exposedName(),
            t.requestId, t.caller, original.result().body());
    t.governance.put("outputHandleRetained", kept);
}
```

Release goes through the admin route `POST adminPath/results/{handle}/release` (`AdminApi.releaseResult`). The route enforces these rules:

- Only `access.resultReleaseGroups` may release.
- The releaser must be non-delegated.
- The identity that produced the handle can never release it, so the agent's own identity cannot read what the policy withheld from it.
- The intent is journaled before the data leaves the store.
- Release is take-once (`StateStore.take`).

Proof:

- `CompleteControlConfigTest.handleOnlyResultsAreRetainedRedactedAndReleasedOnceToAnotherIdentity` (verdict `CC-K2`): the model sees no data, the stored copy has the email redacted, the producer gets 403, a second release gets 404, and the journal holds `RESULT_RELEASE_INTENT` and `RESULT_RELEASE_REFUSED`.
- `AdminApiGapTest.handleReleaseIsTakeOnceNeverToTheProducerAndJournaled` covers every refusal path, including journal down (503).

### K3. Pending complete-control items: what is still not implemented, and why

These items from the complete-control design are **not implemented** in this tree. Each one is either an external system Aegis must verify (not be), or needs a runtime this Windows host does not have to prove it.

| Item | Status | Why not in this pass | What is needed |
| --- | --- | --- | --- |
| ResourceBroker (opaque file handles instead of paths) | Not implemented | Needs a broker service that owns the files; Aegis would hand out handles only | A broker MCP server behind Aegis; `FILE_BROKERED` contract already exists to declare it |
| Tenant-scoped CredentialLease | Not implemented | Requires a secrets backend that issues short leases per tenant (Vault dynamic secrets) | Vault dynamic secrets plugin over `CredentialProvider`; token exchange already covers per-caller tokens |
| Taint propagation with signed provenance tokens | Not implemented | Needs client and model cooperation to carry the token across turns; a gateway alone cannot see the next turn | Client SDK support; until then HANDLE_ONLY keeps untrusted text out of context |
| Live signed catalog reload with break-glass build-then-swap | Not implemented | Today a signed catalog change needs a restart (fails safe); hot swap needs a two-phase build and catalog swap in `Gateway` | A reload command that builds a second gateway and swaps the handler |
| ExecutionContract bound to an adapter identity and digest | Partly: the contract is in `GovernedTool.policyDigest` and the action binding | Binding the operation id to an admitted adapter JAR digest needs adapter manifests | Adapter manifest format plus `ArtifactAdmission` role `adapter` |
| Real sandbox runtime proof (gVisor, Linux namespaces, negative probes) | Launcher implemented, runtime not proven | This host is Windows without gVisor or Kubernetes | Run `CompleteControlStdioTest` with a `runsc` prefix on Linux |
| Managed backend workloads with NetworkPolicy | Chart gates implemented, not rendered | Helm is not installed here | `helm template` with `ci/complete-control-values.yaml` in CI |
| Endpoint Guard (stop clients bypassing Aegis) | External | Endpoint OS control (WDAC, MDM, IDE policy) | Endpoint management; Aegis cannot enforce on the laptop |
| SBOM, CVE receipt and Sigstore verification of server images | External, now verified by K1 | The admission system scans and signs; Aegis verifies its signed statement | An admission controller that signs `attestationStatement` |

---

## Part L. Gaps found by comparing Aegis with ContextForge and other MCP gateways (fourth pass)

The comparison (`AEGIS-VS-CONTEXTFORGE.md`) found seven controls that other gateways ship and Aegis lacked. All seven are fixed and tested here. OWASP coverage after these fixes is in `OWASP-TOP10-COVERAGE.md`.

### L1. DNS rebinding between the egress check and the connection

Before: `EgressGuard.check` resolved the host and checked every answer. `HttpMcpClient.exchange`, `HttpToolExecutor.execute`, token exchange, OPA, remote hooks, OTLP, Vault and A2A then called the JDK `HttpClient`, which resolved the name **again** at connect time. An attacker controlling DNS for an allowed name could answer a public IP to the check and `169.254.169.254` to the connection. Java 17 `HttpClient` has no per-client resolver hook.

After:

- `EgressGuard.pin(URI)` resolves once and returns the checked answers (`EgressGuard.Pin`). `check` now delegates to it.
- New `PinnedHttp` (`aegis-mcp/.../http/PinnedHttp.java`) is a bounded HTTP/1.1 transport. It asks the pinner for the answers, dials a socket to one of them, and for HTTPS wraps it with `SSLSocketFactory.createSocket(socket, host, port, true)`. Endpoint identification is `HTTPS` and SNI is the original name, so the certificate must match the approved name.
- It keeps the `BoundedHttp` guarantees:
  - One total deadline, recomputed before every read.
  - A byte bound.
  - A 64 KiB head bound.
  - No redirects followed.
  - `Connection: close`.
- It refuses malformed heads: folded headers, conflicting `Content-Length`, bad chunk sizes and `101` upgrades.
- `EgressBinding` binds the gateway guard late, because sources are built before the guard exists. Until bound, every pin is denied.
- `BuildContext.outbound(Duration)` gives providers the same pinned transport. `OutboundHttp` is the small interface both transports implement.
- Wired into: `HttpMcpClient` (new constructor), `HttpToolExecutor.over`, `TokenExchangeCredentials`, `OpaPolicy`, `RemoteHook`, `OtlpSpanExporter`, `VaultKvSecretProvider` (with the Vault CA context) and `A2aToolSourceFactory.create(id, settings, context)`.

```java
// PinnedHttp.send (core)
EgressGuard.Pin pin = pinner.apply(uri);
if (pin == null || !pin.allowed() || pin.addresses().isEmpty()) {
    throw new EgressDeniedException(pin == null ? "no egress decision" : String.valueOf(pin.denied()));
}
Socket socket = connect(pin.addresses(), port, deadline);          // no second DNS lookup
if (https) {
    socket = tls(socket, pin.host(), port, deadline);             // SNI + hostname verification on the name
}
```

Behaviour change to note: discovery calls (MCP `tools/list`, A2A agent card) now pass the egress control too. Before, only tool calls did. A source whose host is not allowed now fails at startup instead of failing on the first call. `A2aToolSourceTest` was updated to expect that.

Proof:

- `PinnedHttpTest` (GAP-L1a to L1d):
  - The name `pinned.aegis.test` is not in any DNS, so success proves the dialled address came from the pin.
  - A rebinding answer at connect time is refused with 0 accepted sockets.
  - A trickling body cannot extend the deadline.
  - TLS verifies against the approved name, and fails for another name on the same IP.
- `DnsRebindingConfigTest` (GAP-L1e) runs a full `GatewayFactory`-built gateway. The policy step saw `127.0.0.1` and the connect step saw `169.254.169.254`. The call was refused and backend calls stayed at 1.

Not covered: JWKS fetching in `JwtVerifier` still uses the JDK client. No egress check exists for the identity provider URL, so there is no check to race. The JWKS URL is operator-set and HTTPS-only.

### L2. JWT token ids and gateway-side revocation

Before: `JwtVerifier.verifyWithScopes` ignored `jti`. A stolen token stayed valid until `exp`.

After:

- `JwtVerifier.TokenIdPolicy(requireJti, requireRevocation)` is set from `host.identity.jwt.requireJti` and `requireRevocation`. A `jti` must be a string of 1 to 256 characters.
- New `TokenRevocations` (engine) keeps per-token entries (issuer + jti, until expiry) and per-subject cutoffs (issuer + sub, refusing tokens with `iat` at or before the cutoff, and tokens with no `iat`). Entries live in the governance `StateStore`, so every replica sharing the store sees them, with keys stored as SHA-256 digests.
- `AegisServer` binds the gateway's list into the verifier (`JwtVerifier.bindRevocations`, bind once).
- With `requireRevocation` and no list bound, every token is refused. That is fail closed. ContextForge's security document states its auth paths fail open when the revocation store is unavailable.
- New admin routes: `POST adminPath/revocations/token/{jti}` and `/revocations/subject/{sub}`. They are open only to `access.revokerGroups` (empty means closed) and to non-delegated identities. The intent is journaled first. Retention is `access.revocationRetentionSeconds` (60 s to 30 days, default 24 h).

```java
// JwtVerifier.verifyWithScopes (new checks after sub)
if (idPolicy.requireJti() && jti == null) {
    throw new IllegalArgumentException("token has no jti and host.identity.jwt.requireJti is true");
}
TokenRevocations list = revocations;
if (list == null && idPolicy.requireRevocation()) {
    throw new IllegalArgumentException("revocation is required but no revocation list is bound");
}
if (list != null) {
    String revoked = list.check(s.issuer(), jti, sub, iat);
    if (revoked != null) {
        throw new IllegalArgumentException(revoked);
    }
}
```

Proof:

- `ThreatModelGapJwtTest` (GAP-L2a, L2b).
- `ThreatModelGapServerTest.revokedTokensAreRefusedAtTheFrontDoor` (GAP-L2c) over real HTTP: 200 before revocation, 401 after, the other token still works, and subject revocation refuses it too. The journal holds `TOKEN_REVOKE_INTENT` and `TOKEN_REVOKE_RESULT`.
- `AdminApiGapTest.revocationRoutesAreGatedJournaledAndValidated` covers the refusal paths, including journal down: 503 and nothing revoked.

### L3. Pre-authentication limits, lockout and team quotas

Before:

- `AegisServer.route` verified the bearer token on every request with no per-address limit.
- `RateLimiter` keyed only on the principal, so a team of many identities multiplied the per-caller limit.
- No failed-authentication lockout.

After:

- New `ClientAddressLimiter` (security perimeter) runs in `AegisServer.preAuth` **before** `perimeter.admit`. It applies:
  - A per-address request budget (`host.limits.requestsPerMinute`, opt-in, 0 by default).
  - A lockout after `authFailuresPerMinute` (opt-in, 0 by default) rejected credentials, for `lockoutSeconds` (default 300). The answer is 429 with `Retry-After`. Only a presented and rejected credential counts; an unauthenticated OAuth-discovery request does not.
  - Both are off by default since the fifth pass (R1 in `AI-SCANNER-READINESS.md`): behind an ingress that is not in `trustedProxies`, every client shares one address and an on-by-default lockout would let one attacker lock everyone out.
  - IPv6 clients are limited per /64 prefix.
- `X-Forwarded-For` is believed only when the TCP peer is in `trustedProxies` (IP literals or CIDRs, no DNS). The client is the right-most untrusted hop.
- Memory is bounded by `maxTracked`. Overflow addresses share one bucket.
- State is per replica on purpose: a shared store would turn a pre-auth flood into load on the store.
- `RateLimiter` gained `perGroupPerMinute` (`rateLimits.perGroupPerMinute: {group: n}`). Each configured group the caller is in shares one budget across all members.

Proof:

- `ThreatModelGapServerTest.preAuthenticationLimitsAndLockoutRunBeforeTokenVerification` (GAP-L3a):
  - Four bad tokens lock one forwarded client; its next request with a **valid** token gets 429.
  - Another client behind the same proxy still gets 200.
  - A 3-per-minute budget refuses the 4th and 5th calls.
  - A DNS name in `trustedProxies` is a config error.
- `ThreatModelGapConfigTest.teamQuotaIsSharedByEveryMemberOfTheGroup` (GAP-L3b): 6 different identities in one group get 3 admissions, and another group is unaffected.

### L4. Admin separation, result screening default, PII patterns, SIEM sink, plugin admission

| Before | After | Class | Proof |
| --- | --- | --- | --- |
| MCP and admin API shared one listener; empty `adminGroups` let any authenticated caller reach admin routes (per-route checks still applied) | `host.adminListen: {host, port}` puts the admin API on its own listener and removes it from the MCP port; complete control requires `adminGroups`, `adminListen`, `requireJti` and `requireRevocation` | `OwnServerSecuritySettings`, `AegisServer.listener`, `AegisServer.checkCompleteControlPerimeter` | GAP-L4a: 404 on the MCP port, 200 on the admin port, 403 outside `adminGroups`; all four complete-control refusals |
| `screening.results` defaulted to AUDIT everywhere | Complete control defaults it to ENFORCE and refuses OFF | `GatewayFactory` screening section | GAP-L4b |
| Non-source plugins (hooks, state stores, secret providers) were loaded by type without hash admission | In complete control every plugin provider must come from a JAR listed in `completeControl.artifacts` with role `plugin` | `GatewayFactory.provider(..., pluginArtifacts, completeControl)` | GAP-L4b: an unadmitted audit sink is not loaded |
| No personal-data patterns | `output.redactPii` adds email, E.164 and NANP phone, payment card shape, US SSN and IBAN patterns, all with bounded quantifiers; default on in complete control | `OutputGuard.PII_REDACTIONS` | GAP-L4c: all four PII values redacted, a short order number kept |
| SIEM export only through a host-supplied `BuildContext.extraAuditSink` | `audit.sinks: [{type, settings}]` through the new `AuditSinkFactory` SPI; every record after the journal; a sink failure denies the call (AUDIT_UNAVAILABLE); unknown settings keys fail startup | `AuditSinkFactory`, `GatewayFactory` audit section | GAP-L4d |

### New configuration keys from the fourth pass

| Key | Default | Meaning |
| --- | --- | --- |
| `host.identity.jwt.requireJti` | false | Refuse tokens without `jti` |
| `host.identity.jwt.requireRevocation` | false | Refuse every token until the revocation list is bound |
| `host.limits.requestsPerMinute` | 0 (off) | Per-address pre-auth budget; enable with `trustedProxies` when behind a proxy |
| `host.limits.authFailuresPerMinute` | 0 (off) | Rejected credentials before lockout |
| `host.limits.lockoutSeconds` | 300 | Lockout length |
| `host.limits.trustedProxies` | [] | IP literals or CIDRs whose X-Forwarded-For is believed |
| `host.limits.maxTracked` | 100000 | Bound on tracked addresses |
| `host.adminListen.host`, `.port` | absent (shared listener) | Separate admin listener |
| `access.revokerGroups` | [] (routes closed) | Who may revoke tokens |
| `access.revocationRetentionSeconds` | 86400 | Revocation entry lifetime (60 to 2592000) |
| `access.resultReleaseGroups` | [] (route closed) | Who may release a HANDLE_ONLY result |
| `rateLimits.perGroupPerMinute` | {} | Team quotas |
| `output.redactPii` | false; true in complete control | PII patterns |
| `audit.sinks` | [] | Additional audit sinks through `AuditSinkFactory` |
| `completeControl.artifacts` | [] | Admitted plugin JARs (role `plugin`) |
| `completeControl.handleRetentionSeconds` | 0 (off) | Retain redacted HANDLE_ONLY results for release |
| Host env `AEGIS_REQUIRE_ATTESTATION_KEY` | unset; required in complete control | Admission verifier public key |

All defaults keep existing configurations working, with one exception: discovery now passes the egress control (L1).

---

## Part M. Fifth and sixth passes: scanner readiness and the chosen options

The fifth pass (`AI-SCANNER-READINESS.md`) re-reviewed the fourth-pass code the way a frontier AI code scanner would, found five defects I had introduced (R1 to R5: lockout on by default behind proxies, tokenless requests counted as failures, per-address IPv6 limits, O(n) sweeps, content-derived handles) and fixed them. It then listed 13 open items in Aegis code (O-1 to O-13) and seven outside Aegis (E-1 to E-7), each with options and a recommendation.

The sixth pass implemented the recommended option for every O item. The choices and reasons are in `DECISIONS.md`. In code:

| Id | Change | Classes | Proof |
| --- | --- | --- | --- |
| O-1 B | Unicode confusables folded before phrase matching; five language packs | `InjectionScreen.CONFUSABLES`, `normalise`, `DEFAULT_PHRASES` | SCAN-O1 |
| O-2 B | `screening.results` defaults to ENFORCE; startup warning for write tools on PASS_THROUGH output | `GatewayFactory` | `close03` test updated |
| O-3 A | Memory state refused in complete control and with `requireRevocation` | `GatewayFactory`, `Gateway.durableState`, `AegisServer` | SCAN-O3, server revocation test |
| O-4 B | Host-anchored digest for the plugin allow list | `GatewayFactory.requirePluginDigest`, `AEGIS_REQUIRE_PLUGIN_DIGEST` | SCAN-O4 |
| O-5 A | Delegation chain checked against subject revocations | `JwtVerifier.verifyWithScopes` | SCAN-O5 |
| O-6 A | Subject revocation on the Knox path | `KnoxIdentityBridge.unlessRevoked`, `AegisMcpResource.caller` | SCAN-O6 (unit; no Knox runtime here) |
| O-7 A | Revocation refused for issuers no verifier consults | `TokenRevocations.registerIssuer`, `acceptsIssuer`, `AdminApi.revoke` | `AdminApiGapTest` |
| O-8 A | HTTP CONNECT forward proxy, pinned on both hops | `PinnedHttp.Proxy`, `tunnel`, `EgressBinding`, `BuildContext.outbound`, `egress.proxy` | SCAN-O8 |
| O-9 A | JWKS fetched over the pinned transport | `JwtVerifier.jwksTransport` | `JwtVerifierTest` |
| O-11 C | Ed25519-signed checkpoints and a verifying verifier | `AuditCheckpointPolicy.signingKey`, `HashChainFileAuditSink`, `AuditCheckpointVerifier` | SCAN-O11 |
| O-12 A, D, B | SpotBugs with FindSecBugs in every build; CodeQL and OSV workflows; complete-control chart render in CI | `pom.xml`, `spotbugs-exclude.xml`, `.github/workflows/codeql.yml`, `aegis-2.0.yml` | `evidence/pass6-spotbugs-*.log`; CI unverified here |
| O-13 A | Admin API uses the gateway clock | `AdminApi`, `GatewayFactory` | existing tests |

The first SpotBugs run reported 72 findings; seven classes of them were fixed in code (constant-time digest comparison, a ReDoS-prone regex replaced by a linear scan, two null guards, dead code) and the rest excluded with a written reason each. Details and the second re-review (what a scanner would still report: P-1 to P-10) are in `AI-SCANNER-READINESS.md`.

Regression after the sixth pass, one uninterrupted run with license, coverage and SpotBugs gates on, eight modules including the Knox extension: see `evidence/maven-pass6-all-modules.log` and `evidence/maven-pass6-resume.log`.

---

## Appendix. How to re-run everything

```powershell
# Reproduction suites only (verdict table regenerated into evidence/verdicts.md):
powershell -File threat-model\run-threat-model.ps1

# Full regression of every touched module (fourth pass adds otlp and a2a):
cd aegis-mcp-gateway-20261104-2.0-review-ready
& C:\tools\apache-maven-3.9.11\bin\mvn.cmd -o -pl aegis-engine,aegis-mcp,aegis-observability-otlp,aegis-config,aegis-mcp-security,aegis-gateway,aegis-source-a2a install

# Prove any GAP test detects its bug: stash the fix, run the test, pop the stash. Example for GAP-02:
git stash push -- aegis-engine/src/main/java/io/github/vaquarkhan/aegis/engine/state/InMemoryStateStore.java
& C:\tools\apache-maven-3.9.11\bin\mvn.cmd -q -o -pl aegis-engine test "-Dtest=ThreatModelCveEngineTest#gap02*" "-Drat.skip=true"
git stash pop
```

Files changed by the fixes (uncommitted, 18 source and chart files): engine `GovernanceEngine.java`, `ApprovalService.java`, `HookBinding.java`, `HookRunner.java`, `InjectionScreen.java`, `OutputGuard.java`, `InMemoryStateStore.java`, `GovernedTool.java`; mcp `ClientSupport.java`, `SessionRegistry.java`; security `JwtVerifier.java`, `OwnServerPerimeter.java` (comment only), `OwnServerSecuritySettings.java`; config `GatewayFactory.java`, `SqlReadOnlyHook.java`; gateway `AegisServer.java`; charts `ci/test-values.yaml`, `values.yaml`. Nine test files added under `src/test` of the respective modules and copied to `tests/` (seven from passes one and two, `ThreatModelClosureEngineTest` and `ThreatModelClosureConfigTest` from pass three; `ThreatModelCveSqlTest` gained `close02`). The patch `patches/aegis-2.0-security-fixes.patch` covers all of it and reverse-applies clean.

New configuration keys introduced in passes one and two: `host.listen.allowPlaintext` (boolean, default false), `hooks[].timeoutMs` (0..60000, default 5000). Tightened validation: `approval.tokenTtlSeconds` must be 1..900; `identity.jwt.jwksUri` must be https unless loopback. Pass three keys are listed at the end of Part H.

Live end-to-end proof (Parts G and H): `powershell -File threat-model\e2e\run-e2e.ps1` after `mvn -o -pl aegis-gateway -am install -DskipTests`; results in `threat-model/e2e/RESULTS.md`, kept copies in `evidence/e2e-pass3-RESULTS.md` and `evidence/e2e-pass3-results.json`.
