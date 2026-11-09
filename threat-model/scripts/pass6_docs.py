from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
base = str(_TM)

def edit(path, pairs):
    p = base + path
    s = open(p, encoding='utf-8').read()
    for old, new in pairs:
        if new in s and old not in s:
            continue
        assert old in s, (path, old[:100])
        s = s.replace(old, new, 1)
    open(p, 'w', encoding='utf-8', newline='').write(s)

part_m = '''## Part M. Fifth and sixth passes: scanner readiness and the chosen options

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

'''
edit(r'\DETAILED-FINDINGS-AND-FIXES.md', [
    ('## Appendix. How to re-run everything', part_m + '## Appendix. How to re-run everything'),
])

edit(r'\OWASP-TOP10-COVERAGE.md', [
    ('''| Tool results | `screening.results` | AUDIT | ENFORCE, OFF refused |''',
     '''| Tool results | `screening.results` | ENFORCE (sixth pass; was AUDIT) | ENFORCE, OFF refused |'''),
    ('''Screening normalises before matching (zero-width characters, case, whitespace, nested JSON, base64) (`InjectionScreen`, Fix 9). Proof: `tm12`, `CLOSE-03b`.''',
     '''Screening normalises before matching (zero-width characters, case, whitespace, nested JSON, base64, and since the sixth pass Unicode confusables such as Cyrillic or Greek look-alikes, plus German, French, Spanish, Italian and Portuguese phrase packs) (`InjectionScreen`, Fix 9, O-1 B). Proof: `tm12`, `CLOSE-03b`, `SCAN-O1`.'''),
    ('''| MCP01 Token mismanagement and secret exposure | FULL_GATEWAY_PATH | Caller token never forwarded; per-caller token exchange; output secret redaction; jti and revocation |''',
     '''| MCP01 Token mismanagement and secret exposure | FULL_GATEWAY_PATH | Caller token never forwarded; per-caller token exchange; output secret redaction; jti and revocation (durable store required, delegation chain covered) |'''),
])

edit(r'\THREAT-MODEL.md', [
    ('''- Tool results are now screened (Fix 17, `screening.results`, AUDIT by default, ENFORCE by default in complete control).''',
     '''- Tool results are now screened (Fix 17, `screening.results`; ENFORCE by default since the sixth pass, AUDIT is opt-in).'''),
])

edit(r'\README.md', [
    ('''| `AI-SCANNER-READINESS.md` |''',
     '''| `DECISIONS.md` | Sixth pass: for every open item, the options, the one chosen, why, and the proof; deployment choices for the items outside Aegis |
| `evidence/maven-pass6-all-modules.log`, `maven-pass6-resume.log` | Sixth-pass regression, eight modules including the Knox extension, with license, coverage and SpotBugs plus FindSecBugs gates on |
| `evidence/pass6-spotbugs-*.log` | First static-analysis runs and the triage trail (72 findings down to 0 with fixes and justified exclusions) |
| `AI-SCANNER-READINESS.md` |'''),
])

edit(r'\run-threat-model.ps1', [
    ('''    & $Mvn -o -pl aegis-engine,aegis-mcp,aegis-config,aegis-mcp-security,aegis-gateway test `
        "-Dtest=ThreatModel*,PinnedHttpTest,DnsRebindingConfigTest,CompleteControlConfigTest,AdminApiGapTest" `''',
     '''    & $Mvn -o -pl aegis-engine,aegis-mcp,aegis-config,aegis-mcp-security,aegis-gateway,aegis-knox3-extension test `
        "-Dtest=ThreatModel*,PinnedHttpTest,DnsRebindingConfigTest,CompleteControlConfigTest,AdminApiGapTest,KnoxRevocationTest" `'''),
    ('''"-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" "-Djacoco.skip=true" *> $log''',
     '''"-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" "-Djacoco.skip=true" "-Drat.skip=true" *> $log'''),
])
print('ok')
