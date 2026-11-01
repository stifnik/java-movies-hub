package ru.practicum.moviehub.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import ru.practicum.moviehub.api.ErrorResponse;
import ru.practicum.moviehub.model.Movie;
import ru.practicum.moviehub.store.MoviesStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class MoviesHandler extends BaseHttpHandler {
    private static final int MIN_YEAR = 1888;
    private static final String INVALID_YEAR_QUERY = "Некорректный параметр запроса — 'year'";
    private final MoviesStore store;
    private final Gson gson = new Gson();

    public MoviesHandler(MoviesStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/movies")) {
                if (method.equals("GET")) {
                    getMovies(exchange);
                } else if (method.equals("POST")) {
                    addMovie(exchange);
                } else {
                    sendMethodNotAllowed(exchange, "GET, POST");
                }
            } else if (path.startsWith("/movies/") && path.length() > "/movies/".length()
                    && !path.substring("/movies/".length()).contains("/")) {
                handleMovie(exchange, path.substring("/movies/".length()));
            } else {
                sendError(exchange, 404, "Эндпоинт не найден");
            }
        } finally {
            exchange.close();
        }
    }

    private void getMovies(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            sendJson(exchange, 200, gson.toJson(store.getAll()));
            return;
        }
        String[] parts = query.split("=", -1);
        if (parts.length != 2 || !parts[0].equals("year")) {
            sendError(exchange, 400, INVALID_YEAR_QUERY);
            return;
        }
        int year;
        try {
            year = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            sendError(exchange, 400, INVALID_YEAR_QUERY);
            return;
        }
        if (year < MIN_YEAR || year > LocalDate.now().getYear() + 1) {
            sendError(exchange, 400, INVALID_YEAR_QUERY);
            return;
        }
        sendJson(exchange, 200, gson.toJson(store.getByYear(year)));
    }

    private void handleMovie(HttpExchange exchange, String idText) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("DELETE")) {
            sendMethodNotAllowed(exchange, "GET, DELETE");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(idText);
        } catch (NumberFormatException e) {
            sendError(exchange, 400, "Некорректный ID");
            return;
        }
        if (method.equals("GET")) {
            Movie movie = store.getById(id);
            if (movie == null) {
                sendError(exchange, 404, "Фильм не найден");
            } else {
                sendJson(exchange, 200, gson.toJson(movie));
            }
        } else if (store.delete(id)) {
            sendNoContent(exchange);
        } else {
            sendError(exchange, 404, "Фильм не найден");
        }
    }

    private void addMovie(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (!isSupportedContentType(contentType)) {
            sendError(exchange, 415, "Ожидается Content-Type: application/json с кодировкой UTF-8");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) {
            sendError(exchange, 400, "Некорректный JSON");
            return;
        }
        JsonElement json;
        try {
            json = JsonParser.parseString(body);
        } catch (JsonParseException e) {
            sendError(exchange, 400, "Некорректный JSON");
            return;
        }
        if (!json.isJsonObject()) {
            sendValidationError(exchange, List.of("тело запроса должно быть JSON-объектом"));
            return;
        }

        JsonObject object = json.getAsJsonObject();
        List<String> details = validateMovie(object);
        if (!details.isEmpty()) {
            sendValidationError(exchange, details);
            return;
        }

        Movie movie = store.add(object.get("title").getAsString(), object.get("year").getAsInt());
        sendJson(exchange, 201, gson.toJson(movie));
    }

    private List<String> validateMovie(JsonObject object) {
        JsonElement title = object.get("title");
        JsonElement year = object.get("year");
        List<String> details = new ArrayList<>();
        if (title == null || !title.isJsonPrimitive() || !title.getAsJsonPrimitive().isString()) {
            details.add("название должно быть строкой");
        } else if (title.getAsString().isBlank()) {
            details.add("название не должно быть пустым");
        } else if (title.getAsString().length() > 100) {
            details.add("название должно содержать не более 100 символов");
        }
        if (!isValidYear(year)) {
            details.add("год должен быть целым числом между " + MIN_YEAR
                    + " и " + (LocalDate.now().getYear() + 1));
        }
        return details;
    }

    private boolean isValidYear(JsonElement year) {
        if (year == null || !year.isJsonPrimitive() || !year.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            int value = Integer.parseInt(year.getAsString());
            return value >= MIN_YEAR && value <= LocalDate.now().getYear() + 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isSupportedContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String[] parts = contentType.split(";", -1);
        if (!parts[0].trim().equalsIgnoreCase("application/json")) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            String[] parameter = parts[i].trim().split("=", 2);
            if (parameter[0].trim().equalsIgnoreCase("charset")) {
                if (parameter.length != 2) {
                    return false;
                }
                String charset = parameter[1].trim();
                if (!charset.equalsIgnoreCase("UTF-8") && !charset.equalsIgnoreCase("\"UTF-8\"")) {
                    return false;
                }
            }
        }
        return true;
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, gson.toJson(new ErrorResponse(message)));
    }

    private void sendValidationError(HttpExchange exchange, List<String> details) throws IOException {
        sendJson(exchange, 422, gson.toJson(new ErrorResponse("Ошибка валидации", details)));
    }

    private void sendMethodNotAllowed(HttpExchange exchange, String allowedMethods) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowedMethods);
        sendError(exchange, 405, "Метод не поддерживается");
    }
}
