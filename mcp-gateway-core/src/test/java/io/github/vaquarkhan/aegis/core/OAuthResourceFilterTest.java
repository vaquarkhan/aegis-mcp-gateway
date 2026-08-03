package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.core.auth.CallerContext;
import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.auth.JwksJwtValidator;
import io.github.vaquarkhan.aegis.core.auth.OAuthResourceFilter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end checks of the OAuth resource filter against a local JWKS endpoint and real signed
 * tokens. The property under test is that exactly the tokens an issuer could legitimately mint are
 * admitted, and that everything else, including a syntactically perfect but unverifiable token,
 * is refused.
 *
 * @author Viquar Khan
 */
class OAuthResourceFilterTest {

    private static final String ISSUER = "https://issuer.example.com";
    private static final String AUDIENCE = "aegis";

    private static RSAKey rsaKey;
    private static RSAKey foreignRsaKey;
    private static ECKey ecKey;
    private static RSAKey rotatedKey;
    private static HttpServer jwks;
    private static AtomicReference<String> jwksBody;
    private static String jwksUrl;

    @BeforeAll
    static void startJwksEndpoint() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("rsa-1").keyUse(KeyUse.SIGNATURE).generate();
        foreignRsaKey = new RSAKeyGenerator(2048).keyID("rsa-1").keyUse(KeyUse.SIGNATURE).generate();
        ecKey = new ECKeyGenerator(Curve.P_256).keyID("ec-1").keyUse(KeyUse.SIGNATURE).generate();
        rotatedKey = new RSAKeyGenerator(2048).keyID("rsa-2").keyUse(KeyUse.SIGNATURE).generate();

