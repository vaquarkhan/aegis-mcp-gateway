from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_REPO / r"aegis-mcp-security/src/main/java/io/github/vaquarkhan/aegis/security/perimeter/OwnServerSecuritySettings.java")
s = open(p, encoding='utf-8').read()
old = '''                                 Map<OwnServerPerimeter.RouteGroup, Set<String>> requiredScopes,
                                 boolean requireClientCert, Path clientCaFile) {

    public OwnServerSecuritySettings {
        requiredScopes = requiredScopes == null ? Map.of() : Map.copyOf(requiredScopes);
    }
'''
new = '''                                 Map<OwnServerPerimeter.RouteGroup, Set<String>> requiredScopes,
                                 boolean requireClientCert, Path clientCaFile,
                                 ClientAddressLimiter.Settings limits, String adminHost, int adminPort) {

    public OwnServerSecuritySettings {
        requiredScopes = requiredScopes == null ? Map.of() : Map.copyOf(requiredScopes);
        limits = limits == null ? ClientAddressLimiter.Settings.DEFAULT : limits;
    }

    /** True when the admin API has its own listener ({@code host.adminListen}) instead of sharing the MCP one. */
    public boolean separateAdminListener() {
        return adminPort >= 0;
    }
'''
assert old in s
s = s.replace(old, new, 1)
old2 = '''        root.finish();
        return new OwnServerSecuritySettings(host, port, mcpPath, adminPath, threads, keystore, pw, ksType, resolver, stdioId, maxRequestBytes,
                metricsPath, metricsGroups, mcpGroups, adminGroups, corsPolicy, oauth, requiredScopes, requireClientCert, clientCaFile);'''
new2 = '''        // Pre-authentication limits per client address (before any token is verified).
        ConfigNode lim = root.child("limits");
        ClientAddressLimiter.Settings limits;
        try {
            limits = new ClientAddressLimiter.Settings(lim.integer("requestsPerMinute", 1200),
                    lim.integer("authFailuresPerMinute", 20), lim.integer("lockoutSeconds", 300),
                    lim.strings("trustedProxies"), (int) lim.integer("maxTracked", 100_000));
            new ClientAddressLimiter(limits); // validates trustedProxies now, not on the first request
        } catch (IllegalArgumentException e) {
            throw new ConfigException("config.host.limits: " + e.getMessage());
        }
        // Optional separate admin listener: the admin API is then not reachable on the MCP port at all.
        ConfigNode adminListen = root.child("adminListen");
        String adminHost = null;
        int adminPort = -1;
        if (adminListen.has("port")) {
            adminHost = adminListen.string("host", "127.0.0.1");
            adminPort = (int) adminListen.integer("port", 0);
            if (adminPort < 0 || adminPort > 65_535 || (adminPort == port && port != 0 && adminHost.equals(host))) {
                throw adminListen.error("port", "must be a free port distinct from listen.port");
            }
            if (keystore == null && !isLoopback(adminHost) && !allowPlaintext) {
                throw adminListen.error("host", "is not a loopback address and tls.keystore is not set");
            }
        } else if (adminListen.has("host")) {
            throw adminListen.error("host", "needs adminListen.port");
        }
        root.finish();
        return new OwnServerSecuritySettings(host, port, mcpPath, adminPath, threads, keystore, pw, ksType, resolver, stdioId, maxRequestBytes,
                metricsPath, metricsGroups, mcpGroups, adminGroups, corsPolicy, oauth, requiredScopes, requireClientCert, clientCaFile,
                limits, adminHost, adminPort);'''
assert old2 in s
s = s.replace(old2, new2, 1)
old3 = ''' *   stdio: {actor: local-dev, groups: [analysts]}'''
new3 = ''' *   stdio: {actor: local-dev, groups: [analysts]}
 *   limits: {requestsPerMinute: 1200, authFailuresPerMinute: 20, lockoutSeconds: 300,   # per client address, pre-auth
 *            trustedProxies: [10.0.0.0/8], maxTracked: 100000}   # X-Forwarded-For believed only from these
 *   adminListen: {host: 127.0.0.1, port: 9443}             # optional: admin API on its own listener'''
assert old3 in s
s = s.replace(old3, new3, 1)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
