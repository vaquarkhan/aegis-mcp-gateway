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

import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.server.McpServerHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * stdio front door: the gateway runs as the MCP server a desktop client launches. The identity is
 * the one configured under {@code host.stdio} (the OS user that launched the process owns
 * the pipe; there is no bearer token on stdio). Logs go to stderr only; stdout carries protocol.
 */
public final class StdioHost {

    private static final int MAX_LINE = 1 << 20;

    private StdioHost() {}

    public static void run(Gateway gateway, CallerIdentity identity, InputStream in, OutputStream out) throws IOException {
        McpServerHandler.StdioConnection conn = gateway.mcp().stdio(identity);
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 64 * 1024);
        StringBuilder line = new StringBuilder();
        int c;
        boolean overflow = false;
        while ((c = r.read()) >= 0) {
            if (c == '\n') {
                if (overflow) {
                    ps.println("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"message too large\"}}");
                } else if (!line.toString().isBlank()) {
                    String resp = conn.handleLine(line.toString());
                    if (resp != null) {
                        ps.println(resp);
                    }
                }
                line.setLength(0);
                overflow = false;
            } else if (line.length() < MAX_LINE) {
                line.append((char) c);
            } else {
                overflow = true;
            }
        }
    }
}
