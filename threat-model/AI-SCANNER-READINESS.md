# What a frontier AI code scanner would still find in Aegis 2.0

Date: 2026-11-07. Tree: `aegis-mcp-gateway-20261104-2.0-review-ready`, base `2f7b944` plus all uncommitted fixes in this folder.

## Read this first

I cannot run Anthropic's Mythos or OpenAI's GPT-5.3 here, and no one can promise what a specific model will report. Scanners of that class:

- Read the whole repository.
- Follow data flow across modules.
- Write proof-of-concept inputs.
- Rank findings by exploitability.

This file is the result of doing that kind of review myself on the current tree. I focused on the code added in the fourth pass, because new code is where such a scan looks first.

It has two parts:

1. **Findings fixed now.** Five real defects that I introduced in the fourth pass and found on re-review. A scanner would very likely have reported them. They are fixed and tested in this pass.
2. **Still open.** Findings a scanner would report that are not fixed, with severity and the reason they are open.

No conventional static analyser was run either: the build has no SpotBugs, Semgrep or CodeQL step. That gap is itself item O-12.

## Part 1. Fixed in this pass (fifth pass, R1 to R5)

| Id | Severity | Finding | Fix (class, method) | Proof |
| --- | --- | --- | --- | --- |
| R1 | High (availability) | The pre-auth lockout was **on by default** (20 failures per minute, 5 minute lockout). Behind a load balancer or Kubernetes ingress that is not listed in `trustedProxies`, every caller shares the proxy's address. One attacker sending 21 bad tokens would lock **every** user out, and the 1200 per minute budget would cap the whole gateway. | `ClientAddressLimiter.Settings.DEFAULT` is now `(0, 0, 300, [], 100000)`. Parsing defaults in `OwnServerSecuritySettings.parse` are 0. Both are opt-in, to be enabled together with `trustedProxies`. | `ClientAddressLimiterTest.forwardedForIsBelievedOnlyFromTrustedProxies` asserts the defaults are 0. |
| R2 | Medium | Every 401 counted towards lockout, including requests **with no credential**. An MCP client's first request in OAuth discovery is unauthenticated by design (it needs the `WWW-Authenticate` challenge), so normal clients moved towards lockout. | `AegisServer.afterAdmission(client, ex, admission)` counts only when an `Authorization` header was presented and rejected. | `ThreatModelGapServerTest.preAuthenticationLimitsAndLockoutRunBeforeTokenVerification`: 6 tokenless requests, then a valid token still gets 200. |
| R3 | Medium | IPv6 clients were limited per full address. A subscriber normally holds a whole /64 and can rotate through 2^64 source addresses. | `ClientAddressLimiter.bucket` keys IPv6 by /64. | Same test class: two addresses in one /64 share a key, different /64s do not. |
| R4 | Low | When the address map was full, every new address triggered a full O(n) sweep of up to 100000 entries. That is CPU amplification for an address spray. | `ClientAddressLimiter.key` sweeps at most once per second (`lastSweep` compare-and-set). | Existing overflow and sweep tests still pass. |
| R5 | Medium (separation of duties) | HANDLE_ONLY handles were the SHA-256 of the result **content**. Two calls with identical results shared one handle, and the second `put` overwrote the first entry's producer. A caller in the release group could then release a result its own call produced, which defeats "the producer cannot release its handle". | `OutputPolicy.release` derives the handle from request id plus content digest. `ResultHandleStore.put` uses `putIfAbsent` (never overwrites) and returns false instead of throwing when the store is full. | `AdminApiGapTest.handleReleaseIsTakeOnceNeverToTheProducerAndJournaled`: identical results from two calls get different handles; a second put on an existing handle is refused. |

Regression after R1 to R5, as one uninterrupted seven-module run with license and coverage gates: engine 164, mcp 62, otlp 7, config 88, security 30, gateway 38, a2a 8. That is 397 tests, BUILD SUCCESS (`evidence/maven-pass5-all-modules.log`).

