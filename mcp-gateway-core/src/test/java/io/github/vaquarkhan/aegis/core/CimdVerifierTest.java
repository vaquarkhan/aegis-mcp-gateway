package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.core.auth.CimdVerifier;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The client identity metadata document is only usable as an identifier when the document says the
 * same thing about itself as the URL it came from, so that is the property under test.
 *
 * @author Viquar Khan
 */
class CimdVerifierTest {

    private static HttpServer server;
    private static AtomicReference<String> body;
    private static AtomicInteger status;
    private static AtomicInteger fetches;
    private static String documentUrl;

    @BeforeAll
    static void startEndpoint() throws Exception {
        body = new AtomicReference<>("{}");
        status = new AtomicInteger(200);
        fetches = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client.json", exchange -> {
            fetches.incrementAndGet();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        documentUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/client.json";
    }

    @AfterAll
    static void stopEndpoint() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void acceptsADocumentThatNamesItsOwnUrl() {
        body.set(document(documentUrl));
        status.set(200);
        CimdVerifier verifier = new CimdVerifier(documentUrl);
        assertTrue(verifier.configured());
        assertEquals(documentUrl, verifier.metadataUrl());

        Optional<CimdVerifier.ClientDocument> doc = verifier.verifyConfiguredDocument();
        assertTrue(doc.isPresent());
        assertEquals(documentUrl, doc.get().clientId());
        assertEquals("Aegis demo client", doc.get().clientName());
        assertEquals(Set.of("https://client.example.com/callback"), doc.get().redirectUris());
        assertEquals(Set.of("authorization_code"), doc.get().grantTypes());
    }

    @Test
    void rejectsADocumentClaimingSomeoneElsesClientId() {
        body.set(document("https://someone-else.example.com/client.json"));
        status.set(200);
        assertTrue(new CimdVerifier(documentUrl).verifyConfiguredDocument().isEmpty(),
                "a document that names another URL would let any client borrow that identity");
    }

    @Test
    void rejectsANonJsonBodyAndANonOkStatus() {
        body.set("not json at all");
        status.set(200);
        assertTrue(new CimdVerifier(documentUrl).verifyConfiguredDocument().isEmpty());

        body.set(document(documentUrl));
        status.set(404);
        assertTrue(new CimdVerifier(documentUrl).verifyConfiguredDocument().isEmpty());
        status.set(200);
    }

    @Test
    void cachesTheResultSoOneClientCannotDriveRepeatedFetches() {
        body.set(document(documentUrl));
        status.set(200);
        CimdVerifier verifier = new CimdVerifier(documentUrl);

        int before = fetches.get();
        assertTrue(verifier.verifyDocument(documentUrl).isPresent());
        assertTrue(verifier.verifyDocument(documentUrl).isPresent());
        assertTrue(verifier.verifyDocument(documentUrl).isPresent());
        assertEquals(1, fetches.get() - before, "the document is fetched once per cache window");

        verifier.invalidate();
        assertTrue(verifier.verifyDocument(documentUrl).isPresent());
        assertEquals(2, fetches.get() - before);
    }

    @Test
    void cachesFailuresToo() {
        body.set(document("https://someone-else.example.com/client.json"));
        status.set(200);
        CimdVerifier verifier = new CimdVerifier(documentUrl);

        int before = fetches.get();
        assertTrue(verifier.verifyDocument(documentUrl).isEmpty());
        assertTrue(verifier.verifyDocument(documentUrl).isEmpty());
        assertEquals(1, fetches.get() - before);
    }

    @Test
    void refusesPlaintextForANonLoopbackHost() {
        CimdVerifier verifier = new CimdVerifier("http://client.example.com/client.json");
        assertTrue(verifier.verifyConfiguredDocument().isEmpty(),
                "a plaintext document identifies whoever is on the path, not who published it");
    }

    @Test
    void toleratesATrailingSlashAndACaseDifferenceInTheHost() {
        CimdVerifier verifier = new CimdVerifier(null);
        assertFalse(verifier.configured());
        assertTrue(verifier.verifyConfiguredDocument().isEmpty());

        assertTrue(verifier.parseDocument(
                "https://Client.Example.com/app/",
                document("https://client.example.com/app")).isPresent());
        assertTrue(verifier.parseDocument(
                "https://client.example.com/app",
                document("https://client.example.com/other")).isEmpty());
    }

    @Test
    void stillRefusesToAuthenticateARequest() {
        CimdVerifier verifier = new CimdVerifier(documentUrl);
        assertTrue(verifier.verify(document(documentUrl)).isEmpty(),
                "naming a client is not authenticating one");
        assertThrows(UnsupportedOperationException.class,
                () -> verifier.requireVerified(document(documentUrl)));
    }

    private static String document(String clientId) {
        return "{\"client_id\":\"" + clientId + "\","
                + "\"client_name\":\"Aegis demo client\","
                + "\"redirect_uris\":[\"https://client.example.com/callback\"],"
                + "\"grant_types\":[\"authorization_code\"],"
                + "\"software_id\":\"aegis-demo\"}";
    }
}
