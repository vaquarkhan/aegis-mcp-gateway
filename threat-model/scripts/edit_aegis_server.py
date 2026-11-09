from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_REPO / r"aegis-gateway/src/main/java/io/github/vaquarkhan/aegis/gateway/AegisServer.java")
s = open(p, encoding='utf-8').read()

def rep(old, new):
    global s
    assert old in s, old[:80]
    s = s.replace(old, new, 1)

rep('''import io.github.vaquarkhan.aegis.security.perimeter.OwnServerPerimeter;''',
    '''import io.github.vaquarkhan.aegis.security.identity.JwtVerifier;
import io.github.vaquarkhan.aegis.security.perimeter.ClientAddressLimiter;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerPerimeter;''')

rep('''    private final HttpServer server;
    private final ThreadPoolExecutor pool;
''', '''    private final HttpServer server;
    private final HttpServer adminServer;
    private final ThreadPoolExecutor pool;
    private final ClientAddressLimiter limiter;
''')

old_ctor = s[s.index('''    public AegisServer(Gateway gateway, OwnServerSecuritySettings settings) throws Exception {'''):s.index('''    public void start() {''')]
new_ctor = '''    public AegisServer(Gateway gateway, OwnServerSecuritySettings settings) throws Exception {
        applyServerTimeLimits();
        this.gateway = gateway;
        this.settings = settings;
        this.perimeter = new OwnServerPerimeter(settings);
        this.limiter = new ClientAddressLimiter(settings.limits());
        if (settings.cors().enabled() && !gateway.mcp().options().allowedOrigins().containsAll(settings.cors().allowedOrigins())) {
            throw new IllegalArgumentException("host.cors.allowedOrigins must be a subset of server.allowedOrigins; "
                    + "preflight must never advertise an origin the MCP handler will reject");
        }
        if (gateway.completeControl()) {
            // Complete control: the admin API is never reachable by "any authenticated caller" and never
            // shares the MCP listener, so an MCP client credential alone cannot reach an admin route.
            if (settings.adminGroups().isEmpty()) {
                throw new IllegalArgumentException("completeControl.enabled requires host.routes.adminGroups (the admin API"
                        + " must not be open to every authenticated caller)");
            }
            if (!settings.separateAdminListener()) {
                throw new IllegalArgumentException("completeControl.enabled requires host.adminListen (the admin API must"
                        + " not share the MCP listener)");
            }
        }
        // Gateway-side token revocation: the JWT verifier consults the gateway's shared state store.
        if (settings.identity() instanceof JwtVerifier jwt && gateway.revocations() != null) {
            jwt.bindRevocations(gateway.revocations());
        }
        SSLContext ssl = null;
        if (settings.keystore() != null) {
            KeyStore ks = KeyStore.getInstance(settings.keystoreType());
            try (InputStream in = Files.newInputStream(settings.keystore())) {
                ks.load(in, settings.keystorePassword());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, settings.keystorePassword());
            ssl = SSLContext.getInstance("TLS");
            javax.net.ssl.TrustManager[] clientTrust = settings.requireClientCert()
                    ? clientTrustManagers(settings.clientCaFile()) : null;
            ssl.init(kmf.getKeyManagers(), clientTrust, null);
        }
        server = listener(new InetSocketAddress(settings.host(), settings.port()), ssl);
        adminServer = settings.separateAdminListener()
                ? listener(new InetSocketAddress(settings.adminHost(), settings.adminPort()), ssl) : null;
        pool = boundedPool(settings.threads());
        server.setExecutor(pool);
        server.createContext(settings.mcpPath(), ex -> serve(ex, false));
        if (adminServer == null) {
            server.createContext(settings.adminPath(), ex -> serve(ex, true));
        } else {
            adminServer.setExecutor(pool);
            adminServer.createContext(settings.adminPath(), ex -> serve(ex, true));
            adminServer.createContext("/healthz", ex -> write(ex, McpHttpResponse.json(200, Map.of("status", "ok"))));
        }
        if (gateway.metrics() != null) {
            // Authenticated like /mcp (metrics name tools and sources); optionally restricted to groups.
            server.createContext(settings.metricsPath(), this::metrics);
        }
        if (settings.oauth() != null) {
            // RFC 9728 discovery: unauthenticated by design, and the only unauthenticated route besides health.
            server.createContext(settings.oauth().metadataPath(), this::protectedResourceMetadata);
        }
        server.createContext("/healthz", ex -> write(ex, McpHttpResponse.json(200, Map.of("status", "ok"))));
        server.createContext("/readyz", ex -> {
            List<ToolCatalog.Entry> entries = gateway.engine().catalog().entries();
            long active = entries.stream().filter(ToolCatalog.Entry::active).count();
            write(ex, McpHttpResponse.json(active > 0 ? 200 : 503,
                    Map.of("status", active > 0 ? "ready" : "no active tools", "activeTools", active)));
        });
    }

    private HttpServer listener(InetSocketAddress addr, SSLContext ssl) throws IOException {
        if (ssl == null) {
            return HttpServer.create(addr, 128);
        }
        HttpsServer https = HttpsServer.create(addr, 128);
        boolean needClientAuth = settings.requireClientCert();
        https.setHttpsConfigurator(new HttpsConfigurator(ssl) {
            @Override
            public void configure(com.sun.net.httpserver.HttpsParameters params) {
                javax.net.ssl.SSLParameters p = getSSLContext().getDefaultSSLParameters();
                // Mutual TLS: a client without a certificate that chains to clientCaFile fails the handshake.
                p.setNeedClientAuth(needClientAuth);
                params.setSSLParameters(p);
            }
        });
        return https;
    }

'''
s = s.replace(old_ctor, new_ctor, 1)

