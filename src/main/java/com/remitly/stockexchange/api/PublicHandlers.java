package com.remitly.stockexchange.api;

import com.remitly.stockexchange.cluster.Replicator;
import com.remitly.stockexchange.model.Stock;
import com.remitly.stockexchange.model.Wallet;
import com.remitly.stockexchange.state.MarketState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.remitly.stockexchange.api.HttpHelpers.*;

/**
 * Class responsible for defining handlers for the public REST API.
 * How public endpoints are processed:
 * WalletsHandler = processes user wallet queries and buy/sell operations
 * StocksHandler = processes bank inventory queries and initialization
 * LogHandler = returns the complete chronological audit log
 * ChaosHandler = simulates node crash for testing high availability
 * Note: Any mutating operation (buy/sell/init) is first applied locally and then asynchronously broadcast to peers via Replicator.
 */
public class PublicHandlers {
    
    public static class WalletsHandler implements HttpHandler {
        private final MarketState state;
        private final Replicator replicator;

        public WalletsHandler(MarketState state, Replicator replicator) {
            this.state = state;
            this.replicator = replicator;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String[] segments = path.split("/");

            if (segments.length == 3 && "GET".equals(method)) {
                // GET /wallets/{wallet_id}
                Wallet wallet = state.getWallet(segments[2]);
                sendJson(exchange, 200, wallet);
                return;
            }

            if (segments.length == 5 && "GET".equals(method)) {
                // GET /wallets/{wallet_id}/stocks/{stock_name}
                int quantity = state.getWalletStockQuantity(segments[2], segments[4]);
                sendJson(exchange, 200, quantity);
                return;
            }

            if (segments.length == 5 && "POST".equals(method)) {
                // POST /wallets/{wallet_id}/stocks/{stock_name}
                try {
                    Map<String, String> body = MAPPER.readValue(exchange.getRequestBody(), Map.class);
                    String type = body.get("type");
                    String walletId = segments[2];
                    String stockName = segments[4];
                    String requestId = exchange.getRequestHeaders().getFirst("X-Request-ID");

                    boolean success = false;
                    try {
                        if ("buy".equals(type)) {
                            success = state.buyStock(walletId, stockName, requestId);
                        } else if ("sell".equals(type)) {
                            success = state.sellStock(walletId, stockName, requestId);
                        } else {
                            sendError(exchange, 400, "Invalid type, must be 'buy' or 'sell'");
                            return;
                        }
                    } catch (IllegalArgumentException e) {
                        sendError(exchange, 404, e.getMessage()); // Unknown stock
                        return;
                    }

                    if (success) {
                        // Replicate successful operation
                        byte[] payload = MAPPER.writeValueAsBytes(body);
                        replicator.broadcastAsync("/internal/sync" + path, payload, requestId);
                        sendEmpty(exchange, 200);
                    } else {
                        sendError(exchange, 400, "Condition not met (no stock in bank or wallet)");
                    }
                } catch (Exception e) {
                    sendError(exchange, 400, "Invalid JSON body");
                }
                return;
            }

            sendError(exchange, 404, "Not Found");
        }
    }

    public static class StocksHandler implements HttpHandler {
        private final MarketState state;
        private final Replicator replicator;

        public StocksHandler(MarketState state, Replicator replicator) {
            this.state = state;
            this.replicator = replicator;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            if ("GET".equals(method)) {
                // GET /stocks
                sendJson(exchange, 200, Map.of("stocks", state.getBankState()));
                return;
            }

            if ("POST".equals(method)) {
                // POST /stocks
                try {
                    byte[] rawBody = exchange.getRequestBody().readAllBytes();
                    Map<String, List<Map<String, Object>>> body = MAPPER.readValue(rawBody, Map.class);
                    List<Map<String, Object>> stocksData = body.get("stocks");
                    
                    List<Stock> newStocks = stocksData.stream()
                            .map(s -> new Stock((String) s.get("name"), (Integer) s.get("quantity")))
                            .toList();

                    state.setBankState(newStocks);
                    
                    // Replicate
                    String requestId = exchange.getRequestHeaders().getFirst("X-Request-ID");
                    replicator.broadcastAsync("/internal/sync/stocks", rawBody, requestId);
                    sendEmpty(exchange, 200);
                } catch (Exception e) {
                    sendError(exchange, 400, "Invalid JSON body");
                }
                return;
            }

            sendError(exchange, 404, "Not Found");
        }
    }

    public static class LogHandler implements HttpHandler {
        private final MarketState state;

        public LogHandler(MarketState state) {
            this.state = state;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, Map.of("log", state.getAuditLog()));
            } else {
                sendError(exchange, 404, "Not Found");
            }
        }
    }

    public static class ChaosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                sendEmpty(exchange, 200);
                
                // Exit asynchronously so the 200 OK can be sent back to client
                Thread.startVirtualThread(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}
                    System.out.println("Chaos monkey active! Committing suicide.");
                    System.exit(0);
                });
            } else {
                sendError(exchange, 404, "Not Found");
            }
        }
    }
}
