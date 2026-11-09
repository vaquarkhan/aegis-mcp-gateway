# threat-model/

Security review of Aegis Gateway 2.0 (base commit `2f7b944`, continued on branch `threat-modeling`), 6–8 November 2026. Analysis, CVE census, containment demo, and the hardening that landed in the gateway tree.

| Path | What it is |
|---|---|
| `DETAILED-FINDINGS-AND-FIXES.md` | Long form attack log, class and method for every fix, before/after code, live vulnerable-server proof, capability boundary and Part I residual controls |
| `COMPLETE-CONTROL-EXPLORATION.md` | Design for expanding Aegis from a gateway process into a managed endpoint, artifact-admission, sandboxed-runtime and output-declassification control plane |
| `complete-control-plane.svg` | Logical view of that expanded security boundary |
| `THREAT-MODEL.md` | Trust boundaries, STRIDE analysis, CVE-class coverage and runtime verdicts |
| `OWASP-TOP10-COVERAGE.md` | All 20 OWASP MCP Top 10 and LLM Top 10 risks: closure state, class, method and test; what a gateway cannot own; prompt-redaction answer |
| `DECISIONS.md` | Sixth pass: for every open item, the options, the one chosen, why, and the proof; deployment choices for the items outside Aegis |
| `evidence/maven-pass6-final.log` | Sixth-pass regression in one uninterrupted eight-module run (Knox extension included) with license, coverage and SpotBugs plus FindSecBugs gates on: engine 165, mcp 63, otlp 7, config 90, security 30, gateway 38, a2a 8, knox 7. 408 tests, BUILD SUCCESS |
| `evidence/maven-pass6-all-modules.log`, `maven-pass6-resume.log` | The intermediate sixth-pass runs (kept for history) |
| `evidence/e2e-pass6-RESULTS.md` | Live vulnerable-server run after the sixth pass |
| `evidence/pass6-spotbugs-*.log` | First static-analysis runs and the triage trail (72 findings down to 0 with fixes and justified exclusions) |
| `AI-SCANNER-READINESS.md` | Fifth pass: what a frontier AI code scan (Mythos or GPT-5.3 class) would still report; five self-introduced defects fixed (R1 to R5) and 13 open findings plus 7 external items with severity |
| `evidence/maven-pass5-all-modules.log` | Regression after R1 to R5: 397 tests, one uninterrupted run, BUILD SUCCESS |
| `AEGIS-VS-CONTEXTFORGE.md` | Side-by-side feature review against IBM ContextForge (and other MCP gateways): adopted, equivalent, Aegis ahead, not required, consider later |
| `e2e/` | Deliberately vulnerable MCP server, hardened Aegis profile, driver and current direct-versus-gateway results |
| `evidence/verdicts.md` | One row per tested attack or closure: public references, verdict and exact evidence text |
| `evidence/maven-pass4-all-modules.log` | Fourth-pass regression in one uninterrupted seven-module run with the RAT and JaCoCo gates on: engine 164, mcp 62, otlp 7, config 88, security 30, gateway 38, a2a 8. 397 tests, BUILD SUCCESS |
| `evidence/maven-gap-fixes-full-*.log` | The intermediate fourth-pass runs that found the two coverage gaps and the policy-test environment issue (kept for history) |
| `evidence/maven-pass3-all-modules.log` | Third-pass regression (kept for history) |
| `evidence/e2e-pass4-RESULTS.md` | Live run after the fourth pass (pinned transport, limiter and revocation code in the jar) |
| `evidence/e2e-pass3-RESULTS.md` | Retained live run after Fixes 15 to 18 |
| `evidence/e2e-pass3-results.json` | Machine-readable form of the same live run |
| `evidence/baseline-before-fixes.txt` | Findings observed before the first fixes |
| `evidence/gap02-against-original-store.log` | GAP-02 test failing against the original `InMemoryStateStore`, proving the test detects the bug |
| `evidence/gap02-against-fixed-store.log` | Same test passing against the fixed store |
| `patches/aegis-2.0-security-fixes.patch` | Cumulative unified diff of source, tests and chart changes; reverse-applies clean from the review tree |
| `tests/*.java` | Copies of the executable reproduction and closure suites |
| `cve-census/MCP-CVE-CENSUS.csv` | Machine-readable dated census of 561 unique NVD MCP CVEs with product, versions, CWE, CVSS, attack boundary, Aegis protection, mandatory external control and explicit closure state |
| `cve-census/MCP-CVE-CENSUS.md` | Reviewable table containing all 561 CVE IDs and links |
| `cve-census/census-metadata.json` | Query receipt, counts and SHA-256 of the CSV |
| `cve-census/raw/*.json` | Exact NVD API snapshots for all seven queries |
| `cve-census/build_census.py` | Repeatable normalization, filtering and conservative strongest-control mapping |
| `run-threat-model.ps1` | Re-runs the reproduction, gap and complete-control suites (five modules) and regenerates the verdict table |
| `scripts/*.py` | The edit scripts used to apply multi-line source changes (kept for traceability) |

Verdict meanings:

- **BLOCKED:** denied before the backend.
- **MITIGATED:** the backend ran, but the tested harm was contained.
- **FULL_MANAGED_PATH:** current gateway controls narrow the path and the mandatory endpoint/runtime/output control named in the row completes closure.
- **EXTERNAL_REQUIRED:** the affected action occurs outside the gateway; patching, endpoint policy, route closure or quarantine is mandatory.
- **FIXED:** this review found an Aegis weakness or missing gateway control, implemented it and proved the new behavior.
- **NO-ISSUE:** an apparent finding was already enforced elsewhere in the call path.

Pass six (Part M, `DECISIONS.md`) added 7 verdicts, all FIXED: SCAN-O1, O3, O4, O5, O6, O8 and O11. The regenerated table has 67 rows: 43 FIXED, 17 BLOCKED, 4 MITIGATED, 2 FULL_MANAGED_PATH and 1 NO-ISSUE.

Pass four (Parts K and L) added 16 verdicts, all FIXED: GAP-L1a to L1e, GAP-L2a to L2c, GAP-L3a and L3b, GAP-L4a to L4d, CC-K1 and CC-K2. After pass four the table had 60 rows: 36 FIXED, 17 BLOCKED, 4 MITIGATED, 2 FULL_MANAGED_PATH and 1 NO-ISSUE.

After pass two there were 35 verdicts: 16 BLOCKED, 2 MITIGATED, 2 now mapped to FULL_MANAGED_PATH, 14 FIXED and 1 NO-ISSUE. Pass three added 9 closure verdicts: 7 FIXED and 2 MITIGATED. Total before pass four: 44 runtime verdict rows. The separate dated NVD census contains 561 CVE rows: 22 FULL_GATEWAY_PATH, 297 FULL_MANAGED_PATH and 242 EXTERNAL_REQUIRED. The latest full regression is the fourth-pass set listed above.

The defensible security claim is scoped. Under the hardened profile, every attack in this review that travels through the gateway has a control that blocks or withholds it. `COMPLETE-CONTROL-EXPLORATION.md` defines what must be added to control endpoints and backend runtimes too. It does not claim that signed, scanned software has no unknown vulnerability.
