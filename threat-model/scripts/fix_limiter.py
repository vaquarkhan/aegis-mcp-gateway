from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_REPO / r"aegis-mcp-security/src/main/java/io/github/vaquarkhan/aegis/security/perimeter/ClientAddressLimiter.java")
s = open(p, encoding='utf-8').read()
def rep(old, new):
    global s
    assert old in s, old[:90]
    s = s.replace(old, new, 1)

# R1: opt-in defaults. Behind a load balancer or ingress without trustedProxies every client shares the
# proxy address, so an on-by-default lockout would let one attacker lock out everyone.
rep('''        public static final Settings DEFAULT = new Settings(1200, 20, 300, List.of(), 100_000);''',
    '''        /**
         * Off by default. Behind a load balancer or ingress that is not listed in {@code trustedProxies}
         * every client shares the proxy's address, so an on-by-default lockout would let one attacker
         * lock every caller out with a handful of bad tokens. Enable together with trustedProxies.
         */
        public static final Settings DEFAULT = new Settings(0, 0, 300, List.of(), 100_000);''')

rep('''    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
''', '''    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong lastSweep = new java.util.concurrent.atomic.AtomicLong();
''')

# R3: IPv6 clients are bucketed by /64 (one subscriber usually owns a whole /64).
rep('''        if (proxies.isEmpty() || !trusted(peer) || forwardedFor == null || forwardedFor.isEmpty()) {
            return peer.getHostAddress();
        }''', '''        if (proxies.isEmpty() || !trusted(peer) || forwardedFor == null || forwardedFor.isEmpty()) {
            return bucket(peer);
        }''')
rep('''            InetAddress a = literal(hops.get(i));
            if (a == null) {
                return peer.getHostAddress();
            }
            if (!trusted(a)) {
                return a.getHostAddress();
            }
        }
        return peer.getHostAddress();
    }''', '''            InetAddress a = literal(hops.get(i));
            if (a == null) {
                return bucket(peer);
            }
            if (!trusted(a)) {
                return bucket(a);
            }
        }
        return bucket(peer);
    }

    /**
     * Limit key for an address. IPv4 is the address itself. IPv6 is its /64 prefix: one subscriber is
     * normally given a whole /64, so per-address keys would let one client rotate through 2^64 keys.
     */
    static String bucket(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length != 16) {
            return a.getHostAddress();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            sb.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff))).append(':');
        }
        return sb.append(":/64").toString();
    }''')

# R4: sweeping a full map is O(n); do it at most once per second so a spray of new keys cannot turn
# every request into a full scan.
rep('''        sweep(map, now);
        return map.size() < settings.maxTracked() ? client : OVERFLOW;''', '''        long last = lastSweep.get();
        if (now - last >= 1_000 && lastSweep.compareAndSet(last, now)) {
            sweep(map, now);
        }
        return map.size() < settings.maxTracked() ? client : OVERFLOW;''')
open(p, 'w', encoding='utf-8', newline='').write(s)

# R2: only a presented-and-rejected credential counts as a failure. MCP OAuth discovery starts with an
# unauthenticated request that must get a 401 challenge; counting it would lock out legitimate clients.
a = str(_REPO / r"aegis-gateway/src/main/java/io/github/vaquarkhan/aegis/gateway/AegisServer.java")
t = open(a, encoding='utf-8').read()
old = '''    private void afterAdmission(String client, OwnServerPerimeter.Admission admission) {
        if (!admission.allowed() && admission.status() == 401) {
            limiter.authenticationFailed(client, System.currentTimeMillis());
        }
    }'''
new = '''    /**
     * Counts a failure only when a credential was presented and rejected. An MCP client's first,
     * unauthenticated request is part of OAuth discovery (it needs the 401 challenge) and is not an
     * attack, so it never moves an address towards lockout.
     */
    private void afterAdmission(String client, HttpExchange ex, OwnServerPerimeter.Admission admission) {
        if (!admission.allowed() && admission.status() == 401 && ex.getRequestHeaders().containsKey("Authorization")) {
            limiter.authenticationFailed(client, System.currentTimeMillis());
        }
    }'''
assert old in t
t = t.replace(old, new, 1)
assert t.count('afterAdmission(client, admission);') == 2
t = t.replace('afterAdmission(client, admission);', 'afterAdmission(client, ex, admission);')
open(a, 'w', encoding='utf-8', newline='').write(t)

o = str(_REPO / r"aegis-mcp-security/src/main/java/io/github/vaquarkhan/aegis/security/perimeter/OwnServerSecuritySettings.java")
u = open(o, encoding='utf-8').read()
old = '''            limits = new ClientAddressLimiter.Settings(lim.integer("requestsPerMinute", 1200),
                    lim.integer("authFailuresPerMinute", 20), lim.integer("lockoutSeconds", 300),'''
new = '''            limits = new ClientAddressLimiter.Settings(lim.integer("requestsPerMinute", 0),
                    lim.integer("authFailuresPerMinute", 0), lim.integer("lockoutSeconds", 300),'''
assert old in u
u = u.replace(old, new, 1)
u = u.replace(''' *   limits: {requestsPerMinute: 1200, authFailuresPerMinute: 20, lockoutSeconds: 300,   # per client address, pre-auth''',
              ''' *   limits: {requestsPerMinute: 1200, authFailuresPerMinute: 20, lockoutSeconds: 300,   # per client address, pre-auth; off unless set''', 1)
open(o, 'w', encoding='utf-8', newline='').write(u)

# R5: handles are per call, not per content, so two callers with identical results cannot share (or
# overwrite) a handle and defeat the producer-cannot-release rule; storing never overwrites.
op = str(_REPO / r"aegis-engine/src/main/java/io/github/vaquarkhan/aegis/engine/catalog/OutputPolicy.java")
v = open(op, encoding='utf-8').read()
old = '''        String digest = Digests.canonicalSha256(input.body());
        String handle = "result:" + digest.substring("sha256:".length());'''
new = '''        String digest = Digests.canonicalSha256(input.body());
        // The handle names this call's result, not the content: identical results of two calls get
        // different handles, so one caller's retained copy can never be addressed through another's.
        String handle = "result:" + Digests.sha256Hex("aegis-result-handle-v1\\n" + requestId + "\\n" + digest);'''
assert old in v
v = v.replace(old, new, 1)
open(op, 'w', encoding='utf-8', newline='').write(v)

rh = str(_REPO / r"aegis-engine/src/main/java/io/github/vaquarkhan/aegis/engine/control/ResultHandleStore.java")
w = open(rh, encoding='utf-8').read()
old = '''        store.put(PREFIX + handle, json, clock.instant().plus(ttl));
        return true;'''
new = '''        try {
            // Never overwrite: a retained entry keeps its producer for the separation-of-duties check.
            return store.putIfAbsent(PREFIX + handle, json, clock.instant().plus(ttl));
        } catch (IllegalStateException full) {
            return false; // store full: the handle stays opaque, the call is not failed after it ran
        }'''
assert old in w
w = w.replace(old, new, 1)
open(rh, 'w', encoding='utf-8', newline='').write(w)
print('ok')
