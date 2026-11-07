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
package io.github.vaquarkhan.aegis.mcp.testing;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Minimal stdio MCP server process for tests. {@code args[0]} is "legacy" or "modern". Tool
 * {@code env_probe} reports whether the variable {@code AEGIS_TEST_SECRET} reached the child
 * (it must not: the stdio client passes a minimal environment).
 */
public final class TestStdioMcpServer {

    private TestStdioMcpServer() {}

    public static void main(String[] args) throws Exception {
        boolean modern = args.length > 0 && "modern".equals(args[0]);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        boolean initialized = false;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> msg = Json.parseObject(line);
            String method = (String) msg.get("method");
            if (method == null || !msg.containsKey("id")) {
                continue;
            }
            Object id = msg.get("id");
            Map<String, Object> params = JsonValues.objectOrEmpty(msg, "params");
            boolean modernReq = JsonValues.object(params, "_meta") != null
                    && JsonValues.object(params, "_meta").containsKey("io.modelcontextprotocol/protocolVersion");
            Object result;
            if (!modern && "server/discover".equals(method)) {
                out.println(Json.write(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32601L, "message", "Method not found"))));
                continue;
            }
            if (modern && !modernReq) {
                out.println(Json.write(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32600L, "message", "need _meta"))));
                continue;
            }
            switch (method) {
                case "server/discover" -> result = Map.of("resultType", "complete", "supportedVersions", List.of("2026-07-28"),
                        "capabilities", Map.of("tools", Map.of()), "ttlMs", 0L, "cacheScope", "public");
                case "initialize" -> {
                    initialized = true;
                    result = Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", "test-stdio", "version", "1"));
                }
                case "tools/list" -> {
                    if (!modern && !initialized) {
                        out.println(Json.write(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32600L, "message", "not initialized"))));
                        continue;
                    }
                    result = Map.of("tools", List.of(
                            Map.of("name", "echo", "description", "Echo text.", "inputSchema",
                                    Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")))),
                            Map.of("name", "env_probe", "description", "Report env.", "inputSchema", Map.of("type", "object"))));
                }
                case "tools/call" -> {
                    String name = (String) params.get("name");
                    Map<String, Object> a = JsonValues.objectOrEmpty(params, "arguments");
                    String text = "env_probe".equals(name)
                            ? "secret-visible=" + (System.getenv("AEGIS_TEST_SECRET") != null)
                            : "echo:" + a.get("text") + (modern ? " (modern)" : " (legacy)");
                    result = Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", false);
                }
                default -> {
                    out.println(Json.write(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32601L, "message", "Method not found"))));
                    continue;
                }
            }
            out.println(Json.write(Map.of("jsonrpc", "2.0", "id", id, "result", result)));
        }
    }
}