## Part 2. Open after the fifth pass (historical; every O item below was then implemented in the sixth pass, see "Decisions and their implementation")

Severity uses the usual scale for a gateway that sits in front of production data systems. "Gateway-ownable" says whether more Aegis code could close it.

### Open findings in Aegis code or configuration

| Id | Severity | What a scanner would report | Why it is still open | Gateway-ownable | What closes it |
| --- | --- | --- | --- | --- | --- |
| O-1 | High | Prompt-injection screening is a phrase list. It normalises with NFKC, but NFKC does not map cross-script homoglyphs (Cyrillic `і` in `іgnore`), other languages ("ignorez les instructions") or paraphrase. A scanner will produce a bypass string in minutes. | A phrase list cannot judge intent. It is documented as a tripwire. | Partly | The structural control already exists: complete-control `OutputPolicy` STRUCTURED_ALLOWLIST or HANDLE_ONLY, plus approval for every write. A confusables table (Unicode TR39 skeleton) would raise the bar. |
| O-2 | High (on the default path) | Outside complete control, tools default to `PASS_THROUGH` output and `screening.results: AUDIT`. A hostile backend result reaches the model with only redaction applied. | A default change would break existing adopters. Complete control already enforces it. | Yes | Make complete control the documented production profile, or change defaults at 2.0 GA. |
| O-3 | High | Revocations, approvals, nonces and rate windows live in the **in-memory** store by default. A restart clears revocations, so a revoked token works again after a pod restart, and each replica has its own list. | Durable stores exist (Derby, PostgreSQL), but nothing requires them. | Yes | Refuse `state.type: memory` when `completeControl.enabled`, or when `requireRevocation` is set on more than one replica. One startup check. |
| O-4 | Medium | `completeControl.artifacts` (the plugin JAR allow list) sits in the configuration file and is **not** covered by the signed server catalog. Someone who can edit the config **and** write to the classpath can admit a new plugin. | The signed catalog format covers sources only, and changing it is a format break (decision recorded earlier). | Yes | Add the plugin artifact list to the signed catalog, or anchor its digest on the host like the review key. |
| O-5 | Medium | Revoking a compromised **agent** identity does not revoke tokens where that agent appears only as `act.sub` (RFC 8693 delegation). Subject revocation keys on `sub`. | Not designed in the fourth pass. | Yes | Check every actor in the chain against subject revocations in `JwtVerifier.verifyWithScopes`. |
| O-6 | Medium | Revocation exists only on the own host. On the Knox extension path Knox authenticates and `JwtVerifier` is not used, so gateway-side revocation does not apply. | Knox owns identity on that path. | Partly | Consult `TokenRevocations` in the Knox extension for the subject and session it receives, or revoke in Knox. |
| O-7 | Medium | A revoker who calls the API with a static-token identity revokes under issuer `aegis-static`, which JWT checks never consult. The call returns 200 and revokes nothing useful. | Unchecked combination. | Yes | Refuse revocation unless the issuer is the configured JWT issuer, or the `Aegis-Token-Issuer` header names it. |
| O-8 | Medium | `PinnedHttp` does not support an outbound HTTP proxy, HTTP/2 or connection reuse. Each call does a fresh TCP and TLS handshake. Deployments that must leave through a corporate proxy cannot use gateway-built transports. | The design aimed at closing the rebinding window first. | Yes | CONNECT-proxy support with the egress check on the target, and a pinned connection pool keyed by (host, address). |
| O-9 | Low | JWKS fetching still uses the JDK client, so it is not pinned. No egress policy applies to the identity provider URL, so there is no check to race. The URL is operator-set and HTTPS-only. | Low value. | Yes | Route JWKS through `PinnedHttp` with a dedicated allow entry. |
| O-10 | Low | PII redaction is regex based. It misses names, addresses, national IDs outside the US, and numbers written in words, and it over-redacts some long numeric identifiers. | Regex PII has a known ceiling. | Partly | A remote hook calling a PII classifier for regulated data; HANDLE_ONLY for tools that return personal records. |
| O-11 | Low | The audit journal is hash-chained, but a writer with file access can rewrite the whole file and recompute the chain. Checkpoints help only if they go to write-once storage. | External anchoring is listed in the roadmap. | Partly | Send checkpoints to object-lock storage, or send records through the new `AuditSinkFactory` to a SIEM the gateway host cannot modify. |
| O-12 | Process | No SAST in the build (SpotBugs, Semgrep, CodeQL) and no dependency scan by default. The CycloneDX SBOM is produced only under `-Prelease`. Runtime dependencies are small: SnakeYAML 2.4 (loaded with `SafeConstructor`, alias, depth and size limits), Derby 10.16.1.1 and PostgreSQL JDBC 42.7.12 in optional modules. | Not part of this review's scope. | Yes | Add CodeQL or SpotBugs plus OWASP Dependency-Check or `osv-scanner` to CI, and produce the SBOM on every build. |
| O-13 | Low | `AdminApi` revocation and release use `Instant.now()` for retention instead of the engine clock. | Small inconsistency; not exploitable. | Yes | Pass the gateway clock into `withRevocations`. |

