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
        s = s.replace(old, new, 1)
    if crlf:
        s = s.replace('\n', '\r\n')
    open(p, 'w', encoding='utf-8', newline='').write(s)

# Move the revocation check into the bridge so it is unit-testable without a Knox runtime.
edit(r'\aegis-knox3-extension\src\main\java\io\github\vaquarkhan\aegis\knox\rs\AegisMcpResource.java', [
    ('''        CallerIdentity caller = KnoxIdentityBridge.fromCurrentSubject(topology);
        KnoxGatewayRegistry.Holder h = holder();
        if (caller != null && h.gateway() != null && h.gateway().revocations() != null) {
            // O-6 A: gateway-side subject revocation applies on the Knox path too. Knox gives no token id
            // or issue time, so a subject entry refuses every request for that subject (and for any actor in
            // its delegation chain) until the entry expires. Revoke through POST .../admin/revocations/subject/{sub}.
            io.github.vaquarkhan.aegis.engine.control.TokenRevocations revs = h.gateway().revocations();
            revs.registerIssuer(caller.issuer());
            if (revs.check(caller.issuer(), null, caller.effectiveSubject(), null) != null
                    || revs.check(caller.issuer(), null, caller.actor(), null) != null) {
                return null;
            }
            for (String actor : caller.actorChain()) {
                if (revs.check(caller.issuer(), null, actor, null) != null) {
                    return null;
                }
            }
        }
        return caller;
    }''', '''        CallerIdentity caller = KnoxIdentityBridge.fromCurrentSubject(topology);
        KnoxGatewayRegistry.Holder h = holder();
        // O-6 A: gateway-side subject revocation applies on the Knox path too.
        return KnoxIdentityBridge.unlessRevoked(caller, h.gateway() == null ? null : h.gateway().revocations());
    }'''),
])

edit(r'\aegis-knox3-extension\src\main\java\io\github\vaquarkhan\aegis\knox\KnoxIdentityBridge.java', [
    ('''    /** @return the caller, or null when Knox established no authenticated subject */
    public static CallerIdentity fromCurrentSubject(String topology) {''',
     '''    /**
     * Gateway-side subject revocation on the Knox path. Knox gives no token id or issue time, so a
     * subject entry refuses every request for that subject, for the primary actor and for any actor in
     * the delegation chain until the entry expires (revoke through POST .../admin/revocations/subject/{sub}
     * with the Knox issuer). Registers the Knox issuer so such revocations are accepted.
     *
     * @return the caller, or null when the caller or one of its actors is revoked
     */
    public static CallerIdentity unlessRevoked(CallerIdentity caller,
                                               io.github.vaquarkhan.aegis.engine.control.TokenRevocations revocations) {
        if (caller == null || revocations == null) {
            return caller;
        }
        revocations.registerIssuer(caller.issuer());
        if (revocations.check(caller.issuer(), null, caller.effectiveSubject(), null) != null
                || revocations.check(caller.issuer(), null, caller.actor(), null) != null) {
            return null;
        }
        for (String actor : caller.actorChain()) {
            if (revocations.check(caller.issuer(), null, actor, null) != null) {
                return null;
            }
        }
        return caller;
    }

    /** @return the caller, or null when Knox established no authenticated subject */
    public static CallerIdentity fromCurrentSubject(String topology) {'''),
])
print('ok')
