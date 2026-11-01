package ru.practicum.moviehub.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public abstract class BaseHttpHandler implements HttpHandler {
    protected static final String CT_JSON = "application/json; charset=UTF-8";

    protected void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CT_JSON);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    protected void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", CT_JSON);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }
}