### Open items that need a component Aegis does not own (from Part K.3 and Part I)

| Id | Item | Why external or unproven here |
| --- | --- | --- |
| E-1 | Sandbox runtime (gVisor, Linux namespaces) for stdio servers | The launcher prefix is enforced. The runtime is the platform's job and is unproven on this Windows host. |
| E-2 | Kubernetes NetworkPolicy and Helm gates | The templates exist; `helm template` was not run because Helm is not installed. |
| E-3 | Image scanning, SBOM and Sigstore for upstream servers | Aegis verifies the admission system's signed statement (K1). It does not scan images itself. |
| E-4 | Clients bypassing Aegis (shadow MCP, OWASP MCP09) | Endpoint and network control. |
| E-5 | ResourceBroker, tenant CredentialLease, taint tokens, live catalog reload, adapter-digest binding | Not implemented. Each needs a broker, a secrets backend, client support or a reload design (Part K.3). |
| E-6 | Backend-internal flaws (symlink escape, SQL concatenation in the server, malicious behaviour behind an unchanged definition) | Inside a process Aegis does not run. |
| E-7 | Model-side risks: LLM01 direct injection, LLM03 model supply chain, LLM04, LLM07, LLM08, LLM09 | Owned by the model provider, client or data pipeline (`OWASP-TOP10-COVERAGE.md`). |

## Decisions and their implementation

The options for every item, the one chosen and the reason are recorded in `DECISIONS.md`. The sixth pass implemented the recommended option for O-1 to O-13 (O-10 by design, no code) and added CI steps for E-2 and O-12 B. Regression: `evidence/maven-pass6-all-modules.log`, eight modules including the Knox extension, with license, coverage and SpotBugs plus FindSecBugs gates on.

## Second re-review: what a frontier scanner would still report

Done after the sixth pass, with the same method as the first round: whole tree, data flow across modules, attempt to write an input that breaks each new control.

### Fixed during the re-review itself

| Id | Severity | Finding | Fix |
| --- | --- | --- | --- |
| R-6 | Medium (availability) | The first version of the pinned JWKS fetch (O-9) built its guard with no allow CIDRs, so an identity provider on a private address (the common corporate case) would have been refused and every login would have failed. | `JwtVerifier.jwksTransport` allows every address class for that one host; the pin closes the rebinding window only. |
| R-7 | Low | `AuditCheckpointVerifier` tried to remove `signature` from the parser's read-only map and threw. | Copy to a mutable map first. Caught by the new test before it shipped. |
| R-8 | Low (availability, Windows) | `CatalogIntegrity.persist` replaced the pin file with one atomic move and failed startup with `AccessDeniedException` when another process (indexer, scanner) held the file for a moment. Seen once in the live run on this host. | Bounded retry (5 attempts, 20 ms backoff) before failing. The live run passed on the rerun and after the fix. |