        jwksBody = new AtomicReference<>(
                new JWKSet(List.of(rsaKey.toPublicJWK(), ecKey.toPublicJWK())).toString());

        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks.json", exchange -> {
            byte[] body = jwksBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        jwks.start();
        jwksUrl = "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks.json";
    }

    @AfterAll
    static void stopJwksEndpoint() {
        if (jwks != null) {
            jwks.stop(0);
        }
    }

    @AfterEach
    void clearCaller() {
        CallerContext.clear();
    }

    @Test
    void refusesToConstructWithoutIssuerOrAudience() {
        assertThrows(IllegalArgumentException.class,
                () -> new OAuthResourceFilter(null, AUDIENCE, jwksUrl, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OAuthResourceFilter(ISSUER, "  ", jwksUrl, null));
    }

    @Test
    void admitsAValidTokenAndPublishesAReadOnlyCaller() throws Exception {
        String token = signRsa(rsaKey, claims().claim("scope", "mcp.read").build());
        Recorder recorder = call(filter("mcp.read"), "Bearer " + token);

        assertTrue(recorder.chainCalled, "a verified token must reach the chain");
        assertEquals(0, recorder.status, "an admitted request writes no status of its own");
        CallerIdentity caller = recorder.caller;
        assertNotNull(caller, "the verified caller must be attached to the request");
        assertEquals("svc-a", caller.subject());
        assertTrue(caller.readonly(), "a token without the write scope stays read-only");
        assertEquals(Set.of("mcp.read"), caller.resourceScopes());
        assertTrue(CallerContext.current().isEmpty(), "the caller must not outlive the request");
    }

    @Test
    void theWriteScopeLiftsReadOnly() throws Exception {
        String token = signRsa(rsaKey, claims().claim("scope", "mcp.read aegis.write").build());
        Recorder recorder = call(filter("mcp.read"), "Bearer " + token);
        assertTrue(recorder.chainCalled);
        assertFalse(recorder.caller.readonly());
    }

    @Test
    void aConfiguredWriteScopeReplacesTheDefault() throws Exception {
        OAuthResourceFilter f = new OAuthResourceFilter(
                ISSUER, AUDIENCE, jwksUrl, null, "flink.write");
        assertEquals("flink.write", f.writeScope());

        assertTrue(call(f, "Bearer " + signRsa(rsaKey, claims().claim("scope", "aegis.write").build()))
                .caller.readonly(), "the default write scope no longer applies once one is configured");
        assertFalse(call(f, "Bearer " + signRsa(rsaKey, claims().claim("scope", "flink.write").build()))
                .caller.readonly());
    }

    @Test
    void acceptsAnEs256Token() throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(ecKey.getKeyID()).build(),
                claims().claim("scp", List.of("mcp.read", "aegis.write")).build());
        jwt.sign(new ECDSASigner(ecKey));
        Recorder recorder = call(filter("mcp.read"), "Bearer " + jwt.serialize());
        assertTrue(recorder.chainCalled);
        assertEquals(Set.of("mcp.read", "aegis.write"), recorder.caller.resourceScopes());
    }

    @Test
    void deniesAnyRequestWithoutACredential() throws Exception {
        Recorder recorder = call(filter(null), null);
        assertEquals(401, recorder.status);
        assertFalse(recorder.chainCalled);
        assertTrue(recorder.headers.get("WWW-Authenticate").contains("Bearer"));
    }

    @Test
    void deniesAWellFormedButUnverifiableToken() throws Exception {
        Recorder recorder = call(filter(null), "Bearer eyJhbGciOiJSUzI1NiJ9.e30.signature");
        assertEquals(401, recorder.status);
        assertFalse(recorder.chainCalled, "an unverified token must not become an identity");
        assertTrue(recorder.body().contains("invalid_token"));
        assertTrue(CallerContext.current().isEmpty(), "no caller may be published to the chain");
    }

    @Test
    void deniesATokenSignedByAForeignKeyWithTheSameKid() throws Exception {
        String token = signRsa(foreignRsaKey, claims().claim("scope", "mcp.read").build());
        Recorder recorder = call(filter("mcp.read"), "Bearer " + token);
        assertEquals(401, recorder.status);
        assertFalse(recorder.chainCalled, "a matching kid is not a signature");
    }

    @Test
    void deniesExpiredWrongIssuerAndWrongAudienceTokens() throws Exception {
        String expired = signRsa(rsaKey, claims()
                .expirationTime(Date.from(Instant.now().minusSeconds(600)))
                .build());
        assertEquals(401, call(filter(null), "Bearer " + expired).status);

        String wrongIssuer = signRsa(rsaKey, claims().issuer("https://evil.example.com").build());
        assertEquals(401, call(filter(null), "Bearer " + wrongIssuer).status);

        String wrongAudience = signRsa(rsaKey, claims().audience("someone-else").build());
        assertEquals(401, call(filter(null), "Bearer " + wrongAudience).status);
    }

    @Test
    void toleratesClockSkewInsideTheAllowance() throws Exception {
        String justExpired = signRsa(rsaKey, claims()
                .expirationTime(Date.from(Instant.now().minusSeconds(20)))
                .notBeforeTime(Date.from(Instant.now().plusSeconds(20)))
                .claim("scope", "mcp.read")
                .build());
        assertTrue(call(filter("mcp.read"), "Bearer " + justExpired).chainCalled,
                "60s of skew is allowed on both exp and nbf");
    }

    @Test
    void deniesATokenMissingTheRequiredScope() throws Exception {
        String token = signRsa(rsaKey, claims().claim("scope", "some.other.scope").build());
        Recorder recorder = call(filter("mcp.read"), "Bearer " + token);
        assertEquals(401, recorder.status);
        assertFalse(recorder.chainCalled);
    }

    @Test
    void deniesATokenWithoutASubject() throws Exception {
        String token = signRsa(rsaKey, new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("scope", "mcp.read")
                .build());
        assertEquals(401, call(filter("mcp.read"), "Bearer " + token).status);
    }

    @Test
    void fallsBackToPreferredUsernameWhenSubIsAbsent() throws Exception {
        String token = signRsa(rsaKey, new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("preferred_username", "analyst@example.com")
                .claim("scope", "mcp.read")
                .build());
        Recorder recorder = call(filter("mcp.read"), "Bearer " + token);
        assertTrue(recorder.chainCalled);
        assertEquals("analyst@example.com", recorder.caller.subject());
    }

    @Test
    void refusesTheHmacFamilyEvenWhenTheSignatureIsSelfConsistent() throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(rsaKey.getKeyID()).build(),
                claims().claim("scope", "mcp.read").build());
        jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef0123456789abcdef"));
        Recorder recorder = call(filter("mcp.read"), "Bearer " + jwt.serialize());
        assertEquals(401, recorder.status);
        assertFalse(recorder.chainCalled, "a JWKS key must never be reachable as a shared secret");
    }

    @Test
    void deniesEverythingWhenNoJwksUrlIsConfigured() throws Exception {
        OAuthResourceFilter f = new OAuthResourceFilter(ISSUER, AUDIENCE, null, null);
        assertFalse(f.verificationEnabled());
        String token = signRsa(rsaKey, claims().claim("scope", "mcp.read").build());
        Recorder recorder = call(f, "Bearer " + token);
        assertEquals(401, recorder.status);
        assertFalse(recorder.chainCalled, "without a key source the filter admits nobody");
    }

    @Test
    void picksUpARotatedSigningKeyWithoutARestart() throws Exception {
        OAuthResourceFilter f = filter("mcp.read");
        assertTrue(call(f, "Bearer " + signRsa(rsaKey, claims().claim("scope", "mcp.read").build()))
                .chainCalled);

        String previous = jwksBody.get();
        try {
            jwksBody.set(new JWKSet(List.of(rotatedKey.toPublicJWK())).toString());
            String token = signRsa(rotatedKey, claims().claim("scope", "mcp.read").build());
            assertTrue(call(f, "Bearer " + token).chainCalled,
                    "an unknown kid must trigger a JWKS refresh");
        } finally {
            jwksBody.set(previous);
        }
    }

    @Test
    void reportsItsConfiguredClaims() {
        OAuthResourceFilter f = filter("mcp.read");
        assertEquals(ISSUER, f.issuer());
        assertEquals(AUDIENCE, f.audience());
        assertEquals(jwksUrl, f.jwksUrl());
        assertEquals(Set.of("mcp.read"), f.requiredScopes());
        assertEquals(OAuthResourceFilter.DEFAULT_WRITE_SCOPE, f.writeScope());
        assertThrows(IllegalArgumentException.class, () -> f.verifyClaims("any.jwt.here"));
    }

    @Test
    void splitsScopeClaimsInBothStringAndArrayForms() {
        JWTClaimsSet spaceDelimited = new JWTClaimsSet.Builder()
                .claim("scope", "a b  c").build();
        assertEquals(Set.of("a", "b", "c"), JwksJwtValidator.scopesOf(spaceDelimited));

        JWTClaimsSet array = new JWTClaimsSet.Builder().claim("scp", List.of("a", "b")).build();
        assertEquals(Set.of("a", "b"), JwksJwtValidator.scopesOf(array));

        assertTrue(JwksJwtValidator.scopesOf(new JWTClaimsSet.Builder().build()).isEmpty());
    }

    @Test
    void authenticateIsUsableWithoutAServletRequest() throws Exception {
        String token = signRsa(rsaKey, claims().claim("scope", "mcp.read aegis.write").build());
        Optional<CallerIdentity> caller = filter("mcp.read").authenticate(token);
        assertTrue(caller.isPresent());
        assertEquals("svc-a", caller.get().subject());
        assertFalse(caller.get().readonly());
    }

    private static OAuthResourceFilter filter(String requiredScope) {
        return new OAuthResourceFilter(ISSUER, AUDIENCE, jwksUrl, requiredScope);
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .subject("svc-a")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }

    private static String signRsa(RSAKey key, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static Recorder call(OAuthResourceFilter filter, String authHeader) throws Exception {
        Recorder recorder = new Recorder();
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                OAuthResourceFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getHeader" -> {
                            return "Authorization".equalsIgnoreCase((String) args[0]) ? authHeader : null;
                        }
                        case "setAttribute" -> {
                            if (args[1] instanceof CallerIdentity id) {
                                recorder.caller = id;
                            }
                            return null;
                        }
                        default -> {
                            return defaultFor(method);
                        }
                    }
                });
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                OAuthResourceFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                recorder);
        filter.doFilter(request, response, (ServletRequest rq, ServletResponse rs) -> recorder.chainCalled = true);
        return recorder;
    }

    private static Object defaultFor(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || void.class.equals(type)) {
            return null;
        }
        return boolean.class.equals(type) ? Boolean.FALSE : 0;
    }

    /** Captures the status, headers, body and caller the filter produces. */
    private static final class Recorder implements InvocationHandler {

        private final Map<String, String> headers = new HashMap<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int status;
        private boolean chainCalled;
        private CallerIdentity caller;

        String body() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "setStatus" -> status = (int) args[0];
                case "setHeader", "addHeader" -> headers.put((String) args[0], (String) args[1]);
                case "getOutputStream" -> {
                    return new ServletOutputStream() {
                        @Override
                        public boolean isReady() {
                            return true;
                        }

                        @Override
                        public void setWriteListener(WriteListener listener) {
                            // Nothing to notify: the recorder is always ready.
                        }

                        @Override
                        public void write(int b) {
                            bytes.write(b);
                        }
                    };
                }
                default -> {
                    return defaultFor(method);
                }
            }
            return null;
        }
    }
}
