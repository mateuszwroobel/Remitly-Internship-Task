package com.remitly.stockexchange.api;

import com.remitly.stockexchange.model.Stock;
import com.remitly.stockexchange.state.MarketState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.remitly.stockexchange.api.HttpHelpers.MAPPER;
import static com.remitly.stockexchange.api.HttpHelpers.sendEmpty;

/**
 * Class responsible for defining handlers for internal cluster communication.
 * How internal requests are handled:
 * SyncWalletsHandler = blindly applies buy/sell operations bypassing validation
 * SyncStocksHandler = blindly applies bank inventory state
 * HealthHandler = returns 200 OK for Load Balancer health checks
 * Note: These endpoints are not meant to be called by external users, only by peer nodes.
 */
public class InternalHandlers {

    public static class SyncWalletsHandler implements HttpHandler {
        private final MarketState state;

        public SyncWalletsHandler(MarketState state) {
            this.state = state;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                // Path looks like /internal/sync/wallets/{wallet_id}/stocks/{stock_name}
                String[] segments = path.split("/");
                
                if (segments.length == 7) {
                    String walletId = segments[4];
                    String stockName = segments[6];
                    
                    Map<String, String> body = MAPPER.readValue(exchange.getRequestBody(), Map.class);
                    String type = body.get("type");
                    String requestId = exchange.getRequestHeaders().getFirst("X-Request-ID");

                    // Apply locally without re-broadcasting, completely bypassing validation
                    try {
                        if ("buy".equals(type)) {
                            state.forceBuyStock(walletId, stockName, requestId);
                        } else if ("sell".equals(type)) {
                            state.forceSellStock(walletId, stockName, requestId);
                        }
                    } catch (Exception ignored) {
                        // Already validated by the originating node
                    }
                }
                sendEmpty(exchange, 200);
            }
        }
    }

    public static class SyncStocksHandler implements HttpHandler {
        private final MarketState state;

        public SyncStocksHandler(MarketState state) {
            this.state = state;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    Map<String, List<Map<String, Object>>> body = MAPPER.readValue(exchange.getRequestBody(), Map.class);
                    List<Map<String, Object>> stocksData = body.get("stocks");
                    
                    List<Stock> newStocks = stocksData.stream()
                            .map(s -> new Stock((String) s.get("name"), (Integer) s.get("quantity")))
                            .toList();

                    state.setBankState(newStocks);
                } catch (Exception ignored) {}
                
                sendEmpty(exchange, 200);
            }
        }
    }

    public static class HealthHandler implements HttpHandler {
        private final MarketState state;

        public HealthHandler(MarketState state) {
            this.state = state;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (state.isReady()) {
                sendEmpty(exchange, 200);
            } else {
                com.remitly.stockexchange.api.HttpHelpers.sendError(exchange, 503, "Service Unavailable");
            }
        }
    }

    public static class StateDumpHandler implements HttpHandler {
        private final MarketState state;

        public StateDumpHandler(MarketState state) {
            this.state = state;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                com.remitly.stockexchange.api.HttpHelpers.sendJson(exchange, 200, state.dumpState());
            } else {
                com.remitly.stockexchange.api.HttpHelpers.sendError(exchange, 404, "Not Found");
            }
        }
    }
}
