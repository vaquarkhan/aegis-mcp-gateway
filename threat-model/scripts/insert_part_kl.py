from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
base = str(_TM)
p = base + r'\DETAILED-FINDINGS-AND-FIXES.md'
s = open(p, encoding='utf-8').read()
kl = open(base + r'\part-kl.md', encoding='utf-8').read()
marker = '## Appendix. How to re-run everything'
assert marker in s and '## Part K.' not in s
s = s.replace(marker, kl + marker, 1)
s = s.replace('''# Full regression of the five touched modules:
cd aegis-mcp-gateway-20261104-2.0-review-ready
& C:\\tools\\apache-maven-3.9.11\\bin\\mvn.cmd -o -pl aegis-engine,aegis-mcp,aegis-config,aegis-mcp-security,aegis-gateway test''',
'''# Full regression of every touched module (fourth pass adds otlp and a2a):
cd aegis-mcp-gateway-20261104-2.0-review-ready
& C:\\tools\\apache-maven-3.9.11\\bin\\mvn.cmd -o -pl aegis-engine,aegis-mcp,aegis-observability-otlp,aegis-config,aegis-mcp-security,aegis-gateway,aegis-source-a2a install''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok', s.count('## Part K.'), s.count('## Part L.'))
