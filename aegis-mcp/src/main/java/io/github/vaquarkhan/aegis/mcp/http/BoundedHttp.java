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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbound HTTP with a TOTAL deadline and a byte bound. {@code HttpRequest.timeout} only covers
 * the wait for response headers; a backend that sends headers and then trickles the body would
 * otherwise hold a worker (and a half-open breaker probe) forever. A watchdog closes the body
 * stream at the deadline, which unblocks the read.
 */
public final class BoundedHttp {

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aegis-http-deadline");
        t.setDaemon(true);
        return t;
    });

    private BoundedHttp() {}

    /** Status, headers and the bounded body. */
    public record Result(int status, HttpHeaders headers, byte[] body) {}

    /**
     * @throws HttpTimeoutException when the whole exchange exceeds {@code deadlineMillis}
     * @throws IOException          on transport failure or when the body exceeds {@code maxBytes}
     */
    public static Result send(HttpClient http, HttpRequest request, int maxBytes, long deadlineMillis)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        HttpResponse<InputStream> resp = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        long remaining = deadlineMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        InputStream in = resp.body();
        int status = resp.statusCode();
        // Redirects are not followed (HttpClient.Redirect.NEVER). Return the status with an empty
        // body instead of reading a stream the server may close immediately after the Location
        // header — that path produced a bare IOException and hid the 3xx status from callers.
        if (status >= 300 && status < 400) {
            try {
                in.close();
            } catch (IOException ignored) {
                // status is what the caller needs
            }
            return new Result(status, resp.headers(), new byte[0]);
        }
        if (remaining <= 0) {
            in.close();
            throw new HttpTimeoutException("deadline exceeded before the response body");
        }
        AtomicBoolean expired = new AtomicBoolean(false);
        ScheduledFuture<?> guard = WATCHDOG.schedule(() -> {
            expired.set(true);
            try {
                in.close();
            } catch (IOException ignored) {
                // closing to unblock the reader
            }
        }, remaining, TimeUnit.MILLISECONDS);
        try (in) {
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
            if (expired.get()) {
                throw new HttpTimeoutException("deadline exceeded while reading the response body");
            }
            return new Result(resp.statusCode(), resp.headers(), out.toByteArray());
        } catch (IOException e) {
            if (expired.get()) {
                throw new HttpTimeoutException("deadline exceeded while reading the response body");
            }
            throw e;
        } finally {
            guard.cancel(false);
        }
    }
}
