from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_TM / r"AI-SCANNER-READINESS.md")
s = open(p, encoding='utf-8').read()
start = s.index('## What I would fix next, in order')
new = '''## Decision options

Each open item lists the options and a recommendation. Pick a letter per item. "Effort" is a rough size for the code or setup change. Nothing below has been applied yet.

### Aegis code and configuration (O-1 to O-13)

**O-1 Phrase screening can be bypassed (High)**

- A. Keep the phrase list as a tripwire and rely on complete-control `OutputPolicy` plus write approval. Effort: none.
- B. Add a Unicode TR39 confusables skeleton before matching, plus phrase packs for other languages. Effort: small. Raises the bar; still bypassable by paraphrase.
- C. Add a classifier as a remote hook (for example a prompt-injection model service) on arguments and results. Effort: medium, plus a service to run.
- **Recommended: B now; C for regulated or internet-facing deployments.**

**O-2 Default path passes backend text to the model (High)**

- A. Keep the defaults; document complete control as the production profile. Effort: docs only.
- B. Before 2.0 GA, default `screening.results` to ENFORCE everywhere, and log a startup warning for every MUTATE or DESTRUCTIVE tool left on PASS_THROUGH output. Effort: small. Adopter profiles need explicit settings where AUDIT is wanted.
- C. Add one `profile: production` key that switches on every secure default (results ENFORCE, redactPii, requireJti and requireRevocation, durable state). Effort: small to medium.
- **Recommended: B.** Nothing has been released, so changing a default now costs little.

**O-3 Revocations and approvals are in memory by default (High)**

- A. Refuse `state.type: memory` when `completeControl.enabled` or `requireRevocation` is set. Effort: small.
- B. Allow memory, but warn at startup and persist revocations to a local file. Effort: medium. Still per replica.
- C. Leave it; document that multi-replica and restart-safe deployments must use PostgreSQL. Effort: docs only.
- **Recommended: A.**

**O-4 Plugin allow list is not signed (Medium)**

- A. Move the plugin artifact list into the signed server catalog (catalog format version 2). Effort: medium; a format change.
- B. Host-anchored digest: the host sets `AEGIS_REQUIRE_PLUGIN_DIGEST` to the SHA-256 of the `completeControl.artifacts` block, the same pattern as the review key. Effort: small; no format change.
- C. Only document: load plugins from a read-only image directory (the chart already sets readOnlyRootFilesystem). Effort: docs only.
- **Recommended: B.**

**O-5 Delegated agent identity not covered by subject revocation (Medium)**

- A. Check every actor in the `act` chain against subject revocations. Effort: small.
- B. Add a separate actor-revocation kind and route. Effort: small to medium.
- **Recommended: A.** It reuses the existing route and storage.

**O-6 No revocation on the Knox path (Medium)**

- A. The Knox extension checks the Knox subject against `TokenRevocations` (subject-level only; Knox gives no `jti`). Effort: small to medium.
- B. Document that revocation on the Knox path is done in Knox or the IdP. Effort: docs only.
- **Recommended: A plus B.**

**O-7 Revocation by a static-token admin does nothing useful (Medium)**

- A. Refuse with 400 unless the issuer is the configured JWT issuer, set either by the caller's token or by `Aegis-Token-Issuer`. Effort: small.
- B. Always require the `Aegis-Token-Issuer` header. Effort: small; a little more friction.
- **Recommended: A.**

**O-8 Pinned transport has no proxy support, HTTP/2 or connection reuse (Medium)**

- A. Add CONNECT-proxy support (the egress check stays on the final target), then a pinned keep-alive pool keyed by host and address. Effort: medium.
- B. Allow a per-source `transport: jdk` opt-out. Effort: small. Reopens the DNS rebinding window for that source.
- C. Leave it and document it. Effort: none.
- **Recommended: A, proxy first.** B is not recommended.

**O-9 JWKS fetch is not pinned (Low)**

- A. Route JWKS through `PinnedHttp` with the IdP host as a dedicated allow entry. Effort: small.
- B. Leave it. There is no egress check to race and the URL is HTTPS-only.
- **Recommended: A, low priority.**

**O-10 PII redaction is regex only (Low)**

- A. Keep the regexes, and use HANDLE_ONLY for tools that return personal records. Effort: none.
- B. Ship a reference remote hook backed by a PII classifier (for example Microsoft Presidio, MIT licence). Effort: medium.
- C. Add locale packs (EU national IDs, UK NINO, India Aadhaar and others). Effort: small; more false positives.
- **Recommended: A by default; B for regulated data.**

**O-11 Audit file can be rewritten by someone with file access (Low)**

- A. Write checkpoints to write-once storage (S3 Object Lock or a WORM volume). Effort: setup.
- B. Copy every record to a SIEM through `audit.sinks`. Effort: setup.
- C. Sign each checkpoint with a host key, so a rewritten chain cannot carry a valid signature. Effort: small.
- **Recommended: C plus A.**

**O-12 No SAST or dependency scan in the build (Process)**

- A. SpotBugs with FindSecBugs as a Maven plugin. Runs offline in every build. Effort: small; some triage at first.
- B. CodeQL in GitHub Actions. Effort: small; CI only.
- C. Semgrep with Java rules in CI. Effort: small.
- D. Dependency scanning with `osv-scanner` or OWASP Dependency-Check, and the CycloneDX SBOM on every build instead of only under `-Prelease`. Effort: small.
- **Recommended: A plus D. Add B when the repository is on GitHub.**

**O-13 Admin retention uses the system clock (Low)**

- A. Pass the gateway clock into `withRevocations` and `withResultRelease`. Effort: trivial.
- **Recommended: A.**

### Items that need a component outside Aegis (E-1 to E-7)

**E-1 Sandbox runtime for stdio servers**

- A. Kubernetes: gVisor (`runsc`) RuntimeClass. The chart gate already requires `runtimeClassName`.
- B. Linux VMs: `bubblewrap` or `firejail` as the command prefix, with no network namespace.
- C. One container per server (Docker or Podman), with no extra sandbox.
- D. Windows hosts: refuse stdio sources in complete control, because Windows has no equivalent prefix today.
- **Recommended: A on Kubernetes, B on VMs, and D.**

**E-2 Helm and NetworkPolicy unproven**

- A. Run `helm template` plus `kubeconform` against `ci/complete-control-values.yaml` in CI.
- B. Integration test on a `kind` cluster with NetworkPolicy deny probes.
- **Recommended: A now; B before GA.**

**E-3 Upstream image scanning and signing**

- A. Trivy or Grype scan, cosign signature, then an admission controller (Kyverno or a small signer job) that signs Aegis's `attestationStatement` only after both pass.
- B. A manual reviewer signs the statement.
- **Recommended: A.** B is acceptable for a pilot.

**E-4 Clients bypassing Aegis (shadow MCP)**

- A. Network: developer and agent subnets may reach MCP servers only through Aegis. Endpoint: MDM-managed IDE configuration that pins the Aegis URL.
- B. A periodic discovery scan for unmanaged MCP endpoints.
- **Recommended: A plus B.**

**E-5 Unbuilt complete-control items**

| Item | Options | Recommended |
| --- | --- | --- |
| Live signed catalog reload | A. Build-then-swap reload command. B. Keep restart-to-apply (fails safe). | A, first. Aegis-ownable. |
| Adapter-digest binding | A. Adapter manifest plus `ArtifactAdmission` role `adapter`, bound into the action digest. B. Leave the contract bound only by tool policy digest. | A, second. |
| Tenant CredentialLease | A. Vault dynamic secrets behind `CredentialProvider`. B. Rely on RFC 8693 token exchange. | B now; A for databases without OAuth. |
| ResourceBroker | A. A broker MCP server that hands out opaque file handles (`FILE_BROKERED`). B. Resource scopes plus a read-only mount. | B now; A for file-heavy tools. |
| Taint and provenance tokens | A. Implement now in Aegis only. B. Wait for MCP client or spec support and keep HANDLE_ONLY meanwhile. | B. A gateway alone cannot see the next turn. |

**E-6 Flaws inside backend servers**

- A. Patch servers, run them least-privileged, and classify risky tools DESTRUCTIVE so every call needs approval.
- B. Sandbox each server (E-1) with its own egress policy.
- **Recommended: A plus B.**

**E-7 Model-side risks (LLM01 direct, LLM03, LLM04, LLM07, LLM08, LLM09)**

- A. Model-provider guardrails, for example Amazon Bedrock Guardrails or the provider's own safety filters.
- B. Client policy (system prompt hygiene, retrieval access control in the vector store).
- C. Aegis remote hooks for citation checks (LLM09) using the result digests Aegis already journals.
- **Recommended: A plus B; C optional.**

### Recommended set in one line

O-1 B, O-2 B, O-3 A, O-4 B, O-5 A, O-6 A+B, O-7 A, O-8 A, O-9 A, O-10 A, O-11 C+A, O-12 A+D, O-13 A; E-1 A/B+D, E-2 A, E-3 A, E-4 A+B, E-5 reload and adapter binding first, E-6 A+B, E-7 A+B.
'''
s = s[:start] + new
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
