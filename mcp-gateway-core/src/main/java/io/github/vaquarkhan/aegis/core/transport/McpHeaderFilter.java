package io.github.vaquarkhan.aegis.core.transport;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the streamable HTTP hint headers and, when configured, insists on them.
 *
 * <p>{@code Mcp-Method} and {@code Mcp-Name} let a proxy or an operator see which JSON-RPC method
 * and tool a request carries without parsing or logging the body, which would otherwise mean
 * writing tool arguments into the access log. The values are hints from the client and are never
 * used for routing or authorization; the request body remains the only source of truth.
 *
 * <p>{@code MCP_GW_REQUIRE_MCP_HEADERS=true} turns the hint into a requirement for POST, which is
 * useful in front of an edge proxy that routes or rate limits on the header. It is off by default
 * because a compliant client that omits the header is not doing anything unsafe.
 *
 * @author Viquar Khan
 */
public final class McpHeaderFilter implements Filter {

    public static final String HEADER_METHOD = "Mcp-Method";
    public static final String HEADER_NAME = "Mcp-Name";

    private static final Logger LOG = LoggerFactory.getLogger(McpHeaderFilter.class);

    private final boolean required;

    public McpHeaderFilter(boolean required) {
        this.required = required;
    }

    public boolean required() {
        return required;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }
        String method = trimToNull(req.getHeader(HEADER_METHOD));
        String name = trimToNull(req.getHeader(HEADER_NAME));
        if (method != null || name != null) {
            LOG.debug("mcp request headers method={} name={}",
                    method == null ? "-" : method, name == null ? "-" : name);
        }
        if (required && method == null && "POST".equalsIgnoreCase(req.getMethod())) {
            LOG.debug("rejecting POST without {} because MCP_GW_REQUIRE_MCP_HEADERS=true", HEADER_METHOD);
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.getOutputStream().write(
                    "{\"error\":\"missing_mcp_method\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
