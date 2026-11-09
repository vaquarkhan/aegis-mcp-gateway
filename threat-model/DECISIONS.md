# Decisions taken on the open findings (sixth pass)

Date: 2026-11-07. Tree: `aegis-mcp-gateway-20261104-2.0-review-ready`, base `2f7b944` plus all uncommitted fixes in this folder.

This file records, for every open item from `AI-SCANNER-READINESS.md`, which option was chosen, why, what was changed, and how it is proven. The options that were not chosen are named too, with the reason they lost. Items that need a component outside Aegis record the deployment choice and what Aegis contributes.

Guiding rule for the choices: pick the option that closes the finding inside code Aegis owns, keeps the fail-closed behaviour, and does not add a surface that Aegis would then have to defend (no UI, no accounts, no second identity system).

## Aegis code and configuration

### O-1 Phrase screening bypass by look-alike letters and other languages

- **Chosen: B.** Fold Unicode confusables to Latin before matching, and add phrase packs for German, French, Spanish, Italian and Portuguese.
- **Rejected:** A (phrase list as-is) leaves a one-minute bypass in place. C (a classifier service) is right for regulated or internet-facing deployments, but it adds a model dependency to the core path; it stays available as a remote hook.
- **Change:** `InjectionScreen.CONFUSABLES` (Cyrillic and Greek subset of Unicode TR39 for Latin letters) applied in `InjectionScreen.normalise` after NFKC and lower-casing; `DEFAULT_PHRASES` gained eight other-language phrases.
- **Proof:** `ThreatModelClosureEngineTest.scanO1_homoglyphAndOtherLanguageInjectionPhrasesAreCaught` (verdict SCAN-O1): `іgnоre previοus instructions` with Cyrillic i and o and Greek omicron is caught; a German sentence is caught; normal business text is not.
- **Still true:** paraphrase ("pay no attention to what you were told before") is not caught. The structural control remains complete-control output policy plus approval for writes.

### O-2 Default path passes backend text to the model

- **Chosen: B.** `screening.results` defaults to ENFORCE everywhere, and startup warns for every MUTATE or DESTRUCTIVE tool whose output policy is PASS_THROUGH.
- **Rejected:** A (document only) leaves the weak default. C (a `profile: production` switch) would hide which setting does what; every control stays an individual, visible key.
- **Change:** `GatewayFactory` screening section, default `"ENFORCE"`; a warning loop over `catalog.entries()` after the catalog loads.
- **Proof:** `ThreatModelClosureConfigTest.close03_screeningResults_defaultsToEnforceAndAuditsWhenAsked`: with no key the injected result is withheld (PROMPT_INJECTION); with explicit AUDIT it passes and is journaled.
- **Behaviour change:** an adopter who relied on the AUDIT default must now set `screening.results: AUDIT`. Nothing has been released, so no deployed configuration is affected.

### O-3 Revocations and approvals in memory by default

- **Chosen: A.** Complete control refuses `state.type: memory`; the own host refuses `requireRevocation` without a durable or host-provided store.
- **Rejected:** B (persist to a local file) stays per replica. C (document only) leaves "revoked token works after restart".
- **Change:** `GatewayFactory` state section computes `durableState` and throws in complete control; `Gateway.durableState()`; `AegisServer` constructor check.
- **Proof:** `CompleteControlConfigTest.completeControlRefusesMemoryStateAndAnchorsThePluginList` (SCAN-O3) and `ThreatModelGapServerTest.revokedTokensAreRefusedAtTheFrontDoor` (the memory-store gateway is refused with "durable"; a host-provided store passes).
- **Note:** a store supplied through `BuildContext.stateStore` counts as durable because the host (Knox, an embedder) owns it. Tests use that path.

### O-4 Plugin allow list not signed

- **Chosen: B.** Host-anchored digest: when `completeControl.artifacts` is non-empty, `AEGIS_REQUIRE_PLUGIN_DIGEST` (or JVM property `aegis.requirePluginDigest`) must equal `sourceDigest({"artifacts": [...]})`.
- **Rejected:** A (catalog format v2) is a format break for one list. C (read-only image) is deployment advice, not a check.
- **Change:** `GatewayFactory.requirePluginDigest`, called before `ArtifactAdmission.verify`.
- **Proof:** SCAN-O4 in the same test: missing digest refused, wrong digest refused, correct digest starts.

### O-5 Delegated actor not covered by subject revocation

