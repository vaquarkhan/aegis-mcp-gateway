from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
root = str(_REPO)

def edit(path, pairs):
    p = root + path
    raw = open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in raw
    s = raw.replace('\r\n', '\n')
    for old, new in pairs:
        if new in s and old not in s:
            continue
        assert old in s, (path, old[:100])
        assert s.count(old) == 1, (path, 'ambiguous', old[:100])
        s = s.replace(old, new, 1)
    if crlf:
        s = s.replace('\n', '\r\n')
    open(p, 'w', encoding='utf-8', newline='').write(s)

E = r'\aegis-engine\src\main\java\io\github\vaquarkhan\aegis\engine'
# UNSAFE_HASH_EQUALS: digest comparisons in constant time (the digests are of public definitions, but the
# accept route compares an operator-supplied value, so constant time costs nothing and removes the class).
edit(E + r'\catalog\CatalogIntegrity.java', [
    ('return pinned.equals(digest) ? Verdict.MATCH : Verdict.MISMATCH;',
     'return io.github.vaquarkhan.aegis.engine.model.Digests.constantTimeEquals(pinned, digest) ? Verdict.MATCH : Verdict.MISMATCH;'),
])
edit(E + r'\catalog\ToolCatalog.java', [
    ('boolean changed = prev == null || !prev.digest().equals(digest) || prev.status() != e.status();',
     'boolean changed = prev == null || !Digests.constantTimeEquals(prev.digest(), digest) || prev.status() != e.status();'),
    ('if (e == null || e.active() || expectedDigest == null || !expectedDigest.equals(e.digest())) {',
     'if (e == null || e.active() || expectedDigest == null || !Digests.constantTimeEquals(expectedDigest, e.digest())) {'),
])
edit(E + r'\content\ContentGovernor.java', [
    ('if (e == null || e.active() || expectedDigest == null || !expectedDigest.equals(e.digest())) {',
     'if (e == null || e.active() || expectedDigest == null || !Digests.constantTimeEquals(expectedDigest, e.digest())) {'),
    ('boolean changed = prev == null || !prev.digest().equals(digest) || prev.active() != active;',
     'boolean changed = prev == null || !Digests.constantTimeEquals(prev.digest(), digest) || prev.active() != active;'),
])
print('ok')
