from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
root = str(_REPO)
p = root + r'\aegis-config\src\main\java\io\github\vaquarkhan\aegis\config\GatewayFactory.java'
raw = open(p, encoding='utf-8', newline='').read()
crlf = '\r\n' in raw
t = raw.replace('\r\n', '\n')

line = '        requirePluginDigest(completeControlNode, callerContext);\n'
n = t.count(line)
while t.count(line) > 1:
    i = t.rindex(line)
    t = t[:i] + t[i + len(line):]
print('requirePluginDigest calls', n, '->', t.count(line))

start_marker = '''    /**
     * Host-level setting holding the SHA-256 of the {@code completeControl.artifacts} list'''
end_marker = '''    /** JVM system property alternative to {@link #REQUIRED_CATALOG_KEY}, for hosts such as Knox. */'''
print('blocks', t.count(start_marker))
while t.count(start_marker) > 1:
    second = t.rindex(start_marker)
    second_end = t.index(end_marker, second) + len(end_marker)
    t = t[:second] + t[second_end:]
assert t.count(start_marker) == 1 and t.count(end_marker) == 1
if crlf:
    t = t.replace('\n', '\r\n')
open(p, 'w', encoding='utf-8', newline='').write(t)
print('ok')