- **Chosen: A.** Every actor in the RFC 8693 `act` chain is checked against subject revocations.
- **Rejected:** B (a separate actor-revocation kind) duplicates storage for the same question.
- **Change:** `JwtVerifier.verifyWithScopes` checks `list.check(issuer, null, actor, iat)` for each chain entry.
- **Proof:** `ThreatModelGapJwtTest` (SCAN-O5): revoking `agent-root` refuses a token where it appears only two levels down the chain.

### O-6 No revocation on the Knox path

- **Chosen: A plus B.** The Knox extension consults the gateway's revocation list for the effective subject, the primary actor and every actor in the chain; and the Knox documentation says that token-level revocation is done in Knox or the IdP.
- **Change:** `AegisMcpResource.caller()` in `aegis-knox3-extension`. Knox gives no `jti` or `iat`, so a subject entry refuses every request for that subject until it expires. The Knox issuer (`knox:<topology>`) is registered with the list so revocations under it are accepted.
- **Proof:** the module compiles against the Knox 3.0 jars (`evidence/pass6-compile.log`, module 8 of 8). There is no running Knox on this host, so the behaviour is not exercised end to end here. This is listed as a remaining gap.

### O-7 Revocation by a static-token admin does nothing useful

- **Chosen: A.** A revocation is refused with 400 unless its issuer is one a bound verifier consults.
- **Rejected:** B (always require the header) adds friction for the common case where the caller's own issuer is right.
- **Change:** `TokenRevocations.registerIssuer` and `acceptsIssuer`; `JwtVerifier.bindRevocations` registers its issuer; `AdminApi.revoke` refuses otherwise.
- **Proof:** `AdminApiGapTest.revocationRoutesAreGatedJournaledAndValidated`: a static-token admin gets 400, the same admin naming the JWT issuer in `Aegis-Token-Issuer` gets 200.

### O-8 Pinned transport has no proxy, HTTP/2 or connection reuse

- **Chosen: A, proxy first.** HTTP CONNECT through a forward proxy, configured once as `egress.proxy: {host, port}`. The proxy host joins the allow list and is pinned like any destination; the target is still resolved and checked by the egress control; TLS runs end to end through the tunnel against the original name.
- **Rejected:** B (per-source opt-out to the JDK client) reopens the rebinding window. C (document only) blocks deployments that must leave through a corporate proxy.
- **Operator note:** the proxy's own address must be allowed by `egress.allowCidrs` (corporate proxies usually sit on private ranges), and only unauthenticated CONNECT is supported; `Proxy-Authorization` is not sent.
- **Deferred:** the keep-alive pool and HTTP/2. Each call still does a fresh TCP and TLS handshake. This is a performance limit, not a security one; listed as remaining.
- **Change:** `PinnedHttp.Proxy`, `PinnedHttp.tunnel`, `EgressBinding.bind(guard, proxy)`, `BuildContext.outbound`, `GatewayFactory` egress section and the OPA, hook and OTLP transports.
- **Proof:** `PinnedHttpTest.aForwardProxyIsUsedWithConnectAndTheTargetIsStillChecked` (SCAN-O8): request reaches the backend through an in-test CONNECT proxy; a target not on the allow list sends nothing to the proxy; a proxy not on the allow list is refused; a refused CONNECT fails the call.

### O-9 JWKS fetch not pinned

- **Chosen: A.** The JWKS fetch uses `PinnedHttp` with an allow list of exactly the JWKS host (plus loopback ranges when the URI is loopback, which `Settings` already permits for local testing).
- **Change:** `JwtVerifier.jwksTransport`. The pin accepts every address class for that one host (allow CIDRs `0.0.0.0/0` and `::/0`), because the goal is to close the rebinding window, not to apply address policy to an operator-chosen identity provider; corporate IdPs often live on private addresses. The first version of this change denied private addresses and would have broken such deployments; it was corrected during the re-review (R-6 in `AI-SCANNER-READINESS.md`).
- **Proof:** the existing `JwtVerifierTest` JWKS test (loopback HTTP server) passes over the pinned transport.

### O-10 PII redaction is regex only

- **Chosen: A.** Keep the regex set, and use `outputPolicy: HANDLE_ONLY` for tools that return personal records. No code change.
- **Rejected for now:** B (classifier hook) and C (locale packs) are available later; C raises false positives and B adds a service.

### O-11 Audit file rewritable by someone with file access

