from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_REPO / r"aegis-config/src/main/java/io/github/vaquarkhan/aegis/config/secrets/VaultKvSecretProvider.java")
raw = open(p, encoding='utf-8', newline='').read()
crlf = '\r\n' in raw
s = raw.replace('\r\n', '\n')
pairs = [
    ('''    // Possessive quantifiers: no backtracking, so the pattern is linear in the input (REDOS detector).
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]++(?:/[A-Za-z0-9_.-]++)*+");''',
     '''    /** Plain KV path: non-empty segments of [A-Za-z0-9_.-] separated by single slashes. A linear scan, no regex. */
    static boolean plainPath(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }'''),
    ('''        if (!SEGMENT.matcher(mount).matches() || mount.contains("..")) {''',
     '''        if (!plainPath(mount) || mount.contains("..")) {'''),
    ('''        if (!SEGMENT.matcher(path).matches() || path.contains("..")) {''',
     '''        if (!plainPath(path) || path.contains("..")) {'''),
    ('''import java.util.regex.Pattern;
''', ''),
]
for old, new in pairs:
    assert old in s, old[:80]
    s = s.replace(old, new, 1)
if crlf:
    s = s.replace('\n', '\r\n')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
