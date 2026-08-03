package io.github.vaquarkhan.aegis.core.transport;

import io.github.vaquarkhan.aegis.core.observability.Metrics;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import java.util.EnumSet;
import java.util.function.BooleanSupplier;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded Jetty host for the streamable HTTP MCP transport plus the operational endpoints.
 *
 * <p>The authentication filter is attached to the MCP path only. Ops endpoints stay unauthenticated
 * so orchestrators can probe them, which is safe because they expose no tool surface.
 *
 * @author Viquar Khan
 */
public final class StreamableHttpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(StreamableHttpTransport.class);

    private StreamableHttpTransport() {}

    public static Server start(
            String host,
            int port,
            String pathSpec,
            HttpServlet mcpServlet,
            Filter authFilter,
            Metrics metrics,
            BooleanSupplier readyCheck,
            TlsSettings tls) throws Exception {
        return start(host, port, pathSpec, mcpServlet, authFilter,
                new OpsServlet(metrics, readyCheck), tls);
    }

    /**
     * Starts the listener with a caller supplied {@link OpsServlet}, so the shutdown hook can call
     * {@link OpsServlet#markNotLive()} and fail probes while the process drains.
     */
    public static Server start(
            String host,
            int port,
            String pathSpec,
            HttpServlet mcpServlet,
            Filter authFilter,
            OpsServlet ops,
            TlsSettings tls) throws Exception {
        return start(host, port, pathSpec, mcpServlet, authFilter, ops, tls, false);
    }

    /**
     * Starts the listener, optionally insisting on the streamable HTTP hint headers.
     *
     * @param requireMcpHeaders reject a POST to the MCP path that carries no {@code Mcp-Method}
     */
    public static Server start(
            String host,
            int port,
            String pathSpec,
            HttpServlet mcpServlet,
            Filter authFilter,
            OpsServlet ops,
            TlsSettings tls,
            boolean requireMcpHeaders) throws Exception {

        Server server = new Server();
        if (tls != null && tls.enabled()) {
            SslContextFactory.Server ssl = new SslContextFactory.Server();
            ssl.setKeyStorePath(tls.keystorePath());
            ssl.setKeyStorePassword(tls.keystorePassword());
            ssl.setKeyStoreType(tls.keystoreType());

            HttpConfiguration httpsConf = new HttpConfiguration();
            httpsConf.addCustomizer(new SecureRequestCustomizer());

            ServerConnector tlsConnector = new ServerConnector(
                    server,
                    new SslConnectionFactory(ssl, HttpVersion.HTTP_1_1.asString()),
                    new HttpConnectionFactory(httpsConf));
            tlsConnector.setHost(host);
            tlsConnector.setPort(port);
            tlsConnector.setIdleTimeout(60_000);
            server.addConnector(tlsConnector);
        } else {
            ServerConnector connector = new ServerConnector(server);
            connector.setHost(host);
            connector.setPort(port);
            connector.setIdleTimeout(60_000);
            server.addConnector(connector);
        }

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder mcpHolder = new ServletHolder(mcpServlet);
        mcpHolder.setAsyncSupported(true);
        context.addServlet(mcpHolder, pathSpec);
        if (authFilter != null) {
            context.addFilter(new FilterHolder(authFilter), pathSpec,
                    EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        }
        // Registered after authentication so an anonymous caller learns nothing from the shape of
        // a 400 that a 401 has not already told it.
        context.addFilter(new FilterHolder(new McpHeaderFilter(requireMcpHeaders)), pathSpec,
                EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        OpsServlet opsServlet = ops == null ? new OpsServlet(null, null) : ops;
        context.addServlet(new ServletHolder(opsServlet), "/healthz");
        context.addServlet(new ServletHolder(opsServlet), "/readyz");
        context.addServlet(new ServletHolder(opsServlet), "/metrics");
        context.addServlet(new ServletHolder(opsServlet), "/audit/verify");

        server.setHandler(context);
        server.setStopAtShutdown(true);
        server.setStopTimeout(10_000);
        server.start();

        String scheme = (tls != null && tls.enabled()) ? "https" : "http";
        LOG.info("http transport listening on {}://{}:{} (mcp={}, healthz=/healthz, metrics=/metrics, "
                + "requireMcpHeaders={})", scheme, host, port, pathSpec, requireMcpHeaders);
        return server;
    }

    public static Server start(
            String host,
            int port,
            String pathSpec,
            HttpServlet mcpServlet,
            Filter authFilter,
            Metrics metrics,
            BooleanSupplier readyCheck) throws Exception {
        return start(host, port, pathSpec, mcpServlet, authFilter, metrics, readyCheck, TlsSettings.disabled());
    }

    public static void stopQuietly(Server server, long timeoutMillis) {
        if (server == null) {
            return;
        }
        try {
            server.setStopTimeout(timeoutMillis);
            server.stop();
        } catch (Exception e) {
            LOG.warn("jetty stop error: {}", e.getMessage());
        }
    }
}
