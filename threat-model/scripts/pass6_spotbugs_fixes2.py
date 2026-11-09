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

C = r'\aegis-config\src\main\java\io\github\vaquarkhan\aegis\config'
edit(C + r'\ConfigLoader.java', [
    ('''        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);''',
     '''        byte[] bytes = Files.readAllBytes(file);
        Path fileName = file.getFileName();
        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);'''),
])
edit(C + r'\openapi\OpenApiDraft.java', [
    ('''        String text = Files.readString(file, StandardCharsets.UTF_8);
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);''',
     '''        String text = Files.readString(file, StandardCharsets.UTF_8);
        Path fileName = file.getFileName();
        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);'''),
])
edit(C + r'\GatewayFactory.java', [
    ('''        boolean stdioSource;
        boolean pluginSource;
''', ''),
    ('''                    sb.stdioSource = true;
''', ''),
    ('''                sb.pluginSource = true;
''', ''),
])
edit(C + r'\secrets\VaultKvSecretProvider.java', [
    ('''    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*");''',
     '''    // Possessive quantifiers: no backtracking, so the pattern is linear in the input (REDOS detector).
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]++(?:/[A-Za-z0-9_.-]++)*+");'''),
])
print('ok')
