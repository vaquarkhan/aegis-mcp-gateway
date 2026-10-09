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
package io.github.vaquarkhan.aegis.mcp.client;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.mcp.protocol.JsonRpc;
import io.github.vaquarkhan.aegis.mcp.protocol.McpVersions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio client for an existing MCP server launched as a subprocess (the most common way MCP
 * servers ship). Newline-delimited JSON-RPC over the child's stdin/stdout.
 *
 * <p>Security: the child gets a MINIMAL environment (a few OS variables plus what the operator
 * configures), never the gateway's full environment, so gateway secrets do not leak to a
 * third-party server process. Lines are bounded. Server-to-client requests are answered with
 * method-not-found (they would bypass governance). A dead child is restarted on the next call.
 *
 * <p>Era: {@code AUTO} probes with {@code server/discover} (2026-07-28) and falls back to the
 * legacy {@code initialize} handshake on a non-modern error or a probe timeout.
 */
public final class StdioMcpClient implements McpClient {

    private static final System.Logger LOG = System.getLogger(StdioMcpClient.class.getName());
    private static final List<String> BASE_ENV = List.of("PATH", "SYSTEMROOT", "SystemRoot", "WINDIR", "TEMP", "TMP",
            "HOME", "USERPROFILE", "LANG", "JAVA_HOME", "COMSPEC");

    private final List<String> command;
    private final Map<String, String> env;
    private final Path workingDir;
    private final HttpMcpClient.Era configured;
    private final String legacyVersion;
    private final int maxLineChars;
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private Process process;
    private volatile OutputStream stdin;
    private volatile HttpMcpClient.Era resolved;