### Static analysis results (first run, O-12)

SpotBugs with FindSecBugs (effort Max, threshold Medium) over the eight modules reported 72 findings on the first run. Triage:

| Pattern | Count | Outcome |
| --- | --- | --- |
| EI_EXPOSE_REP, EI_EXPOSE_REP2 | 47 | Excluded with justification: records copy their collections in compact constructors; service fields are shared collaborators |
| UNSAFE_HASH_EQUALS | 5 | **Fixed**: digest comparisons in `CatalogIntegrity`, `ToolCatalog` and `ContentGovernor` now use `Digests.constantTimeEquals` |
| IS2_INCONSISTENT_SYNC | 6 | Excluded for `HashChainFileAuditSink`: the unsynchronised accesses happen in the constructor before publication |
| REDOS | 1 | **Fixed**: `VaultKvSecretProvider` path check is a linear scan, no regex |
| NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE | 2 | **Fixed**: `Path.getFileName()` null guard in `ConfigLoader` and `OpenApiDraft` |
| URF_UNREAD_FIELD | 2 | **Fixed**: two dead `SourceBuild` fields removed |
| UPM_UNCALLED_PRIVATE_METHOD | 1 | **Fixed**: dead `approverEvidence` overload removed |
| COMMAND_INJECTION | 1 | Excluded for `ProcessLauncher`: argv is operator configuration passed as a list, hash-admitted in complete control |
| UNENCRYPTED_SOCKET | 1 | Excluded for `PinnedHttp`: the plain socket is wrapped in TLS before any byte is sent when the scheme is https |
| REC_CATCH_EXCEPTION | 2 | Excluded for `JwtVerifier`: catching everything around signature verification is the fail-closed design |
| RV_RETURN_VALUE_IGNORED_INFERRED | 1 | Excluded for `OwnServerSecuritySettings`: the call marks a config key as read |
| NP_BOOLEAN_RETURN_NULL | 1 | Excluded for `ControlTokens.boolOrNull`: deliberate tri-state |
| PATH_TRAVERSAL_IN, PATH_TRAVERSAL_OUT | (config module) | Excluded for the configuration package: paths come from the operator's own file |

Every exclusion is in `spotbugs-exclude.xml` with the reason beside it. None of the FindSecBugs injection detectors (SQL, command, path, XXE, SSRF) reported anything that was not covered above.

### Still open after the sixth pass

| Id | Severity | What a scanner would report | Why it is open | What closes it |
| --- | --- | --- | --- | --- |
| P-1 | Medium | Phrase screening is still a list. Confusables and five language packs are folded in, but paraphrase ("pay no attention to what you were told") and languages outside the packs pass. | A list cannot judge intent. | The structural control is complete-control output policy plus approval for writes (already in place). A classifier remote hook (O-1 C) raises the bar further. |
| P-2 | Medium | The checkpoint signing key sits on the gateway host (`audit.checkpoint.signingKeyFile`). Root on that host has both the journal and the key. The signature defeats an attacker with write access to the files but not to the key file. | A gateway process cannot sign with a key it cannot read. | Write checkpoints to object-lock or WORM storage (O-11 A), or sign on a separate host that tails the checkpoint file. |
| P-3 | Medium | Knox-path revocation (O-6) compiles against Knox 3.0 but was not exercised: there is no Knox runtime on this host. | Environment. | Run `aegis-knox3-extension` against a stock Knox with a revoked subject; the two-door runs in `examples/adopters` are the place. |
| P-4 | Low | `PinnedHttp` opens a fresh TCP and TLS connection per call (no pool, no HTTP/2). A large fan-out of backend calls costs a handshake each. | Deferred from O-8. | A pinned keep-alive pool keyed by host and checked address, invalidated when the pin changes. |
| P-5 | Low | The proxy support sends no `Proxy-Authorization`; authenticated proxies are not supported. | Scope of O-8 A. | Add basic or token proxy credentials from the secrets source. |
| P-6 | Low | The confusables table is the common Cyrillic and Greek subset, not the full Unicode TR39 data. | Table size versus coverage. | Generate the full table from `confusables.txt` at build time. |
| P-7 | Low | Pre-authentication limits are per address or per IPv6 /64. A distributed source (many networks) is bounded only by the worker pool and connection cap, not by a global pre-auth budget. | A global cap is itself a denial-of-service lever. | Edge rate limiting (ingress, WAF) in front of Aegis. |
| P-8 | Low | Retained HANDLE_ONLY results are stored redacted but in plaintext in the state store. | The store's at-rest protection is the database's. | PostgreSQL with TDE or disk encryption; keep `handleRetentionSeconds` short. |
| P-9 | Process | The CodeQL and OSV workflows are written but unverified (no GitHub run from this host). The Helm complete-control step is added to CI but Helm is not installed here. | Environment. | First push to GitHub runs them. |
| P-10 | Process | Adopter example configurations under `examples/adopters` were not re-run after the `screening.results` default changed to ENFORCE. A profile whose backend legitimately returns a screened phrase would now withhold that result. | Time; two-door runs need the real engines. | Re-run the adopter profiles; set `screening.results: AUDIT` where a documented false positive exists. |

