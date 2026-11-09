from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_TM / r"AI-SCANNER-READINESS.md")
s = open(p, encoding='utf-8').read()
start = s.index('## Decision options')
s = s[:start] + '''## Decisions and their implementation

The options for every item, the one chosen and the reason are recorded in `DECISIONS.md`. The sixth pass implemented the recommended option for O-1 to O-13 (O-10 by design, no code) and added CI steps for E-2 and O-12 B. Regression: `evidence/maven-pass6-all-modules.log`, eight modules including the Knox extension, with license, coverage and SpotBugs plus FindSecBugs gates on.

## Second re-review: what a frontier scanner would still report

Done after the sixth pass, with the same method as the first round: whole tree, data flow across modules, attempt to write an input that breaks each new control.

### Fixed during the re-review itself

| Id | Severity | Finding | Fix |
| --- | --- | --- | --- |
| R-6 | Medium (availability) | The first version of the pinned JWKS fetch (O-9) built its guard with no allow CIDRs, so an identity provider on a private address (the common corporate case) would have been refused and every login would have failed. | `JwtVerifier.jwksTransport` allows every address class for that one host; the pin closes the rebinding window only. |
| R-7 | Low | `AuditCheckpointVerifier` tried to remove `signature` from the parser's read-only map and threw. | Copy to a mutable map first. Caught by the new test before it shipped. |

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

### Honest summary

After six passes every finding that Aegis code can close is closed and tested, with three exceptions that are performance or scope limits rather than open attack paths (P-4, P-5, P-6). The remaining security-relevant items are a phrase list that is a tripwire by design (P-1), a signing key co-located with the journal until WORM storage is used (P-2), one control proven by compilation only (P-3), and the items that need a runtime, a cluster or a CI service this Windows host does not have (P-3, P-9, P-10). A frontier scanner would report P-1 and P-2 as findings and the rest as notes.
'''
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
