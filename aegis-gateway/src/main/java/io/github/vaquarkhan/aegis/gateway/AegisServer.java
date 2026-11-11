/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpResponse;
import io.github.vaquarkhan.aegis.security.identity.JwtVerifier;
import io.github.vaquarkhan.aegis.security.perimeter.ClientAddressLimiter;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerPerimeter;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerSecuritySettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The gateway's own front door on the JDK's built-in HTTP(S) server. It adapts HTTP exchanges to
 * the host-neutral {@link McpHttpRequest}, authenticates with the configured {@code
 * IdentityResolver}, and hands everything else to the shared handler. It contains no governance
 * logic.
 *
 * <p>Defaults are conservative: binds to 127.0.0.1 (MCP transport guidance for local servers),
 * bounded request bodies, a bounded worker pool with a bounded queue (overload refuses the
 * connection instead of blocking the accept thread or queueing without limit), and request,
 * response, header-size and connection limits via the JDK server properties that {@link
 * #applyServerTimeLimits} sets unless the operator already did.
 */
public final class AegisServer implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger(AegisServer.class.getName());

  private final Gateway gateway;
  private final OwnServerSecuritySettings settings;
  private final OwnServerPerimeter perimeter;
  private final HttpServer server;
  private final HttpServer adminServer;
  private final ThreadPoolExecutor pool;
  private final ClientAddressLimiter limiter;

  /**
   * Slow-client protection for the JDK HTTP server: a request must arrive within 30 s and a
   * response be written within 120 s, a request may carry at most 100 header lines and 16 KiB of
   * header bytes, and the server keeps at most 1,024 open connections. The header and connection
   * bounds matter because the JDK parses headers on a worker thread BEFORE this class authenticates
   * the request, so without them an unauthenticated client could hold worker threads with a
   * half-sent header. These are JDK-wide properties read when the server implementation
   * initialises, so they are set before the first server is created and never override an
   * operator's value.
   */
  static void applyServerTimeLimits() {
    setIfAbsent("sun.net.httpserver.maxReqTime", "30");
    setIfAbsent("sun.net.httpserver.maxRspTime", "120");
    setIfAbsent("sun.net.httpserver.maxReqHeaders", "100");
    setIfAbsent("sun.net.httpserver.maxReqHeaderSize", "16384");
    setIfAbsent("jdk.httpserver.maxConnections", "1024");
  }

  private static void setIfAbsent(String key, String value) {
    if (System.getProperty(key) == null) {
      System.setProperty(key, value);
    }
  }

  /**
   * The worker pool: {@code threads} workers and a queue of eight requests per worker. When both
   * are full the request is REJECTED (the JDK dispatcher logs and closes that connection) rather
   * than run on the accept thread. Running it there, as {@code CallerRunsPolicy} does, lets a burst
   * of slow clients stop the server accepting anyone, which is the pre-authentication exhaustion
   * pattern.
   */
  static ThreadPoolExecutor boundedPool(int threads) {
    return new ThreadPoolExecutor(
        threads,
        threads,
        60,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(threads * 8),
        r -> {
          Thread t = new Thread(r, "aegis-http");
          t.setDaemon(true);
          return t;
        },
        (r, executor) -> {
          LOG.log(
              System.Logger.Level.WARNING,
              "HTTP worker pool saturated ("
                  + threads
                  + " workers, "
                  + threads * 8
                  + " queued); refusing a connection");
          throw new java.util.concurrent.RejectedExecutionException("aegis worker pool saturated");
        });
  }

  public AegisServer(Gateway gateway, OwnServerSecuritySettings settings) throws Exception {
    applyServerTimeLimits();
    this.gateway = gateway;
    this.settings = settings;
    this.perimeter = new OwnServerPerimeter(settings);
    this.limiter = new ClientAddressLimiter(settings.limits());
    if (settings.cors().enabled()
        && !gateway
            .mcp()
            .options()
            .allowedOrigins()
            .containsAll(settings.cors().allowedOrigins())) {
      throw new IllegalArgumentException(
          "host.cors.allowedOrigins must be a subset of server.allowedOrigins; "
              + "preflight must never advertise an origin the MCP handler will reject");
    }
    checkCompleteControlPerimeter(gateway.completeControl(), settings);
    if (settings.identity() instanceof JwtVerifier jwt
        && jwt.idPolicy().requireRevocation()
        && !gateway.durableState()) {
      // O-3 A: a revocation that lives only in one replica's memory is lost on restart.
      throw new IllegalArgumentException(
          "host.identity.jwt.requireRevocation needs a durable, shared state store"
              + " (state.type derby-local or postgres); the in-memory store forgets revocations on restart");
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
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, settings.keystorePassword());
      ssl = SSLContext.getInstance("TLS");
      javax.net.ssl.TrustManager[] clientTrust =
          settings.requireClientCert() ? clientTrustManagers(settings.clientCaFile()) : null;
      ssl.init(kmf.getKeyManagers(), clientTrust, null);
    }
    server = listener(new InetSocketAddress(settings.host(), settings.port()), ssl);
    adminServer =
        settings.separateAdminListener()
            ? listener(new InetSocketAddress(settings.adminHost(), settings.adminPort()), ssl)
            : null;
    pool = boundedPool(settings.threads());
    server.setExecutor(pool);
    server.createContext(settings.mcpPath(), ex -> serve(ex, false));
    if (adminServer == null) {
      server.createContext(settings.adminPath(), ex -> serve(ex, true));
    } else {
      adminServer.setExecutor(pool);
      adminServer.createContext(settings.adminPath(), ex -> serve(ex, true));
      adminServer.createContext(
          "/healthz", ex -> write(ex, McpHttpResponse.json(200, Map.of("status", "ok"))));
    }
    if (gateway.metrics() != null) {
      // Authenticated like /mcp (metrics name tools and sources); optionally restricted to groups.
      server.createContext(settings.metricsPath(), this::metrics);
    }
    if (settings.oauth() != null) {
      // RFC 9728 discovery: unauthenticated by design, and the only unauthenticated route besides
      // health.
      server.createContext(settings.oauth().metadataPath(), this::protectedResourceMetadata);
    }
    server.createContext(
        "/healthz", ex -> write(ex, McpHttpResponse.json(200, Map.of("status", "ok"))));
    server.createContext(
        "/readyz",
        ex -> {
          List<ToolCatalog.Entry> entries = gateway.engine().catalog().entries();
          long active = entries.stream().filter(ToolCatalog.Entry::active).count();
          write(
              ex,
              McpHttpResponse.json(
                  active > 0 ? 200 : 503,
                  Map.of(
                      "status", active > 0 ? "ready" : "no active tools", "activeTools", active)));
        });
  }

  /**
   * Complete control: the admin API is never reachable by "any authenticated caller" and never
   * shares the MCP listener, so an MCP client credential alone cannot reach an admin route, and JWT
   * callers must carry token ids that the gateway can revoke.
   */
  static void checkCompleteControlPerimeter(
      boolean completeControl, OwnServerSecuritySettings settings) {
    if (!completeControl) {
      return;
    }
    if (settings.adminGroups().isEmpty()) {
      throw new IllegalArgumentException(
          "completeControl.enabled requires host.routes.adminGroups (the admin API"
              + " must not be open to every authenticated caller)");
    }
    if (!settings.separateAdminListener()) {
      throw new IllegalArgumentException(
          "completeControl.enabled requires host.adminListen (the admin API must"
              + " not share the MCP listener)");
    }
    if (settings.identity() instanceof JwtVerifier jwt
        && !(jwt.idPolicy().requireJti() && jwt.idPolicy().requireRevocation())) {
      throw new IllegalArgumentException(
          "completeControl.enabled requires host.identity.jwt.requireJti and"
              + " requireRevocation (a stolen token must be revocable at the gateway)");
    }
  }

  private HttpServer listener(InetSocketAddress addr, SSLContext ssl) throws IOException {
    if (ssl == null) {
      return HttpServer.create(addr, 128);
    }
    HttpsServer https = HttpsServer.create(addr, 128);
    boolean needClientAuth = settings.requireClientCert();
    https.setHttpsConfigurator(
        new HttpsConfigurator(ssl) {
          @Override
          public void configure(com.sun.net.httpserver.HttpsParameters params) {
            javax.net.ssl.SSLParameters p = getSSLContext().getDefaultSSLParameters();
            // Mutual TLS: a client without a certificate that chains to clientCaFile fails the
            // handshake.
            p.setNeedClientAuth(needClientAuth);
            params.setSSLParameters(p);
          }
        });
    return https;
  }

  public void start() {
    server.start();
    if (adminServer != null) {
      adminServer.start();
      LOG.log(
          System.Logger.Level.INFO,
          "Aegis admin API listening on "
              + settings.adminHost()
              + ":"
              + adminPort()
              + settings.adminPath());
    }
    LOG.log(
        System.Logger.Level.INFO,
        "Aegis gateway listening on "
            + (settings.keystore() != null ? "https" : "http")
            + "://"
            + settings.host()
            + ":"
            + port()
            + settings.mcpPath());
  }

  public int port() {
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
    String client =
        limiter.clientAddress(
            remote == null ? null : remote.getAddress(),
            ex.getRequestHeaders().get("X-Forwarded-For"));
    ClientAddressLimiter.Refusal r =
        limiter.beforeAuthentication(client, System.currentTimeMillis());
    if (r != null) {
      refuseBeforeBody(
          ex,
          McpHttpResponse.json(
              r.status(),
              Map.of("error", r.reason()),
              Map.of("Retry-After", Long.toString(r.retryAfterSeconds()))));
      return null;
    }
    return client;
  }

  /**
   * Counts a failure only when a credential was presented and rejected. An MCP client's first,
   * unauthenticated request is part of OAuth discovery (it needs the 401 challenge) and is not an
   * attack, so it never moves an address towards lockout.
   */
  private void afterAdmission(
      String client, HttpExchange ex, OwnServerPerimeter.Admission admission) {
    if (!admission.allowed()
        && admission.status() == 401
        && ex.getRequestHeaders().containsKey("Authorization")) {
      limiter.authenticationFailed(client, System.currentTimeMillis());
    }
  }

  private void serve(HttpExchange ex, boolean admin) throws IOException {
    guarded(ex, "request", () -> route(ex, admin));
  }

  /** One step of request handling that may write the response. */
  @FunctionalInterface
  interface ExchangeAction {
    void run() throws IOException;
  }

  /**
   * Runs {@code action} and always closes the exchange. An unexpected {@link RuntimeException} is
   * logged and answered with a generic 500 when no response has started; the client never sees the
   * exception text. The exchange is closed by the outer try, after the catch has answered: a single
   * try-with-resources would close it first and the 500 would be lost.
   */
  static void guarded(HttpExchange ex, String what, ExchangeAction action) throws IOException {
    try (ex) {
      try {
        action.run();
      } catch (RuntimeException e) {
        LOG.log(System.Logger.Level.ERROR, what + " failed", e);
        if (ex.getResponseCode() == -1) {
          byte[] body =
              "{\"error\":\"internal error\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          ex.getResponseHeaders().set("Content-Type", "application/json");
          ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
          ex.sendResponseHeaders(500, body.length);
          try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
          }
        }
      }
    }
  }

  private void route(HttpExchange ex, boolean admin) throws IOException {
    String base = admin ? settings.adminPath() : settings.mcpPath();
    String path = ex.getRequestURI().getPath();
    String rel = path.length() > base.length() ? path.substring(base.length()) : "";
    if (!admin && !rel.isEmpty() && !"/".equals(rel)) {
      refuseBeforeBody(ex, McpHttpResponse.json(404, Map.of("error", "not found")));
      return;
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    ex.getRequestHeaders().forEach(headers::put);
    String origin = ex.getRequestHeaders().getFirst("Origin");
    if ("OPTIONS".equals(ex.getRequestMethod())) {
      preflight(ex, admin, origin);
      return;
    }
    // CORS policy belongs at the HTTP boundary. The shared MCP/admin handlers retain their own
    // origin check too, so Knox and embedding hosts get the same non-browser origin defense.
    if (origin != null && settings.cors().enabled() && !settings.cors().allowsOrigin(origin)) {
      refuseBeforeBody(ex, McpHttpResponse.json(403, Map.of("error", "origin not allowed")));
      return;
    }
    // Pre-authentication limits per client address come first: a flood of bad tokens is refused
    // by a map lookup, before any signature verification or JWKS fetch.
    String client = preAuth(ex);
    if (client == null) {
      return;
    }
    // Authenticate and admit the route before consuming an untrusted request body. The body is
    // not needed for JWT/static authentication, so unauthenticated traffic cannot make this host
    // buffer up to maxRequestBytes before the perimeter rejects it.
    List<java.security.cert.X509Certificate> peer = verifiedPeerChain(ex);
    OwnServerPerimeter.Admission admission =
        perimeter.admit(
            admin ? OwnServerPerimeter.RouteGroup.ADMIN : OwnServerPerimeter.RouteGroup.MCP,
            new McpHttpRequest(ex.getRequestMethod(), rel, headers, new byte[0], peer));
    afterAdmission(client, ex, admission);
    if (!admission.allowed()) {
      refuseBeforeBody(
          ex,
          McpHttpResponse.json(
              admission.status(), Map.of("error", admission.publicMessage()), admission.headers()));
      return;
    }
    CallerIdentity caller = admission.caller();
    byte[] body;
    try {
      body = readBounded(ex, settings.maxRequestBytes());
    } catch (IOException e) {
      write(ex, McpHttpResponse.json(413, Map.of("error", "request body too large")));
      return;
    }
    McpHttpRequest req = new McpHttpRequest(ex.getRequestMethod(), rel, headers, body, peer);
    McpHttpResponse resp;
    // A verified JWT is offered to credential providers for RFC 8693 exchange during this request
    // only. Static tokens are gateway-local secrets, not identity-provider tokens, so they are not.
    io.github.vaquarkhan.aegis.engine.spi.SubjectToken.Scope subject =
        io.github.vaquarkhan.aegis.engine.spi.SubjectToken.bind(
            admin ? null : verifiedJwt(caller, ex));
    io.github.vaquarkhan.aegis.engine.spi.ScopeChallenge.Scope stepUp =
        io.github.vaquarkhan.aegis.engine.spi.ScopeChallenge.open();
    try {
      resp = admin ? gateway.admin().handle(req, caller) : gateway.mcp().handleHttp(req, caller);
      resp = stepUp(resp, caller, admin, stepUp.required());
    } finally {
      stepUp.close();
      subject.close();
    }
    write(ex, resp);
  }

  /**
   * OAuth step-up for tool scopes: when a JWT caller's tool call was refused for missing action
   * scopes, the answer is 403 with an {@code insufficient_scope} challenge that names them, so an
   * MCP client can request a token that carries them. The JSON-RPC body is kept unchanged, so the
   * client also sees the {@code SCOPE_DENIED} result. Static tokens cannot be stepped up and keep
   * the plain result.
   */
  McpHttpResponse stepUp(
      McpHttpResponse resp, CallerIdentity caller, boolean admin, List<String> actionScopes) {
    if (admin
        || actionScopes.isEmpty()
        || caller == null
        || caller.authMethod() == null
        || !caller.authMethod().startsWith("jwt:")
        || resp.status() != 200) {
      return resp;
    }
    Map<String, String> headers = new LinkedHashMap<>(resp.headers());
    headers.put(
        "WWW-Authenticate",
        settings.insufficientScopeChallenge(OwnServerPerimeter.RouteGroup.MCP, actionScopes));
    return new McpHttpResponse(403, headers, resp.body());
  }

  /** The bearer token the perimeter verified as a JWT for this caller, or null. */
  static String verifiedJwt(CallerIdentity caller, HttpExchange ex) {
    if (caller == null || caller.authMethod() == null || !caller.authMethod().startsWith("jwt:")) {
      return null;
    }
    List<String> values = ex.getRequestHeaders().get("Authorization");
    if (values == null || values.size() != 1) {
      return null;
    }
    String v = values.get(0);
    return v.regionMatches(true, 0, "Bearer ", 0, 7) ? v.substring(7).trim() : null;
  }

  /**
   * The client chain the TLS handshake verified against {@code host.tls.clientCaFile}. Only read
   * when client authentication is required, so the chain is always one the trust managers accepted.
   */
  private List<java.security.cert.X509Certificate> verifiedPeerChain(HttpExchange ex) {
    if (!settings.requireClientCert()
        || !(ex instanceof com.sun.net.httpserver.HttpsExchange https)) {
      return List.of();
    }
    try {
      List<java.security.cert.X509Certificate> chain = new java.util.ArrayList<>();
      for (java.security.cert.Certificate c : https.getSSLSession().getPeerCertificates()) {
        if (c instanceof java.security.cert.X509Certificate x) {
          chain.add(x);
        }
      }
      return chain;
    } catch (javax.net.ssl.SSLPeerUnverifiedException none) {
      return List.of();
    }
  }

  /**
   * Trust managers for client certificates: only the PEM bundle in {@code clientCaFile}, not the
   * JDK defaults.
   */
  private static javax.net.ssl.TrustManager[] clientTrustManagers(java.nio.file.Path caFile)
      throws Exception {
    java.security.cert.CertificateFactory cf =
        java.security.cert.CertificateFactory.getInstance("X.509");
    KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null, null);
    int i = 0;
    try (InputStream in = Files.newInputStream(caFile)) {
      for (java.security.cert.Certificate c : cf.generateCertificates(in)) {
        trust.setCertificateEntry("client-ca-" + i++, c);
      }
    }
    if (i == 0) {
      throw new IllegalArgumentException(
          "host.tls.clientCaFile " + caFile + " holds no certificate");
    }
    javax.net.ssl.TrustManagerFactory tmf =
        javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    return tmf.getTrustManagers();
  }

  /** Browser preflight is evaluated by the perimeter before authentication or body read. */
  private void preflight(HttpExchange ex, boolean admin, String origin) throws IOException {
    OwnServerPerimeter.Preflight result =
        perimeter.preflight(
            origin,
            ex.getRequestHeaders().getFirst("Access-Control-Request-Method"),
            ex.getRequestHeaders().getFirst("Access-Control-Request-Headers"));
    if (!result.allowed()) {
      write(ex, McpHttpResponse.json(403, Map.of("error", "CORS preflight not allowed")));
      return;
    }
    write(ex, McpHttpResponse.empty(204, result.headers()));
  }

  /**
   * RFC 9728 protected resource metadata. Discovery data, so no authentication and no governance.
   */
  private void protectedResourceMetadata(HttpExchange ex) throws IOException {
    try (ex) {
      if (!"GET".equals(ex.getRequestMethod())) {
        write(ex, McpHttpResponse.empty(405, Map.of("Allow", "GET")));
        return;
      }
      if (!settings.oauth().metadataPath().equals(ex.getRequestURI().getPath())) {
        write(ex, McpHttpResponse.json(404, Map.of("error", "not found")));
        return;
      }
      byte[] body = settings.oauth().json().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", "application/json");
      ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
      ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    }
  }

  private void metrics(HttpExchange ex) throws IOException {
    guarded(ex, "metrics request", () -> renderMetrics(ex));
  }

  private void renderMetrics(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) {
      write(ex, McpHttpResponse.empty(405, Map.of("Allow", "GET")));
      return;
    }
    String client = preAuth(ex);
    if (client == null) {
      return;
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    ex.getRequestHeaders().forEach(headers::put);
    OwnServerPerimeter.Admission admission =
        perimeter.admit(
            OwnServerPerimeter.RouteGroup.METRICS,
            new McpHttpRequest("GET", "", headers, new byte[0], verifiedPeerChain(ex)));
    afterAdmission(client, ex, admission);
    if (!admission.allowed()) {
      refuseBeforeBody(
          ex,
          McpHttpResponse.json(
              admission.status(), Map.of("error", admission.publicMessage()), admission.headers()));
      return;
    }
    byte[] body =
        gateway.metrics().renderPrometheus().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
    ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    ex.sendResponseHeaders(200, body.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(body);
    }
  }

  private static byte[] readBounded(HttpExchange ex, int max) throws IOException {
    String cl = ex.getRequestHeaders().getFirst("Content-Length");
    if (cl != null) {
      try {
        if (Long.parseLong(cl.trim()) > max) {
          throw new IOException("too large");
        }
      } catch (NumberFormatException e) {
        throw new IOException("bad content-length");
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int total = 0;
    int n;
    try (InputStream in = ex.getRequestBody()) {
      while ((n = in.read(buf)) >= 0) {
        total += n;
        if (total > max) {
          throw new IOException("too large");
        }
        out.write(buf, 0, n);
      }
    }
    return out.toByteArray();
  }

  /**
   * Answer before the request body is read. Drain any posted bytes and close the connection so a
   * keep-alive client cannot reuse a half-consumed stream (JDK HttpClient on Windows then fails the
   * next request with "HTTP/1.1 header parser received no bytes").
   */
  private void refuseBeforeBody(HttpExchange ex, McpHttpResponse r) throws IOException {
    discardRequestBody(ex);
    Map<String, String> headers = new LinkedHashMap<>(r.headers());
    headers.put("Connection", "close");
    write(ex, new McpHttpResponse(r.status(), headers, r.body()));
  }

  private static void discardRequestBody(HttpExchange ex) {
    try (InputStream in = ex.getRequestBody()) {
      in.transferTo(OutputStream.nullOutputStream());
    } catch (IOException ignored) {
      // Best-effort; the exchange close still tears the socket down.
    }
  }

  private void write(HttpExchange ex, McpHttpResponse r) throws IOException {
    r.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
    applyCorsHeaders(ex);
    ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    if (r.body().length == 0) {
      ex.sendResponseHeaders(r.status(), -1);
      return;
    }
    ex.sendResponseHeaders(r.status(), r.body().length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(r.body());
    }
  }

  /** Adds CORS response headers only for an explicitly configured, exact allowed origin. */
  private void applyCorsHeaders(HttpExchange ex) {
    String origin = ex.getRequestHeaders().getFirst("Origin");
    perimeter
        .responseCorsHeaders(origin)
        .forEach((name, value) -> ex.getResponseHeaders().set(name, value));
  }

  @Override
  public void close() {
    server.stop(1);
    if (adminServer != null) {
      adminServer.stop(1);
    }
    pool.shutdownNow();
    gateway.close();
  }
}
