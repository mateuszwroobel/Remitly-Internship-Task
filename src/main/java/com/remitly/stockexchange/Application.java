package com.remitly.stockexchange;

import com.remitly.stockexchange.api.InternalHandlers;
import com.remitly.stockexchange.api.PublicHandlers;
import com.remitly.stockexchange.cluster.LoadBalancer;
import com.remitly.stockexchange.cluster.Replicator;
import com.remitly.stockexchange.state.MarketState;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Main entry point class responsible for starting the application.
 * How node role is determined:
 * mode = "lb" (Load Balancer) or "node" (Market Node)
 * peers = list of other nodes to communicate with
 * Then the application starts an HTTP server on a specified port using Virtual Threads.
 * Note: In lb mode, it routes traffic based on stock name to prevent write skew.
 * In node mode, it handles public API and internal replication endpoints.
 */
public class Application {
    
    public static void main(String[] args) throws Exception {
        String mode = "node"; // "node" or "lb"
        int port = 8080;
        List<String> peers = List.of();
        
        // Very basic arg parsing
        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                mode = arg.substring(7);
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring(7));
            } else if (arg.startsWith("--peers=") || arg.startsWith("--backends=")) {
                peers = Arrays.asList(arg.substring(arg.indexOf('=') + 1).split(","));
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // Use Virtual Threads for all HTTP request handling
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        if ("lb".equals(mode)) {
            System.out.println("Starting Load Balancer on port " + port + " routing to " + peers);
            LoadBalancer lb = new LoadBalancer(peers);
            server.createContext("/", lb);
            server.start();
        } else {
            System.out.println("Starting Node on port " + port + " with peers " + peers);
            MarketState state = new MarketState();
            Replicator replicator = new Replicator(peers);

            // Public endpoints
            server.createContext("/wallets", new PublicHandlers.WalletsHandler(state, replicator));
            server.createContext("/stocks", new PublicHandlers.StocksHandler(state, replicator));
            server.createContext("/log", new PublicHandlers.LogHandler(state));
            server.createContext("/chaos", new PublicHandlers.ChaosHandler());

            // Internal replication endpoints
            server.createContext("/internal/sync/wallets", new InternalHandlers.SyncWalletsHandler(state));
            server.createContext("/internal/sync/stocks", new InternalHandlers.SyncStocksHandler(state));
            server.createContext("/internal/health", new InternalHandlers.HealthHandler(state));
            server.createContext("/internal/state/dump", new InternalHandlers.StateDumpHandler(state));

            server.start();

            // Bootstrapping process in a background virtual thread
            final List<String> finalPeers = peers;
            Thread.ofVirtual().start(() -> {
                if (finalPeers.isEmpty()) {
                    System.out.println("No peers configured. Starting as isolated node.");
                    state.setReady(true);
                    return;
                }

                System.out.println("Bootstrapping: Attempting to fetch state from peers...");
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(2))
                        .build();

                for (String peer : finalPeers) {
                    try {
                        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create("http://" + peer + "/internal/state/dump"))
                                .GET()
                                .timeout(java.time.Duration.ofSeconds(2))
                                .build();

                        java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() == 200) {
                            java.util.Map<String, Object> dump = com.remitly.stockexchange.api.HttpHelpers.MAPPER.readValue(response.body(), java.util.Map.class);
                            state.restoreFromDump(dump);
                            System.out.println("Successfully bootstrapped state from peer: " + peer);
                            state.setReady(true);
                            return;
                        }
                    } catch (Exception e) {
                        System.out.println("Could not fetch state from peer: " + peer + " (" + e.getMessage() + ")");
                    }
                }

                System.out.println("Failed to fetch state from any peer. Starting with empty state.");
                state.setReady(true);
            });
        }
    }
}
