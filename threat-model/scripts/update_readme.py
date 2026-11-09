from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_TM / r"README.md")
s = open(p, encoding='utf-8').read()
def rep(old, new):
    global s
    assert old in s, old[:80]
    s = s.replace(old, new, 1)

rep('''| `THREAT-MODEL.md` | Trust boundaries, STRIDE analysis, CVE-class coverage and runtime verdicts |''',
    '''| `THREAT-MODEL.md` | Trust boundaries, STRIDE analysis, CVE-class coverage and runtime verdicts |
| `OWASP-TOP10-COVERAGE.md` | All 20 OWASP MCP Top 10 and LLM Top 10 risks: closure state, class, method and test; what a gateway cannot own; prompt-redaction answer |
| `AEGIS-VS-CONTEXTFORGE.md` | Side-by-side feature review against IBM ContextForge (and other MCP gateways): adopted, equivalent, Aegis ahead, not required, consider later |''')
rep('''| `evidence/maven-pass3-all-modules.log` | Latest five-module regression: engine 158, mcp 54, config 75, security 25, gateway 35, all passing with the RAT check on |''',
    '''| `evidence/maven-gap-fixes-full-2.log`, `-3.log`, `-4.log` | Fourth-pass regression with RAT and JaCoCo gates on: engine 164, mcp 62, otlp 7, config 88 (run 2); security 30 (run 3, resumed after adding limiter unit tests); gateway 38 and a2a 8 (run 4, resumed after the policy-test env fix). 397 tests, all passing |
| `evidence/maven-pass3-all-modules.log` | Third-pass regression (kept for history) |
| `evidence/e2e-pass4-RESULTS.md` | Live run after the fourth pass (pinned transport, limiter and revocation code in the jar) |''')
rep('''| `run-threat-model.ps1` | Re-runs the original suites and regenerates the verdict table |''',
    '''| `run-threat-model.ps1` | Re-runs the reproduction, gap and complete-control suites (five modules) and regenerates the verdict table |
| `scripts/*.py` | The edit scripts used to apply multi-line source changes (kept for traceability) |''')
rep('''After pass two there were 35 verdicts''', '''Pass four (Parts K and L) added 16 verdicts, all FIXED: GAP-L1a to L1e, GAP-L2a to L2c, GAP-L3a and L3b, GAP-L4a to L4d, CC-K1 and CC-K2. The regenerated table has 60 rows: 36 FIXED, 17 BLOCKED, 4 MITIGATED, 2 FULL_MANAGED_PATH and 1 NO-ISSUE.

After pass two there were 35 verdicts''')
rep('''Total: 44 runtime verdict rows.''', '''Total before pass four: 44 runtime verdict rows.''')
rep('''The latest full regression is `evidence/maven-pass3-all-modules.log`.''',
    '''The latest full regression is the fourth-pass set listed above.''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
