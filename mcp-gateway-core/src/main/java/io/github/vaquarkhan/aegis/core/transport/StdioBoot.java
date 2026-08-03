/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * stdio transport factory.
 *
 * <p>stdout belongs exclusively to MCP JSON-RPC framing. Anything else written there corrupts the
 * protocol stream, which is why every logger in this project is configured to stderr and why the
 * project forbids {@code System.out.println} outside the approval mint CLI.
 *
 * @author Viquar Khan
 */
public final class StdioBoot {

    private static final Logger LOG = LoggerFactory.getLogger(StdioBoot.class);

    private StdioBoot() {}

    public static StdioServerTransportProvider create(McpJsonMapper json) {
        if (json == null) {
            throw new IllegalArgumentException("McpJsonMapper required");
        }
        LOG.info("stdio transport ready (stdout=MCP JSON-RPC, stderr=logs)");
        return new StdioServerTransportProvider(json);
    }

    /** Blocks the calling thread for the lifetime of the stdio session. */
    public static void await() throws InterruptedException {
        Thread.currentThread().join();
    }
}
