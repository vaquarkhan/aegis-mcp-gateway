from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_TM / r"DETAILED-FINDINGS-AND-FIXES.md")
s = open(p, encoding='utf-8').read()
pairs = [
    ('''  - A per-address request budget (`host.limits.requestsPerMinute`, default 1200).
  - A lockout after `authFailuresPerMinute` (default 20) rejected credentials, for `lockoutSeconds` (default 300). The answer is 429 with `Retry-After`.''',
     '''  - A per-address request budget (`host.limits.requestsPerMinute`, opt-in, 0 by default).
  - A lockout after `authFailuresPerMinute` (opt-in, 0 by default) rejected credentials, for `lockoutSeconds` (default 300). The answer is 429 with `Retry-After`. Only a presented and rejected credential counts; an unauthenticated OAuth-discovery request does not.
  - Both are off by default since the fifth pass (R1 in `AI-SCANNER-READINESS.md`): behind an ingress that is not in `trustedProxies`, every client shares one address and an on-by-default lockout would let one attacker lock everyone out.
  - IPv6 clients are limited per /64 prefix.'''),
    ('''| `host.limits.requestsPerMinute` | 1200 | Per-address pre-auth budget (0 off) |
| `host.limits.authFailuresPerMinute` | 20 | Failures before lockout (0 off) |''',
     '''| `host.limits.requestsPerMinute` | 0 (off) | Per-address pre-auth budget; enable with `trustedProxies` when behind a proxy |
| `host.limits.authFailuresPerMinute` | 0 (off) | Rejected credentials before lockout |'''),
]
for old, new in pairs:
    assert old in s, old[:80]
    s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
