package com.remitly.stockexchange.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * Class responsible for providing utility methods for HTTP responses.
 * How responses are created:
 * MAPPER = shared Jackson ObjectMapper instance for JSON serialization
 * Methods serialize objects to JSON, set the Content-Type header, and handle byte transfers.
 * Note: Used by all handlers to simplify HTTP boilerplate.
 */
public class HttpHelpers {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static void sendJson(HttpExchange exchange, int statusCode, Object response) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    
    public static void sendEmpty(HttpExchange exchange, int statusCode) throws IOException {
        exchange.sendResponseHeaders(statusCode, -1);
        exchange.close();
    }
}
