package ru.practicum.moviehub.http;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.moviehub.model.Movie;
import ru.practicum.moviehub.store.MoviesStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MoviesApiTest {
    private static final String BASE = "http://localhost:8080";
    private static MoviesStore store;
    private static MoviesServer server;
    private static HttpClient client;
    private final Gson gson = new Gson();

    @BeforeAll
    static void beforeAll() {
        store = new MoviesStore();
        server = new MoviesServer(store, 8080);
        server.start();
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @BeforeEach
    void beforeEach() {
        store.clear();
    }

    @AfterAll
    static void afterAll() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void getMovies_whenEmpty_returnsEmptyArray() throws Exception {
        HttpResponse<String> response = get("/movies");
        assertEquals(200, response.statusCode());
        assertJsonContentType(response);
        assertEquals("[]", response.body().trim());
    }

    @Test
    void postMovies_whenValid_returnsCreatedMovie() throws Exception {
        HttpResponse<String> response = post(movieJson("Начало", 2010));
        assertEquals(201, response.statusCode());
        assertJsonContentType(response);
        JsonObject movie = jsonObject(response);
        assertTrue(movie.get("id").getAsInt() > 0);
        assertEquals("Начало", movie.get("title").getAsString());
        assertEquals(2010, movie.get("year").getAsInt());
    }

    @Test
    void getMovies_whenAdded_returnsStoredMovies() throws Exception {
        JsonObject first = createdMovie("Начало", 2010);
        JsonObject second = createdMovie("Интерстеллар", 2014);
        assertNotEquals(first.get("id"), second.get("id"));

        HttpResponse<String> response = get("/movies");
        assertEquals(200, response.statusCode());
        assertJsonContentType(response);
        JsonArray movies = gson.fromJson(response.body(), JsonArray.class);
        assertEquals(2, movies.size());
        assertTrue(movies.contains(first));
        assertTrue(movies.contains(second));
    }

    @Test
    void postMovies_whenTitleEmpty_returnsValidationError() throws Exception {
        assertValidationError(post(movieJson("", 2000)));
        assertValidationError(post(movieJson("   \t\n", 2000)));
    }

    @Test
    void postMovies_whenTitleTooLong_returnsValidationError() throws Exception {
        assertValidationError(post(movieJson("а".repeat(101), 2000)));
    }

    @Test
    void postMovies_whenTitleAtLengthLimit_createsMovie() throws Exception {
        assertEquals(201, post(movieJson("а".repeat(100), 2000)).statusCode());
    }

    @Test
    void postMovies_whenYearOutsideRange_returnsValidationError() throws Exception {
        assertValidationError(post(movieJson("Фильм", 1887)));
        assertValidationError(post(movieJson("Фильм", LocalDate.now().getYear() + 2)));
    }

    @Test
    void postMovies_whenYearAtRangeBoundaries_createsMovie() throws Exception {
        assertEquals(201, post(movieJson("Первый фильм", 1888)).statusCode());
        assertEquals(201, post(movieJson("Будущий фильм", LocalDate.now().getYear() + 1)).statusCode());
    }

    @Test
    void postMovies_whenYearIsFractional_returnsValidationError() throws Exception {
        assertValidationError(post("{\"title\":\"Фильм\",\"year\":2000.5}"));
        assertEquals("[]", get("/movies").body());
    }

    @Test
    void postMovies_whenFieldsMissingOrNull_returnsValidationError() throws Exception {
        String[] bodies = {"{}", "{\"year\":2000}", "{\"title\":\"Фильм\"}",
                "{\"title\":null,\"year\":null}"};
        for (String body : bodies) {
            assertValidationError(post(body));
        }
    }

    @Test
    void postMovies_whenFieldsHaveWrongTypes_returnsValidationError() throws Exception {
        String[] bodies = {"{\"title\":42,\"year\":2000}", "{\"title\":[],\"year\":2000}",
                "{\"title\":true,\"year\":2000}", "{\"title\":{},\"year\":2000}",
                "{\"title\":\"Фильм\",\"year\":\"2000\"}",
                "{\"title\":\"Фильм\",\"year\":true}",
                "{\"title\":\"Фильм\",\"year\":[]}", "{\"title\":\"Фильм\",\"year\":{}}",
                "{\"title\":\"Фильм\",\"year\":999999999999999999999}"};
        for (String body : bodies) {
            assertValidationError(post(body));
        }
    }

    @Test
    void postMovies_whenBothFieldsInvalid_returnsBothDetails() throws Exception {
        HttpResponse<String> response = post(movieJson("", 1887));
        assertValidationError(response);
        JsonArray details = jsonObject(response).getAsJsonArray("details");
        assertEquals(2, details.size());
        assertTrue(details.get(0).getAsString().contains("название"));
        assertTrue(details.get(1).getAsString().contains("год"));
    }

    @Test
    void postMovies_whenContentTypeWrong_returns415() throws Exception {
        String[] contentTypes = {"text/plain", "application/xml", "application/jsonp"};
        for (String contentType : contentTypes) {
            assertError(send("POST", "/movies", movieJson("Фильм", 2000), contentType), 415);
        }
    }

    @Test
    void postMovies_whenContentTypeMissing_returns415() throws Exception {
        assertError(send("POST", "/movies", movieJson("Фильм", 2000), null), 415);
    }

    @Test
    void postMovies_whenJsonContentTypeHasCharset_createsMovie() throws Exception {
        for (String contentType : new String[]{"Application/JSON; charset=UTF-8",
                "application/json; charset=\"utf-8\""}) {
            HttpResponse<String> response = send("POST", "/movies", movieJson("Фильм", 2000), contentType);
            assertEquals(201, response.statusCode());
            assertJsonContentType(response);
        }
    }

    @Test
    void postMovies_whenCharsetUnsupported_returns415() throws Exception {
        for (String charset : new String[]{"ISO-8859-1", "UTF-16"}) {
            assertError(send("POST", "/movies", movieJson("Фильм", 2000),
                    "application/json; charset=" + charset), 415);
        }
        assertEquals("[]", get("/movies").body());
    }

    @Test
    void postMovies_whenJsonMalformed_returns400() throws Exception {
        String[] bodies = {"", "  \r\n\t", "{", "{\"title\":}",
                "{\"title\":\"Фильм\",\"year\":2000,}", "{} {}"};
        for (String body : bodies) {
            assertError(post(body), 400);
        }
    }

    @Test
    void postMovies_whenJsonIsNotObject_returnsValidationError() throws Exception {
        for (String body : new String[]{"null", "[]", "42", "\"Фильм\""}) {
            assertValidationError(post(body));
        }
    }

    @Test
    void postMovies_whenRejected_doesNotChangeStore() throws Exception {
        createdMovie("Существующий фильм", 2000);
        assertValidationError(post(movieJson("", 2000)));
        assertError(post("{"), 400);
        assertError(send("POST", "/movies", movieJson("Фильм", 2000), "text/plain"), 415);
        JsonArray movies = gson.fromJson(get("/movies").body(), JsonArray.class);
        assertEquals(1, movies.size());
        assertEquals("Существующий фильм", movies.get(0).getAsJsonObject().get("title").getAsString());
    }

    @Test
    void postMovies_whenClientSuppliesId_assignsServerId() throws Exception {
        JsonObject first = createdMovie("Первый", 2000);
        String body = "{\"id\":" + first.get("id") + ",\"title\":\"Второй\",\"year\":2001}";
        HttpResponse<String> response = post(body);
        assertEquals(201, response.statusCode());
        assertNotEquals(first.get("id"), jsonObject(response).get("id"));
        assertEquals(2, gson.fromJson(get("/movies").body(), JsonArray.class).size());
    }

    @Test
    void getMovie_whenExists_returnsMovie() throws Exception {
        JsonObject created = createdMovie("Солярис", 1972);
        HttpResponse<String> response = get("/movies/" + created.get("id").getAsInt());
        assertEquals(200, response.statusCode());
        assertJsonContentType(response);
        Movie movie = gson.fromJson(response.body(), Movie.class);
        assertEquals(created.get("id").getAsInt(), movie.getId());
        assertEquals("Солярис", movie.getTitle());
        assertEquals(1972, movie.getYear());
    }

    @Test
    void getMovie_whenMissing_returns404() throws Exception {
        HttpResponse<String> response = get("/movies/999");
        assertError(response, 404);
        assertEquals("Фильм не найден", jsonObject(response).get("error").getAsString());
    }

    @Test
    void getMovie_whenIdInvalid_returns400() throws Exception {
        for (String id : new String[]{"abc", "1.5", "999999999999999999999"}) {
            HttpResponse<String> response = get("/movies/" + id);
            assertError(response, 400);
            assertEquals("Некорректный ID", jsonObject(response).get("error").getAsString());
        }
    }

    @Test
    void deleteMovie_whenExists_removesOnlyThatMovie() throws Exception {
        JsonObject first = createdMovie("Первый", 2000);
        JsonObject second = createdMovie("Второй", 2001);
        String path = "/movies/" + first.get("id").getAsInt();
        HttpResponse<String> response = send("DELETE", path, null, null);
        assertEquals(204, response.statusCode());
        assertJsonContentType(response);
        assertEquals("", response.body());
        assertError(get(path), 404);

        JsonArray movies = gson.fromJson(get("/movies").body(), JsonArray.class);
        assertEquals(1, movies.size());
        assertEquals(second, movies.get(0));
        assertError(send("DELETE", path, null, null), 404);
    }

    @Test
    void deleteMovie_whenMissing_returns404() throws Exception {
        assertError(send("DELETE", "/movies/999", null, null), 404);
    }

    @Test
    void deleteMovie_whenIdInvalid_returns400() throws Exception {
        for (String id : new String[]{"abc", "1.5", "999999999999999999999"}) {
            assertError(send("DELETE", "/movies/" + id, null, null), 400);
        }
    }

    @Test
    void postMovies_afterDelete_doesNotReuseExistingId() throws Exception {
        JsonObject first = createdMovie("Первый", 2000);
        JsonObject second = createdMovie("Второй", 2001);
        assertEquals(204, send("DELETE", "/movies/" + first.get("id").getAsInt(), null, null).statusCode());
        JsonObject third = createdMovie("Третий", 2002);
        assertNotEquals(first.get("id"), third.get("id"));
        assertNotEquals(second.get("id"), third.get("id"));
        assertEquals(second, jsonObject(get("/movies/" + second.get("id").getAsInt())));
    }

    @Test
    void getMovies_whenYearSpecified_returnsOnlyMatchingMovies() throws Exception {
        JsonObject first = createdMovie("Первый", 2000);
        JsonObject second = createdMovie("Второй", 2000);
        createdMovie("Другой год", 2001);
        HttpResponse<String> response = get("/movies?year=2000");
        assertEquals(200, response.statusCode());
        assertJsonContentType(response);
        JsonArray movies = gson.fromJson(response.body(), JsonArray.class);
        assertEquals(2, movies.size());
        assertTrue(movies.contains(first));
        assertTrue(movies.contains(second));
        assertEquals(3, gson.fromJson(get("/movies").body(), JsonArray.class).size());
    }

    @Test
    void getMovies_whenYearHasNoMatches_returnsEmptyArray() throws Exception {
        createdMovie("Фильм", 2000);
        HttpResponse<String> response = get("/movies?year=2001");
        assertEquals(200, response.statusCode());
        assertJsonContentType(response);
        List<Movie> movies = gson.fromJson(response.body(), new ListOfMoviesTypeToken().getType());
        assertTrue(movies.isEmpty());
        assertEquals("[]", response.body());
    }

    @Test
    void getMovies_whenYearAtRangeBoundaries_returnsMovies() throws Exception {
        createdMovie("Первый фильм", 1888);
        int nextYear = LocalDate.now().getYear() + 1;
        createdMovie("Будущий фильм", nextYear);
        assertEquals(1, gson.fromJson(get("/movies?year=1888").body(), JsonArray.class).size());
        assertEquals(1, gson.fromJson(get("/movies?year=" + nextYear).body(), JsonArray.class).size());
    }

    @Test
    void getMovies_whenYearInvalid_returns400() throws Exception {
        String[] years = {"abc", "", "2000.5", "1887", String.valueOf(LocalDate.now().getYear() + 2),
                "999999999999999999999"};
        for (String year : years) {
            HttpResponse<String> response = get("/movies?year=" + year);
            assertError(response, 400);
            assertTrue(jsonObject(response).get("error").getAsString().contains("year"));
        }
    }

    @Test
    void getMovies_whenQueryMalformed_returns400() throws Exception {
        for (String query : new String[]{"year", "year=2000&year=2001", "other=2000", "year=2000&other=1"}) {
            assertError(get("/movies?" + query), 400);
        }
    }

    @Test
    void movies_whenMethodUnsupported_returns405() throws Exception {
        for (String method : new String[]{"PUT", "PATCH", "DELETE", "OPTIONS"}) {
            HttpResponse<String> response = send(method, "/movies", null, null);
            assertError(response, 405);
            assertEquals("GET, POST", response.headers().firstValue("Allow").orElse(""));
        }
        for (String method : new String[]{"POST", "PUT", "PATCH", "OPTIONS"}) {
            HttpResponse<String> response = send(method, "/movies/1", null, null);
            assertError(response, 405);
            assertEquals("GET, DELETE", response.headers().firstValue("Allow").orElse(""));
        }
    }

    @Test
    void movies_whenHeadRequested_returns405WithoutBody() throws Exception {
        HttpResponse<String> response = send("HEAD", "/movies", null, null);
        assertEquals(405, response.statusCode());
        assertJsonContentType(response);
        assertEquals("", response.body());
    }

    @Test
    void movies_whenPathUnknown_returnsJson404() throws Exception {
        for (String path : new String[]{"/moviesExtra", "/movies/1/extra", "/other", "/movies/"}) {
            assertError(get(path), 404);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null);
    }

    private HttpResponse<String> post(String body) throws Exception {
        return send("POST", "/movies", body, "application/json");
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(2));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertJsonContentType(response);
        return response;
    }

    private String movieJson(String title, int year) {
        JsonObject movie = new JsonObject();
        movie.addProperty("title", title);
        movie.addProperty("year", year);
        return gson.toJson(movie);
    }

    private JsonObject createdMovie(String title, int year) throws Exception {
        HttpResponse<String> response = post(movieJson(title, year));
        assertEquals(201, response.statusCode());
        assertJsonContentType(response);
        return jsonObject(response);
    }

    private JsonObject jsonObject(HttpResponse<String> response) {
        return gson.fromJson(response.body(), JsonObject.class);
    }

    private void assertError(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertJsonContentType(response);
        JsonObject error = jsonObject(response);
        assertTrue(error.has("error"));
        assertTrue(error.get("error").getAsJsonPrimitive().isString());
        assertTrue(!error.get("error").getAsString().isBlank());
    }

    private void assertValidationError(HttpResponse<String> response) {
        assertError(response, 422);
        JsonArray details = jsonObject(response).getAsJsonArray("details");
        assertTrue(details.size() > 0);
        for (int i = 0; i < details.size(); i++) {
            assertTrue(details.get(i).getAsJsonPrimitive().isString());
            assertTrue(!details.get(i).getAsString().isBlank());
        }
    }

    private void assertJsonContentType(HttpResponse<String> response) {
        assertEquals("application/json; charset=UTF-8",
                response.headers().firstValue("Content-Type").orElse(""));
    }
}
