package com.e2eechat.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class MetricsServer {
    private static final Logger logger = LoggerFactory.getLogger(MetricsServer.class);
    
    private HttpServer server;
    private final int port;

    public MetricsServer(int port) {
        this.port = port;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/metrics", new MetricsHandler());
            server.setExecutor(null); // creates a default executor
            server.start();
            logger.info("Metrics server started on port {}", port);
        } catch (IOException e) {
            logger.error("Failed to start metrics server on port {}", port, e);
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

            byte[] response = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }
}
