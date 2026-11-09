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
package io.github.vaquarkhan.aegis.mcp.http;

import io.github.vaquarkhan.aegis.engine.control.EgressGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * HTTP/1.1 transport that closes the DNS rebinding window between the egress check and the
 * connection. For every request it asks the egress control to resolve and check the host ONCE
 * ({@link EgressGuard#pin}) and then connects a socket directly to one of those checked addresses.
 * The JDK client is not used because it resolves the host again at connect time and Java 17 has no
 * per-client resolver hook.
 *
 * <p>TLS keeps the original name: the socket is wrapped with
 * {@code SSLSocketFactory.createSocket(socket, host, port, true)}, SNI is the original host, and
 * endpoint identification is {@code HTTPS}, so the certificate must match the name the policy
 * approved, not the IP that was dialled.
 *
 * <p>Bounded like {@link BoundedHttp}: one total deadline (socket read timeouts are recomputed
 * from it before every read, so a trickling server cannot extend it), a byte bound on the body, a
 * 64 KiB bound on the response head, redirects returned as a status (never followed), and
 * {@code Connection: close} (no pooled connection survives a re-resolution).
 */
public final class PinnedHttp implements OutboundHttp {

    /** The egress control refused the destination at connect time; nothing was sent. */
    public static final class EgressDeniedException extends IOException {
        private static final long serialVersionUID = 1L;

        public EgressDeniedException(String reason) {
            super("egress denied at connect: " + reason);
        }
    }

    private static final int MAX_HEAD_BYTES = 64 * 1024;
    private static final int MAX_HEADERS = 200;

    /**
     * A forward proxy reached with HTTP CONNECT. Its host must itself pass the egress control.
     * {@code credential} is an optional {@code Proxy-Authorization} header value (for example
     * {@code "Basic dXNlcjpwYXNz"}); it is sent only on the CONNECT line to the proxy, never to the target.
     */
    public record Proxy(String host, int port, String credential) {
        public Proxy(String host, int port) {
            this(host, port, null);
        }

        public Proxy {
            if (host == null || host.isBlank() || port < 1 || port > 65_535) {
                throw new IllegalArgumentException("proxy needs a host and a port between 1 and 65535");
            }
            if (credential != null && (credential.indexOf('\r') >= 0 || credential.indexOf('\n') >= 0)) {
                throw new IllegalArgumentException("proxy credential must not contain a line break");
            }
        }
    }

    private final Function<URI, EgressGuard.Pin> pinner;
    private final long connectTimeoutMillis;
    private final SSLContext ssl;
    private final Proxy proxy;

    /**
     * @param pinner         resolve-and-check step, normally {@code guard::pin} or {@code binding::pin}
     * @param connectTimeout per-address connect bound (default 5 s)
     * @param ssl            TLS context (null = the JVM default trust store)
     */
    public PinnedHttp(Function<URI, EgressGuard.Pin> pinner, Duration connectTimeout, SSLContext ssl) {
        this(pinner, connectTimeout, ssl, null);
    }

    /**
     * @param proxy forward proxy for every exchange (null = direct). The target is still resolved and
     *              checked by the egress control (policy), the proxy host is resolved and checked too,
     *              and the TCP connection goes to a checked proxy address; TLS (SNI, hostname
     *              verification) runs end to end through the CONNECT tunnel against the original name.
     */
    public PinnedHttp(Function<URI, EgressGuard.Pin> pinner, Duration connectTimeout, SSLContext ssl, Proxy proxy) {
        this.proxy = proxy;
        if (pinner == null) {
            throw new IllegalArgumentException("an egress pinner is required");
        }
        this.pinner = pinner;
        this.connectTimeoutMillis = connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                ? 5_000 : connectTimeout.toMillis();
        this.ssl = ssl;
    }

    @Override
    public BoundedHttp.Result send(HttpRequest request, int maxBytes, long deadlineMillis)
            throws IOException, InterruptedException {
        long budget = Math.max(1, deadlineMillis);
        Optional<Duration> rt = request.timeout();
        if (rt.isPresent()) {
            budget = Math.min(budget, Math.max(1, rt.get().toMillis()));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
        URI uri = request.uri();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        if (!https && !"http".equals(scheme)) {
            throw new EgressDeniedException("scheme not supported by the pinned transport: " + scheme);
        }
        EgressGuard.Pin pin = pinner.apply(uri);
        if (pin == null || !pin.allowed() || pin.addresses().isEmpty()) {
            throw new EgressDeniedException(pin == null ? "no egress decision" : String.valueOf(pin.denied()));
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : (https ? 443 : 80);
        byte[] body = bodyOf(request);
        Socket socket;
        if (proxy == null) {
            socket = connect(pin.addresses(), port, deadline);
        } else {
            EgressGuard.Pin proxyPin = pinner.apply(URI.create("http://" + hostForUri(proxy.host()) + ":" + proxy.port() + "/"));
            if (proxyPin == null || !proxyPin.allowed() || proxyPin.addresses().isEmpty()) {
                throw new EgressDeniedException("proxy " + (proxyPin == null ? "refused" : String.valueOf(proxyPin.denied())));
            }
            socket = connect(proxyPin.addresses(), proxy.port(), deadline);
            try {
                tunnel(socket, pin.host(), port, proxy.credential(), deadline);
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }
        try {
            if (https) {
                socket = tls(socket, pin.host(), port, deadline);
            }
            writeRequest(socket.getOutputStream(), request, uri, pin.host(), body);
            return readResponse(new DeadlineInput(socket, deadline), request.method(), maxBytes);
        } catch (SocketTimeoutException e) {
            throw new HttpTimeoutException("deadline exceeded");
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // the exchange already has its outcome
            }
        }
    }

    private static String hostForUri(String host) {
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    /** HTTP CONNECT to {@code host:port} through an already connected proxy socket; 2xx opens the tunnel. */
    private static void tunnel(Socket proxySocket, String host, int port, String credential, long deadline) throws IOException {
        String authority = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        StringBuilder connect = new StringBuilder("CONNECT ").append(authority).append(" HTTP/1.1\r\nHost: ")
                .append(authority).append("\r\n");
        if (credential != null && !credential.isEmpty()) {
            connect.append("Proxy-Authorization: ").append(credential).append("\r\n");
        }
        connect.append("\r\n");
        OutputStream out = proxySocket.getOutputStream();
        out.write(connect.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        DeadlineInput in = new DeadlineInput(proxySocket, deadline);
        int[] budget = {MAX_HEAD_BYTES};
        String status = readLine(in, budget);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.") || parts[1].length() != 3) {
            throw new IOException("malformed proxy CONNECT response");
        }
        readHeaders(in, budget);
        if (parts[1].charAt(0) != '2') {
            throw new IOException("proxy refused CONNECT with HTTP " + parts[1]);
        }
        if (in.buffered() > 0) {
            // Bytes after the CONNECT response head cannot be the target's: it has not been spoken to yet.
            throw new IOException("proxy sent data before the tunnel was used");
        }
    }

    private Socket connect(List<InetAddress> addresses, int port, long deadline) throws IOException {
        IOException last = null;
        for (InetAddress a : addresses) {
            long remaining = remaining(deadline);
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(a, port), (int) Math.min(Integer.MAX_VALUE, Math.min(connectTimeoutMillis, remaining)));
                return s;
            } catch (SocketTimeoutException e) {
                s.close();
                last = new HttpTimeoutException("connect timed out");
            } catch (IOException e) {
                s.close();
                last = e;
            }
        }
        throw last == null ? new IOException("no address to connect to") : last;
    }

    private Socket tls(Socket raw, String host, int port, long deadline) throws IOException {
        SSLContext ctx;
        try {
            ctx = ssl == null ? SSLContext.getDefault() : ssl;
        } catch (NoSuchAlgorithmException e) {
            raw.close();
            throw new IOException("no TLS context", e);
        }
        SSLSocket t = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
        SSLParameters p = t.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        if (!isIpLiteral(host)) {
            p.setServerNames(List.of(new SNIHostName(host)));
        }
        p.setApplicationProtocols(new String[] {"http/1.1"});
        t.setSSLParameters(p);
        t.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remaining(deadline)));
        t.startHandshake();
        return t;
    }

    static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private static void writeRequest(OutputStream out, HttpRequest request, URI uri, String host, byte[] body) throws IOException {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        String method = request.method();
        StringBuilder head = new StringBuilder(256);
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        String hostHeader = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        head.append("Host: ").append(hostHeader);
        if (uri.getPort() >= 0) {
            head.append(':').append(uri.getPort());
        }
        head.append("\r\n");
        for (Map.Entry<String, List<String>> e : request.headers().map().entrySet()) {
            for (String v : e.getValue()) {
                if (hasLineBreak(e.getKey()) || hasLineBreak(v)) {
                    throw new IOException("header contains a line break");
                }
                head.append(e.getKey()).append(": ").append(v).append("\r\n");
            }
        }
        boolean bodyMethod = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        if (body != null && (body.length > 0 || bodyMethod)) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        } else if (bodyMethod) {
            head.append("Content-Length: 0\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private static boolean hasLineBreak(String s) {
        return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
    }

    /** Collects the request body from its publisher (byte-array, string and empty publishers complete at once). */
    static byte[] bodyOf(HttpRequest request) throws IOException, InterruptedException {
        Optional<HttpRequest.BodyPublisher> p = request.bodyPublisher();
        if (p.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();
        p.get().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer b) {
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.write(chunk, 0, chunk.length);
            }

            @Override
            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IOException("request body publisher failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("request body publisher did not complete");
        }
        return out.toByteArray();
    }

    private static BoundedHttp.Result readResponse(DeadlineInput in, String method, int maxBytes) throws IOException {
        int[] headBudget = {MAX_HEAD_BYTES};
        int status;
        Map<String, List<String>> headers;
        while (true) {
            String statusLine = readLine(in, headBudget);
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/1.") || parts[1].length() != 3) {
                throw new IOException("malformed HTTP status line");
            }
            try {
                status = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("malformed HTTP status code");
            }
            headers = readHeaders(in, headBudget);
            if (status == 101) {
                throw new IOException("unexpected protocol upgrade");
            }
            if (status >= 200) {
                break;
            }
            // 1xx interim response: discard and read the final one.
        }
        HttpHeaders h = HttpHeaders.of(headers, (k, v) -> true);
        if ("HEAD".equals(method) || status == 204 || status == 304 || (status >= 300 && status < 400)) {
            return new BoundedHttp.Result(status, h, new byte[0]);
        }
        List<String> te = headers.getOrDefault("transfer-encoding", List.of());
        boolean chunked = false;
        for (String v : te) {
            if (v.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            }
        }
        byte[] body;
        if (chunked) {
            body = readChunked(in, maxBytes, headBudget);
        } else if (headers.containsKey("content-length")) {
            long len = contentLength(headers.get("content-length"));
            if (len > maxBytes) {
                throw new IOException("response exceeds " + maxBytes + " bytes");
            }
            body = readExactly(in, (int) len);
        } else {
            body = readToEnd(in, maxBytes);
        }
        return new BoundedHttp.Result(status, h, body);
    }

    private static long contentLength(List<String> values) throws IOException {
        Long len = null;
        for (String v : values) {
            for (String part : v.split(",")) {
                long n;
                try {
                    n = Long.parseLong(part.trim());
                } catch (NumberFormatException e) {
                    throw new IOException("malformed Content-Length");
                }
                if (n < 0 || (len != null && len != n)) {
                    throw new IOException("conflicting or negative Content-Length");
                }
                len = n;
            }
        }
        return len == null ? 0 : len;
    }

    private static Map<String, List<String>> readHeaders(InputStream in, int[] budget) throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        int count = 0;
        while (true) {
            String line = readLine(in, budget);
            if (line.isEmpty()) {
                return out;
            }
            if (++count > MAX_HEADERS) {
                throw new IOException("too many response headers");
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new IOException("obsolete header line folding is not accepted");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("malformed response header");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
    }

    /** One CRLF (or LF) terminated line, charged against the head budget. */
    private static String readLine(InputStream in, int[] budget) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("connection closed inside the response head");
            }
            if (--budget[0] < 0) {
                throw new IOException("response head exceeds " + MAX_HEAD_BYTES + " bytes");
            }
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
    }

    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(out, off, len - off);
            if (n < 0) {
                throw new IOException("connection closed before the full response body");
            }
            off += n;
        }
        return out;
    }

    private static byte[] readToEnd(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("response exceeds " + maxBytes + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in, int maxBytes, int[] budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = 0;
        while (true) {
            int[] lineBudget = {1024};
            String sizeLine = readLine(in, lineBudget);
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("malformed chunk size");
            }
            if (size < 0 || total + size > maxBytes) {
                throw new IOException("response exceeds " + maxBytes + " bytes");
            }
            if (size == 0) {
                readHeaders(in, budget); // trailers, discarded
                return out.toByteArray();
            }
            byte[] chunk = readExactly(in, (int) size);
            out.write(chunk, 0, chunk.length);
            total += (int) size;
            int[] crlf = {2};
            if (!readLine(in, crlf).isEmpty()) {
                throw new IOException("malformed chunk terminator");
            }
        }
    }

    private static long remaining(long deadline) throws HttpTimeoutException {
        long ms = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (ms <= 0) {
            throw new HttpTimeoutException("deadline exceeded");
        }
        return ms;
    }

    /** Socket input whose read timeout is recomputed from one total deadline before every read. */
    private static final class DeadlineInput extends InputStream {
        private final Socket socket;
        private final InputStream in;
        private final long deadline;
        private final byte[] one = new byte[1];
        private final byte[] buf = new byte[8192];
        private int pos;
        private int lim;

        DeadlineInput(Socket socket, long deadline) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.deadline = deadline;
        }

        int buffered() {
            return lim - pos;
        }

        private boolean fill() throws IOException {
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remaining(deadline)));
            int n;
            try {
                n = in.read(buf, 0, buf.length);
            } catch (SocketTimeoutException e) {
                throw new HttpTimeoutException("deadline exceeded while reading the response");
            }
            if (n <= 0) {
                return false;
            }
            pos = 0;
            lim = n;
            return true;
        }

        @Override
        public int read() throws IOException {
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (pos >= lim && !fill()) {
                return -1;
            }
            int n = Math.min(len, lim - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