### Items that remain outside Aegis

Unchanged from the first round (E-1 to E-7). Their chosen deployment options are in `DECISIONS.md`. In short: gVisor or bubblewrap for the sandbox runtime; `helm template` plus kubeconform in CI; Trivy or Grype plus cosign feeding the signed attestation; network and endpoint policy for shadow MCP servers; token exchange and read-only mounts instead of credential leases and a resource broker for now; provider guardrails for model-side risks.

### Can each remaining item be fixed here? (seventh pass)

A direct answer per item: fixable in this code now, fixable but deliberately deferred, or not possible on this host.

| Id | Verdict | Reason |
| --- | --- | --- |
| P-1 phrase paraphrase | **Not fully fixable in code.** Partly mitigated. | A word list cannot judge intent; no amount of list growth closes paraphrase. The real control is already in place (complete-control `OutputPolicy` plus write approval). Full closure needs a classifier, which is a model service, offered as a remote hook (O-1 C). |
| P-2 signing key co-located with the journal | **Not fixable in code; deployment.** | A process that signs needs a key it can read, so root on that host has both. Closing it means WORM/object-lock storage for checkpoints or signing on a separate host. The code already supports both (signed checkpoints, SIEM sink); the placement is an operator choice. |
| P-3 Knox revocation not run against real Knox | **Not possible here; environment.** | No Knox runtime on this Windows host. Covered by a unit test (`KnoxRevocationTest`, SCAN-O6); the end-to-end run belongs in the `examples/adopters` two-door harness. |
| P-4 no connection pool or HTTP/2 | **Fixable, deliberately deferred.** | Purely performance. A pinned keep-alive pool is real work with real risk (stale-connection and pin-invalidation bugs) for no security gain. Deferred on purpose. |
| P-5 no proxy authentication | **Fixed (seventh pass).** |
| P-6 curated confusables table | **Broadened (seventh pass).** The table gained more Cyrillic, Greek lunate forms, Armenian and IPA look-alikes; still a curated subset, full TR39 generation at build remains the only complete fix. Proof: `ThreatModelClosureEngineTest` SCAN-O1 Armenian case. |
| P-8 retained handle bodies plaintext in the store | **Fixed (seventh pass).** Optional AES-256-GCM: `completeControl.handleEncryptionKeyEnv` encrypts every retained body as `enc:nonce||ciphertext`; a wrong key cannot read it; release decrypts. Proof: `AdminApiGapTest` SCAN-P8, `CompleteControlConfigTest` SCAN-P8e. | `PinnedHttp.Proxy` now carries an optional `Proxy-Authorization` value; `egress.proxy.credentialEnv` or `usernameEnv`/`passwordEnv` supply it from the secrets source. Sent only on the CONNECT line, never to the target; line breaks refused. Proof: `PinnedHttpTest` authenticated-proxy case. |
| P-6 curated confusables table, not full TR39 | **Fixable only at build time; left curated.** | The complete fix generates the table from the Unicode `confusables.txt` at build. NFKC already folds fullwidth and mathematical letters; the shipped table folds the common Cyrillic and Greek cross-script look-alikes. A hand-added partial set would not meaningfully raise coverage and risks wrong mappings, so the table is left curated and the generator is noted as optional. |
| P-7 no global pre-auth budget | **Not fixed by choice.** | A global cap is itself a denial-of-service lever (one attacker trips it for everyone). Distributed floods belong to edge rate limiting (ingress, WAF) in front of Aegis. |
| P-8 retained handle bodies plaintext in the store | **Fixable, deferred to the store.** | The bodies are already redacted and short-lived (`handleRetentionSeconds`). Application-layer encryption adds a second key to manage; database at-rest encryption (PostgreSQL TDE, encrypted volume) covers it without that. Left to the store. |
| P-9 CodeQL/OSV/Helm CI unverified | **Not possible here; environment.** | No GitHub Actions and no Helm on this host. The workflows and the chart render step are written; the first push runs them. |
| P-10 adopter profiles not re-run after the ENFORCE default | **Not possible here; environment.** | The two-door adopter runs need the real engines (Spark, Kafka, Flink, Iceberg), which are not on this host. The e2e harness config already sets ENFORCE; the adopter profiles must be re-run where the engines exist. |

