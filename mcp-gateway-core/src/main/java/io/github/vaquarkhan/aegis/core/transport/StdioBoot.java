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
