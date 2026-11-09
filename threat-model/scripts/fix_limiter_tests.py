from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
root = str(_REPO)

def edit(path, pairs):
    p = root + path
    s = open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (path, old[:90])
        s = s.replace(old, new, 1)
    open(p, 'w', encoding='utf-8', newline='').write(s)

edit(r'\aegis-mcp-security\src\test\java\io\github\vaquarkhan\aegis\security\perimeter\ClientAddressLimiterTest.java', [
    ('''        assertEquals("2001:db8:0:0:0:0:0:1", l.clientAddress(ip("10.1.2.3"), List.of("[2001:db8::1]")));''',
     '''        assertEquals("2001:db8:0:0::/64", l.clientAddress(ip("10.1.2.3"), List.of("[2001:db8::1]")));
        // IPv6 is limited per /64: rotating the interface id inside one subscriber prefix gains nothing.
        assertEquals(l.clientAddress(ip("2001:db8:aa:bb::1"), null), l.clientAddress(ip("2001:db8:aa:bb:ffff::9"), null));
        assertTrue(!l.clientAddress(ip("2001:db8:aa:bb::1"), null).equals(l.clientAddress(ip("2001:db8:aa:bc::1"), null)));'''),
    ('''        assertEquals(ClientAddressLimiter.Settings.DEFAULT, noProxies.settings());''',
     '''        assertEquals(ClientAddressLimiter.Settings.DEFAULT, noProxies.settings());
        assertEquals(0, ClientAddressLimiter.Settings.DEFAULT.requestsPerMinute(), "opt-in: a shared proxy address must not be limited by default");
        assertEquals(0, ClientAddressLimiter.Settings.DEFAULT.authFailuresPerMinute(), "opt-in lockout");'''),
])

edit(r'\aegis-gateway\src\test\java\io\github\vaquarkhan\aegis\gateway\ThreatModelGapServerTest.java', [
    ('''            for (int i = 0; i < 4; i++) {
                assertEquals(401, call(http, port, "bad.token." + i, attacker).statusCode());
            }''',
     '''            // Unauthenticated requests (MCP OAuth discovery starts with one) are answered 401 but never
            // count towards lockout: only a presented and rejected credential does.
            Map<String, String> discovering = Map.of("X-Forwarded-For", "192.0.2.50");
            for (int i = 0; i < 6; i++) {
                assertEquals(401, call(http, port, null, discovering).statusCode());
            }
            assertEquals(200, call(http, port, good, discovering).statusCode(), "discovery did not lock the client out");
            for (int i = 0; i < 4; i++) {
                assertEquals(401, call(http, port, "bad.token." + i, attacker).statusCode());
            }'''),
])

edit(r'\aegis-mcp\src\test\java\io\github\vaquarkhan\aegis\mcp\admin\AdminApiGapTest.java', [
    ('''        assertFalse(handles.put("result:" + "b".repeat(64), "t", "r", TestEngines.ALICE, Map.of("x", "y".repeat(5000))),
                "too large to retain");''',
     '''        assertFalse(handles.put("result:" + "b".repeat(64), "t", "r", TestEngines.ALICE, Map.of("x", "y".repeat(5000))),
                "too large to retain");
        assertFalse(handles.put(handle, "list_things", "req-2", TestEngines.BOB, Map.of("x", "other")),
                "an existing handle is never overwritten (its producer stays bound)");
        // Handles are per call: two calls with byte-identical results get different handles.
        io.github.vaquarkhan.aegis.engine.model.ToolResult same = io.github.vaquarkhan.aegis.engine.model.ToolResult.text("same", false);
        String h1 = io.github.vaquarkhan.aegis.engine.catalog.OutputPolicy.HANDLE_ONLY.release(same, "req-a").handle();
        String h2 = io.github.vaquarkhan.aegis.engine.catalog.OutputPolicy.HANDLE_ONLY.release(same, "req-b").handle();
        assertTrue(h1.matches("result:[0-9a-f]{64}") && !h1.equals(h2), h1 + " " + h2);'''),
])
print('ok')