    public StdioMcpClient(List<String> command, Map<String, String> env, Path workingDir, HttpMcpClient.Era era,
                          String legacyVersion, int maxLineChars) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("stdio MCP source needs a command");
        }
        this.command = List.copyOf(command);
        this.env = env == null ? Map.of() : Map.copyOf(env);
        this.workingDir = workingDir;
        this.configured = era == null ? HttpMcpClient.Era.AUTO : era;
        this.legacyVersion = legacyVersion == null ? McpVersions.V2025_11_25 : legacyVersion;
        this.maxLineChars = maxLineChars <= 0 ? 4 << 20 : maxLineChars;
    }

    @Override
    public URI endpoint() {
        return null;
    }

    @Override
    public List<Map<String, Object>> listTools(Map<String, String> headers, long timeoutMillis) throws McpCallException {
        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            Map<String, Object> r = ClientSupport.resultOf(request("tools/list", params, timeoutMillis));
            for (Object t : ClientSupport.list(r, "tools")) {
                if (ClientSupport.validTool(t)) {
                    out.add(JsonValues.asObject(t));
                }
            }
            cursor = JsonValues.string(r, "nextCursor");
            if (cursor == null || cursor.isEmpty()) {
                return out;
            }
        }
        throw new McpCallException("downstream tools/list pagination exceeded 50 pages");
    }

    @Override
    public Map<String, Object> callTool(String name, Map<String, Object> arguments, Map<String, String> headers,
                                        long timeoutMillis) throws McpCallException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        return ClientSupport.resultOf(request("tools/call", params, timeoutMillis));
    }

    @Override
    public Map<String, Object> call(String method, Map<String, Object> params, String mcpName, Map<String, String> headers,
                                    long timeoutMillis) throws McpCallException {
        return ClientSupport.resultOf(request(method, params == null ? Map.of() : params, timeoutMillis));
    }

    public HttpMcpClient.Era resolvedEra() {
        return resolved;
    }

    private Map<String, Object> request(String method, Map<String, Object> params, long timeoutMillis) throws McpCallException {
        ensureStarted(timeoutMillis);
        boolean modern = resolved == HttpMcpClient.Era.MODERN;
        return raw(method, modern ? ClientSupport.withModernMeta(params) : params, timeoutMillis);
    }

    private synchronized void ensureStarted(long timeoutMillis) throws McpCallException {
        if (process != null && process.isAlive() && resolved != null) {
            return;
        }
        stopProcess();
        ProcessBuilder pb = new ProcessBuilder(command);
        Map<String, String> childEnv = pb.environment();
        Map<String, String> keep = new LinkedHashMap<>();
        for (String k : BASE_ENV) {
            String v = System.getenv(k);
            if (v != null) {
                keep.put(k, v);
            }
        }
        childEnv.clear();
        childEnv.putAll(keep);
        childEnv.putAll(env);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new McpCallException("could not start stdio MCP server: " + e.getMessage(), null, false, e);
        }
        stdin = process.getOutputStream();
        Process p = process;
        Thread reader = new Thread(() -> readLoop(p), "aegis-stdio-mcp-out");
        reader.setDaemon(true);
        reader.start();
        Thread err = new Thread(() -> drain(p.getErrorStream()), "aegis-stdio-mcp-err");
        err.setDaemon(true);
        err.start();

        resolved = null;
        if (configured != HttpMcpClient.Era.LEGACY) {
            Map<String, Object> resp = null;
            McpCallException probeFailure = null;
            try {
                resp = raw("server/discover", ClientSupport.withModernMeta(Map.of()), Math.min(timeoutMillis, 5_000));
            } catch (McpCallException e) {
                probeFailure = e; // legacy servers may time out or reject the unknown method
            }
            if (resp != null) {
                Map<String, Object> result = JsonValues.object(resp, "result");
                List<Object> versions = result == null ? null : JsonValues.asList(result.get("supportedVersions"));
                if (versions != null && versions.contains(McpVersions.V2026_07_28)) {
                    resolved = HttpMcpClient.Era.MODERN;
                    return;
                }
                Map<String, Object> error = JsonValues.object(resp, "error");
                Long code = error == null ? null : JsonValues.integral(error.get("code"));
                if (code != null && code == JsonRpc.UNSUPPORTED_PROTOCOL_VERSION) {
                    throw new McpCallException("stdio MCP server does not support " + McpVersions.V2026_07_28);
                }
            }
            if (configured == HttpMcpClient.Era.MODERN) {
                throw probeFailure != null ? probeFailure
                        : new McpCallException("stdio MCP server did not answer server/discover as a 2026-07-28 server");
            }
            if (!process.isAlive()) {
                throw new McpCallException("stdio MCP server exited during startup");
            }
        }
        Map<String, Object> init = new LinkedHashMap<>();
        init.put("protocolVersion", legacyVersion);
        init.put("capabilities", Map.of());
        init.put("clientInfo", ClientSupport.clientInfo());
        Map<String, Object> r = ClientSupport.resultOf(raw("initialize", init, timeoutMillis));
        String v = JsonValues.string(r, "protocolVersion");
        if (v == null || !McpVersions.isLegacy(v)) {
            throw new McpCallException("stdio MCP server negotiated an unsupported version");
        }
        write(JsonRpc.notification("notifications/initialized", null));
        resolved = HttpMcpClient.Era.LEGACY;
    }

    private Map<String, Object> raw(String method, Map<String, Object> params, long timeoutMillis) throws McpCallException {
        long id = ids.incrementAndGet();
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            write(JsonRpc.request(id, method, params));
            return f.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new McpCallException("stdio MCP call timed out", null, true, e);
        } catch (ExecutionException e) {
            throw new McpCallException("stdio MCP server failed: " + e.getCause().getMessage(), null, false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpCallException("interrupted", null, false, e);
        } finally {
            pending.remove(id);
        }
    }

    private void write(Map<String, Object> msg) throws McpCallException {
        // Separate lock from the lifecycle monitor, so the reader thread can answer a server request
        // while a caller holds the lifecycle lock during startup.
        synchronized (writeLock) {
            OutputStream out = stdin;
            if (out == null) {
                throw new McpCallException("stdio MCP server is not running");
            }
            try {
                out.write((Json.write(msg) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new McpCallException("stdio MCP server is not accepting input", null, false, e);
            }
        }
    }

    private void readLoop(Process p) {
        JsonLimits limits = JsonLimits.DEFAULT.withMaxBytes(maxLineChars * 4);
        try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = r.read()) >= 0) {
                if (c == '\n') {
                    handleLine(line.toString().trim(), limits);
                    line.setLength(0);
                } else if (line.length() < maxLineChars) {
                    line.append((char) c);
                } else {
                    failAll(new IOException("stdio MCP line exceeds " + maxLineChars + " characters"));
                    p.destroyForcibly();
                    return;
                }
            }
        } catch (IOException e) {
            // stream closed
        }
        failAll(new IOException("stdio MCP server exited"));
    }

    private void handleLine(String line, JsonLimits limits) {
        if (line.isEmpty()) {
            return;
        }
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(line.getBytes(StandardCharsets.UTF_8), limits);
        } catch (JsonException e) {
            LOG.log(System.Logger.Level.WARNING, "ignoring non-JSON line from stdio MCP server");
            return;
        }
        if (msg.get("method") instanceof String m) {
            if (msg.containsKey("id")) {
                try {
                    write(JsonRpc.error(msg.get("id"), JsonRpc.METHOD_NOT_FOUND,
                            "server-to-client request '" + m.toLowerCase(Locale.ROOT) + "' is not relayed by Aegis", null));
                } catch (McpCallException ignored) {
                    // child is going away
                }
            }
            return;
        }
        Long id = JsonValues.integral(msg.get("id"));
        CompletableFuture<Map<String, Object>> f = id == null ? null : pending.get(id);
        if (f != null) {
            f.complete(msg);
        }
    }

    private void failAll(Exception e) {
        for (CompletableFuture<Map<String, Object>> f : pending.values()) {
            f.completeExceptionally(e);
        }
    }

    private static void drain(InputStream err) {
        byte[] buf = new byte[4096];
        try (InputStream in = err) {
            while (in.read(buf) >= 0) {
                // discard; operators capture the child's stderr via the child's own logging
            }
        } catch (IOException ignored) {
            // closed
        }
    }

    private void stopProcess() {
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
        }
    }

    @Override
    public synchronized void close() {
        stopProcess();
    }
}
