package com.e2eechat.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public class MetricsServer {
    private static final Logger logger = LoggerFactory.getLogger(MetricsServer.class);
    
    /** Where an unconfigured relay listens. See {@link ServerConfig#getMetricsHost()}. */
    static final String DEFAULT_HOST = "127.0.0.1";

    private HttpServer server;
    private final int port;
    private final String host;

    public MetricsServer(int port) {
        this(port, DEFAULT_HOST);
    }

    public MetricsServer(int port, String host) {
        this.port = port;
        this.host = host == null || host.isEmpty() ? DEFAULT_HOST : host;
    }

    /**
     * Starts the endpoint on the configured interface.
     *
     * <p>Bound explicitly rather than by passing a bare port, which is the wildcard address and
     * meant {@code /metrics} was served on every interface the host had. There is no
     * authentication on it, so on a public relay that was operational telemetry - active clients,
     * messages routed, buffer overflows - published to anyone who connected.
     */
    public void start() {
        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(host), port);
            server = HttpServer.create(address, 0);
            server.createContext("/metrics", new MetricsHandler());
            server.setExecutor(null); // creates a default executor
            server.start();
            if (!DEFAULT_HOST.equals(host) && !"localhost".equals(host)) {
                logger.warn("Metrics endpoint is bound to {}, not loopback. /metrics is "
                        + "unauthenticated: anything that can reach {}:{} can read this relay's "
                        + "traffic counters.", host, host, server.getAddress().getPort());
            }
            logger.info("Metrics server started on {}:{}", host, server.getAddress().getPort());
        } catch (UnknownHostException e) {
            logger.error("Metrics host {} could not be resolved; endpoint not started", host, e);
        } catch (IOException e) {
            logger.error("Failed to start metrics server on {}:{}", host, port, e);
        }
    }

    public int getPort() {
        return server != null ? server.getAddress().getPort() : port;
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            logger.info("Metrics server stopped.");
        }
    }

    private static class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("connected_clients ").append(Metrics.connectedClients.get()).append("\n");
            sb.append("messages_routed_total ").append(Metrics.messagesRouted.get()).append("\n");
            sb.append("queue_high_water_mark ").append(Metrics.queueHighWaterMark.get()).append("\n");
            sb.append("rejected_server_full_total ").append(Metrics.rejectedServerFull.get()).append("\n");
            sb.append("rejected_too_many_connections_total ").append(Metrics.rejectedTooManyConnections.get()).append("\n");
            sb.append("rejected_rate_limit_total ").append(Metrics.rejectedRateLimit.get()).append("\n");
            sb.append("rejected_buffer_overflow_total ").append(Metrics.rejectedBufferOverflow.get()).append("\n");
            sb.append("rejected_unauthenticated_total ").append(Metrics.rejectedUnauthenticated.get()).append("\n");
            sb.append("rejected_spoofed_sender_total ").append(Metrics.rejectedSpoofedSender.get()).append("\n");

            byte[] response = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }
}
