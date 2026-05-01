package com.remitly.stockexchange.cluster;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Class responsible for broadcasting state mutations to peer nodes.
 * How replication is handled:
 * path = API endpoint path to replicate to
 * payload = JSON byte array containing the exact request body
 * requestId = unique identifier for idempotency
 * Then the node sends an asynchronous HTTP POST to all active peers using Virtual Threads.
 * Dead peers are automatically removed from the active pool on failure.
 * Note: Implements High Availability by avoiding distributed consensus and instead replicating exactly what was processed.
 */
public class Replicator {
    private final List<String> peers;
    private final HttpClient httpClient;

    public Replicator(List<String> initialPeers) {
        this.peers = new CopyOnWriteArrayList<>(initialPeers);

        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * Broadcasts an HTTP POST asynchronously to all known peers.
     * Dead peers are NOT removed from the list to allow them to recover.
     */
    public void broadcastAsync(String path, byte[] payload, String requestId) {
        if (peers.isEmpty()) {
            return;
        }

        for (String peer : peers) {
            Thread.startVirtualThread(() -> {
                try {
                    HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                            .uri(URI.create("http://" + peer + path))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                            .timeout(Duration.ofSeconds(2));
                            
                    if (requestId != null) {
                        reqBuilder.header("X-Request-ID", requestId);
                    }
                    
                    HttpRequest request = reqBuilder.build();

                    HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                    
                    if (response.statusCode() >= 500) {
                        System.err.println("Peer " + peer + " returned error: " + response.statusCode());
                    }
                } catch (Exception e) {
                    System.err.println("Peer " + peer + " unreachable (" + e.getMessage() + "), will retry next time.");
                }
            });
        }
    }
}