- **Chosen: C now, A as the deployment step.** Every checkpoint line is Ed25519-signed with a key kept off the journal host (`audit.checkpoint.signingKeyFile`); the verifier accepts a public key and refuses a checkpoint file whose lines do not verify. Deployment guidance: write the checkpoint file to object-lock or WORM storage.
- **Rejected as the only control:** B (SIEM copy) depends on the SIEM; it remains available through `audit.sinks`.
- **Change:** `AuditCheckpointPolicy.signingKey`, `HashChainFileAuditSink.writeCheckpoint`, `AuditCheckpointVerifier.verify(journal, checkpoints, publicKey)`, `GatewayFactory` checkpoint section.
- **Proof:** `ThreatModelGapConfigTest.signedCheckpointsDetectARegeneratedCheckpointFile` (SCAN-O11): the verifier passes with the public key, and fails with "signature" after one checkpoint line is regenerated without the key.

### O-12 No SAST or dependency scan

- **Chosen: A plus D, and B for CI.** SpotBugs with the FindSecBugs detectors runs in every `mvn verify` (root pom, `failOnError`, effort Max, threshold Medium, exclusions in `spotbugs-exclude.xml` with a written reason per entry). A CodeQL workflow (security-extended queries) and an OSV scan of the CycloneDX SBOM run in GitHub Actions.
- **Rejected:** C (Semgrep) overlaps with CodeQL.
- **Change:** `pom.xml`, `spotbugs-exclude.xml`, `.github/workflows/codeql.yml`.
- **Proof:** the SpotBugs run is in `evidence/pass6-spotbugs.log`. The CodeQL and OSV workflows cannot run on this host; they are unverified until the repository is pushed.

### O-13 Admin retention uses the system clock

- **Chosen: A.** `AdminApi` takes the gateway clock; `GatewayFactory` passes `ctx.clock()`.

## Items outside Aegis

| Item | Chosen | Why | What Aegis contributes |
| --- | --- | --- | --- |
| E-1 Sandbox runtime | gVisor RuntimeClass on Kubernetes; bubblewrap or firejail on VMs; refuse stdio sources in complete control on Windows | These are the runtimes that give a process no ambient authority; Windows has no equivalent prefix today | `ProcessLauncher.prefixed` enforces the prefix; the chart gate requires `runtimeClassName` |
| E-2 Helm and NetworkPolicy | `helm template` plus kubeconform in CI (added for the complete-control values file) | Helm is not installed here; CI can run it | `charts/aegis/ci/complete-control-values.yaml`; the workflow step greps the render for `runtimeClassName` and `NetworkPolicy` |
| E-3 Image scanning and signing | Trivy or Grype plus cosign, then an admission job signs `attestationStatement` | Aegis should verify, not scan | `AEGIS_REQUIRE_ATTESTATION_KEY` and `deploymentAttestation.signature` (K1) |
| E-4 Clients bypassing Aegis | Network rule plus managed IDE configuration, plus a periodic scan for unmanaged MCP endpoints | A gateway cannot see traffic that avoids it | Signed server catalog and egress pinning limit what Aegis can reach |
| E-5 Unbuilt complete-control items | Live catalog reload and adapter-digest binding first (Aegis-ownable); token exchange and read-only mounts now instead of credential leases and a resource broker; wait for client support before taint tokens | Reload and adapter binding need no external system; the others do | `FILE_BROKERED` contract and `ArtifactAdmission` roles are ready for them |
| E-6 Backend-internal flaws | Patch and least privilege; classify risky tools DESTRUCTIVE; sandbox each server | Aegis sees the argument, not the server's disk or SQL | Schema, scope, approval and dry-run receipts |
| E-7 Model-side risks | Provider guardrails plus client policy; optional citation-check hook | Owned by the model provider and client | Result digests and the journal give provenance for review |

## Configuration keys added in this pass

| Key or setting | Default | Meaning |
| --- | --- | --- |
| `screening.results` | ENFORCE (was AUDIT) | Injection-bearing results are withheld unless AUDIT is set |
| `egress.proxy.host`, `egress.proxy.port` | absent | Forward proxy for every pinned transport (CONNECT) |
| `audit.checkpoint.signingKeyFile` | absent | Ed25519 private key; checkpoints are signed |
| Host env `AEGIS_REQUIRE_PLUGIN_DIGEST` | unset; required when `completeControl.artifacts` is non-empty | Digest of the plugin list |
| `state.type` in complete control | must be durable or host-provided | Memory store refused |
| `host.identity.jwt.requireRevocation` | needs a durable store | Refused with the memory store |