rep('''    public void start() {
        server.start();''', '''    public void start() {
        server.start();
        if (adminServer != null) {
            adminServer.start();
            LOG.log(System.Logger.Level.INFO, "Aegis admin API listening on " + settings.adminHost() + ":" + adminPort()
                    + settings.adminPath());
        }''')

rep('''    public int port() {
        return server.getAddress().getPort();
    }
''', '''    public int port() {
        return server.getAddress().getPort();
    }

    /** Port of the separate admin listener, or -1 when the admin API shares the MCP listener. */
    public int adminPort() {
        return adminServer == null ? -1 : adminServer.getAddress().getPort();
    }

    /** Pre-authentication limiter (diagnostics and tests). */
    ClientAddressLimiter limiter() {
        return limiter;
    }

    /**
     * Pre-authentication gate: per-address request budget and failed-authentication lockout, applied
     * before any credential is verified. Returns the client address when admitted, else null after
     * answering 429 with Retry-After.
     */
    private String preAuth(HttpExchange ex) throws IOException {
        java.net.InetSocketAddress remote = ex.getRemoteAddress();
        String client = limiter.clientAddress(remote == null ? null : remote.getAddress(),
                ex.getRequestHeaders().get("X-Forwarded-For"));
        ClientAddressLimiter.Refusal r = limiter.beforeAuthentication(client, System.currentTimeMillis());
        if (r != null) {
            write(ex, McpHttpResponse.json(r.status(), Map.of("error", r.reason()),
                    Map.of("Retry-After", Long.toString(r.retryAfterSeconds()))));
            return null;
        }
        return client;
    }

    private void afterAdmission(String client, OwnServerPerimeter.Admission admission) {
        if (!admission.allowed() && admission.status() == 401) {
            limiter.authenticationFailed(client, System.currentTimeMillis());
        }
    }
''')

rep('''        // Authenticate and admit the route before consuming an untrusted request body. The body is
        // not needed for JWT/static authentication, so unauthenticated traffic cannot make this host
        // buffer up to maxRequestBytes before the perimeter rejects it.
        List<java.security.cert.X509Certificate> peer = verifiedPeerChain(ex);
        OwnServerPerimeter.Admission admission = perimeter.admit(
                admin ? OwnServerPerimeter.RouteGroup.ADMIN : OwnServerPerimeter.RouteGroup.MCP,
                new McpHttpRequest(ex.getRequestMethod(), rel, headers, new byte[0], peer));
        if (!admission.allowed()) {''', '''        // Pre-authentication limits per client address come first: a flood of bad tokens is refused
        // by a map lookup, before any signature verification or JWKS fetch.
        String client = preAuth(ex);
        if (client == null) {
            return;
        }
        // Authenticate and admit the route before consuming an untrusted request body. The body is
        // not needed for JWT/static authentication, so unauthenticated traffic cannot make this host
        // buffer up to maxRequestBytes before the perimeter rejects it.
        List<java.security.cert.X509Certificate> peer = verifiedPeerChain(ex);
        OwnServerPerimeter.Admission admission = perimeter.admit(
                admin ? OwnServerPerimeter.RouteGroup.ADMIN : OwnServerPerimeter.RouteGroup.MCP,
                new McpHttpRequest(ex.getRequestMethod(), rel, headers, new byte[0], peer));
        afterAdmission(client, admission);
        if (!admission.allowed()) {''')

rep('''        Map<String, List<String>> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach(headers::put);
        OwnServerPerimeter.Admission admission = perimeter.admit(OwnServerPerimeter.RouteGroup.METRICS,
                new McpHttpRequest("GET", "", headers, new byte[0], verifiedPeerChain(ex)));
        if (!admission.allowed()) {''', '''        String client = preAuth(ex);
        if (client == null) {
            return;
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach(headers::put);
        OwnServerPerimeter.Admission admission = perimeter.admit(OwnServerPerimeter.RouteGroup.METRICS,
                new McpHttpRequest("GET", "", headers, new byte[0], verifiedPeerChain(ex)));
        afterAdmission(client, admission);
        if (!admission.allowed()) {''')

rep('''    public void close() {
        server.stop(1);''', '''    public void close() {
        server.stop(1);
        if (adminServer != null) {
            adminServer.stop(1);
        }''')

open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
