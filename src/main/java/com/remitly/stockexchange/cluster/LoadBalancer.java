package com.remitly.stockexchange.cluster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class responsible for routing HTTP traffic to available backend nodes.
 * How routing is computed:
 * routingKey = stock name (if present in URL) or "ALL_STOCKS" (for initial population)
 * Then the chosen backend is determined by modulo-based sticky routing on the routingKey.
 * If no routingKey is found, it falls back to a simple Round-Robin strategy.
 * Note: Includes an active health checker running in the background to remove dead nodes.
 * Ensures Write Skew is prevented by routing requests for the same stock to the same node.
 */
public class LoadBalancer implements HttpHandler {
    private final List<String> allBackends;
    private volatile List<String> activeBackends;
    private final HttpClient proxyClient;
    private final AtomicInteger roundRobin = new AtomicInteger(0);

    public LoadBalancer(List<String> initialBackends) {
        this.allBackends = new CopyOnWriteArrayList<>(initialBackends);
        this.activeBackends = List.copyOf(initialBackends);
        this.proxyClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(2))
                .build();
                
        startHealthChecker();
    }

    private void startHealthChecker() {
        Thread.startVirtualThread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                    List<String> healthyNow = new java.util.ArrayList<>();
                    for (String backend : allBackends) {
                        if (isHealthy(backend)) {
                            healthyNow.add(backend);
                        }
                    }
                    activeBackends = List.copyOf(healthyNow);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private boolean isHealthy(String backend) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + backend + "/internal/health"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build();
            HttpResponse<Void> response = proxyClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String selectBackend(HttpExchange exchange) {
        List<String> currentBackends = activeBackends;
        if (currentBackends.isEmpty()) {
            throw new IllegalStateException("No healthy backends available");
        }
        
        String path = exchange.getRequestURI().getPath();
        String[] segments = path.split("/");
        String routingKey = null;

        // Example: /wallets/w1/stocks/AAPL -> ["", "wallets", "w1", "stocks", "AAPL"]
        if (segments.length >= 5 && "stocks".equals(segments[3])) {
            routingKey = segments[4];
        } else if ("/stocks".equals(path) && "POST".equals(exchange.getRequestMethod())) {
            routingKey = "ALL_STOCKS";
        }

        if (routingKey != null) {
            // Modulo-based sticky routing
            int index = (routingKey.hashCode() & Integer.MAX_VALUE) % currentBackends.size();
            return currentBackends.get(index);
        }

        // Fallback to round-robin
        int index = (roundRobin.getAndIncrement() & Integer.MAX_VALUE) % currentBackends.size();
        return currentBackends.get(index);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path != null && path.startsWith("/internal/")) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        String backend;
        try {
            backend = selectBackend(exchange);
        } catch (IllegalStateException e) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }

        try (InputStream reqIs = exchange.getRequestBody();
             java.io.OutputStream resOs = exchange.getResponseBody()) {
             
            byte[] body = reqIs.readAllBytes();
            
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + backend + exchange.getRequestURI().toString()))
                    .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(5));
                    
            // Copy headers, skip restricted ones
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (!k.equalsIgnoreCase("Host") && !k.equalsIgnoreCase("Connection")) {
                    for (String val : v) {
                        reqBuilder.header(k, val);
                    }
                }
            });

            HttpResponse<InputStream> response = proxyClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            // Copy response headers
            response.headers().map().forEach((k, v) -> {
                for (String val : v) {
                    exchange.getResponseHeaders().add(k, val);
                }
            });

            // Handle content length properly. Chunked (-1), Exact (>0), or Empty (0)
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            boolean noBody = response.statusCode() == 204 || response.statusCode() == 304;
            long headerArg = noBody ? -1 : (contentLength == -1 ? 0 : contentLength);
            
            exchange.sendResponseHeaders(response.statusCode(), headerArg);
            
            if (!noBody) {
                try (InputStream proxyIs = response.body()) {
                    proxyIs.transferTo(resOs);
                }
            }

        } catch (Exception e) {
            try {
                exchange.sendResponseHeaders(502, -1);
            } catch (IOException ignored) {}
        } finally {
            exchange.close();
        }
    }
}
