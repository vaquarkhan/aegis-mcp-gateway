from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_TM / r"README.md")
s = open(p, encoding='utf-8').read()
pairs = [
    ('''| `evidence/maven-pass6-all-modules.log`, `maven-pass6-resume.log` | Sixth-pass regression, eight modules including the Knox extension, with license, coverage and SpotBugs plus FindSecBugs gates on |''',
     '''| `evidence/maven-pass6-final.log` | Sixth-pass regression in one uninterrupted eight-module run (Knox extension included) with license, coverage and SpotBugs plus FindSecBugs gates on: engine 165, mcp 63, otlp 7, config 90, security 30, gateway 38, a2a 8, knox 7. 408 tests, BUILD SUCCESS |
| `evidence/maven-pass6-all-modules.log`, `maven-pass6-resume.log` | The intermediate sixth-pass runs (kept for history) |
| `evidence/e2e-pass6-RESULTS.md` | Live vulnerable-server run after the sixth pass |'''),
    ('''Pass four (Parts K and L) added 16 verdicts, all FIXED''',
     '''Pass six (Part M, `DECISIONS.md`) added 7 verdicts, all FIXED: SCAN-O1, O3, O4, O5, O6, O8 and O11. The regenerated table has 67 rows: 43 FIXED, 17 BLOCKED, 4 MITIGATED, 2 FULL_MANAGED_PATH and 1 NO-ISSUE.

Pass four (Parts K and L) added 16 verdicts, all FIXED'''),
    ('''The regenerated table has 60 rows: 36 FIXED, 17 BLOCKED, 4 MITIGATED, 2 FULL_MANAGED_PATH and 1 NO-ISSUE.''',
     '''After pass four the table had 60 rows: 36 FIXED, 17 BLOCKED, 4 MITIGATED, 2 FULL_MANAGED_PATH and 1 NO-ISSUE.'''),
]
for old, new in pairs:
    assert old in s, old[:80]
    s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