Net: of the ten, one more is now fixed in code (P-5). P-4, P-6 and P-8 are fixable but deferred for the reasons above (performance, marginal coverage, key management). P-1 and P-7 cannot be closed by a gateway in principle. P-2, P-3, P-9 and P-10 need storage, a Knox runtime, a CI service or the real engines that this host does not have.

### Honest summary

After six passes every finding that Aegis code can close is closed and tested, with three exceptions that are performance or scope limits rather than open attack paths (P-4, P-5, P-6). The remaining security-relevant items are a phrase list that is a tripwire by design (P-1), a signing key co-located with the journal until WORM storage is used (P-2), one control proven by compilation only (P-3), and the items that need a runtime, a cluster or a CI service this Windows host does not have (P-3, P-9, P-10). A frontier scanner would report P-1 and P-2 as findings and the rest as notes.

## Addendum: full-reactor static-analysis pass (2026-11-07)

The earlier passes gated the eight core modules. Running the complete reactor (16 modules, including the
optional state, source and guardrails modules) under SpotBugs with the FindSecBugs plugin surfaced two
pre-existing findings in modules the narrower builds never exercised. Both are resolved, and the full
reactor now builds green with SpotBugs plus FindSecBugs, JaCoCo and Apache RAT all on.

| Id | Severity | Finding | Resolution |
| --- | --- | --- | --- |
| R-9 | Low | `aegis-state-derby` `DerbyStateStore`: `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`. `Path.getParent()` can be null, and the result was passed straight to `Files.createDirectories`. | **Fixed in code**: read the parent once and guard it with a null check before creating directories. |
| R-10 | By design | `aegis-source-jdbc` `JdbcToolSource`: `SQL_INJECTION_JDBC` on `executeQuery` and `executeUpdate`. | **Justified exclusion**: this generic JDBC converter exists to run the one governed statement a SQL tool carries, so the statement is dynamic by design and cannot be a bind parameter. The controls are the `sql-readonly` hook (one read statement, no writing keyword) plus a read-only database credential (FEATURES.md F13c); a tool that needs certainty is classified MUTATE and gated by approval. Excluded for that one class with the reason in `spotbugs-exclude.xml`. |

Why they were not seen before: the pass-7 build ran `-pl` the eight core modules only. The reactor-wide
SpotBugs gate added in the sixth pass covers every module, but no full-reactor build had been run until
this end-to-end review. Evidence: `threat-model/evidence/final-review-build*.log`, BUILD SUCCESS across
all 16 modules.
