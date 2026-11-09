from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
root = str(_REPO)

def dedupe(path, block):
    p = root + path
    raw = open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in raw
    s = raw.replace('\r\n', '\n')
    n = s.count(block)
    assert n >= 1, (path, block[:80])
    while s.count(block) > 1:
        i = s.rindex(block)
        s = s[:i] + s[i + len(block):]
    if crlf:
        s = s.replace('\n', '\r\n')
    open(p, 'w', encoding='utf-8', newline='').write(s)
    print(path, 'had', n)

dedupe(r'\aegis-config\src\main\java\io\github\vaquarkhan\aegis\config\Gateway.java',
       '''    private final boolean durableState;
''')
dedupe(r'\aegis-config\src\main\java\io\github\vaquarkhan\aegis\config\Gateway.java',
       '''    /**
     * True when governance state (approvals, nonces, revocations) is in a durable or host-provided store,
     * false for the built-in single-replica in-memory store. Hosts refuse revocation-dependent settings
     * when this is false (a restart would make a revoked token valid again).
     */
    public boolean durableState() {
        return durableState;
    }

''')
s = open(root + r'\aegis-config\src\main\java\io\github\vaquarkhan\aegis\config\GatewayFactory.java', encoding='utf-8', newline='').read()
start_marker = '''    /**
     * Host-level setting holding the SHA-256 of the {@code completeControl.artifacts} list'''
end_marker = '''    /** JVM system property alternative to {@link #REQUIRED_CATALOG_KEY}, for hosts such as Knox. */'''
crlf = '\r\n' in s
t = s.replace('\r\n', '\n')
first = t.index(start_marker)
first_end = t.index(end_marker, first) + len(end_marker)
block = t[first:first_end]
assert t.count(start_marker) == 2, t.count(start_marker)
second = t.index(start_marker, first_end)
second_end = t.index(end_marker, second) + len(end_marker)
t = t[:second] + t[second_end:]
# the first block already ends with end_marker; make sure exactly one end_marker remains
assert t.count(end_marker) == 1
if crlf:
    t = t.replace('\n', '\r\n')
open(root + r'\aegis-config\src\main\java\io\github\vaquarkhan\aegis\config\GatewayFactory.java', 'w', encoding='utf-8', newline='').write(t)
print('factory deduped')
