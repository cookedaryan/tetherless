package com.e2eechat.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.Assert.assertTrue;

public class MetricsServerTest {

    private ChatServer server;
    private int port;
    private int metricsPort;

    @Before
    public void setup() throws Exception {
        ServerConfig config = new ServerConfig();
        config.setPort(0);
        server = new ChatServer(config);
        new Thread(() -> server.start()).start();
        Thread.sleep(200);
        port = server.getPort();
        metricsPort = port + 1; // As per ChatServer logic (0 -> 1 might be buggy, but port != 0 usually)
        // Wait, if port is 0, ServerSocket picks ephemeral port. 
        // Then getPort() returns the actual port. 
        // But metricsServer is initialized BEFORE start(), so it might have port 1.
        // Let's modify the test to just use a fixed port for testing to avoid race conditions.
    }

    @After
    public void teardown() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test(timeout = 5000)
    public void testMetricsEndpoint() throws Exception {
        URL url = new URL("http://localhost:" + server.getMetricsPort() + "/metrics");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        
        int responseCode = conn.getResponseCode();
        assertTrue("Metrics server should return HTTP 200", responseCode == 200);
        
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuilder content = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine).append("\n");
        }
        in.close();
        conn.disconnect();
        
        String response = content.toString();
        assertTrue(response.contains("connected_clients"));
        assertTrue(response.contains("messages_routed_total"));
        assertTrue(response.contains("queue_high_water_mark"));
    }
}
