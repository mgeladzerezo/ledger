package io.github.mgeladzerezo.ledger.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A small HTTP client for the ledger API built on the JDK client, used both by the in-process
 * tests and by the tests that run the application as a separate process. Going through real HTTP
 * keeps the tests honest about status codes, headers and JSON shapes.
 */
public final class Api {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final String apiKey;

    public Api(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Response get(String path) {
        return send(request(path).GET().build());
    }

    /** POST with a fresh idempotency key. */
    public Response post(String path, Object body) {
        return post(path, body, UUID.randomUUID().toString());
    }

    /** @param idempotencyKey sent as {@code Idempotency-Key}; {@code null} omits the header */
    public Response post(String path, Object body, String idempotencyKey) {
        HttpRequest.Builder builder = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body)));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return send(builder.build());
    }

    public Response delete(String path) {
        return send(request(path).DELETE().build());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        return builder;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new ConnectionFailed(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- convenience wrappers for the calls nearly every test makes ----

    /** Opens a customer account (LIABILITY, may not go negative) with a unique code and returns its id. */
    public long openCustomer(String currency) {
        return openAccount("LIABILITY", currency, true);
    }

    public long openAccount(String type, String currency, boolean nonNegative) {
        String code = "t-" + UUID.randomUUID().toString().substring(0, 13);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Test " + code);
        body.put("type", type);
        body.put("currency", currency);
        body.put("nonNegative", nonNegative);
        return post("/api/v1/accounts", body).expect(201).json().get("id").asLong();
    }

    public Response deposit(long accountId, long amount, String currency) {
        return post("/api/v1/deposits", Map.of("accountId", accountId, "amount", amount, "currency", currency));
    }

    public Response transfer(long from, long to, long amount, String currency, String idempotencyKey) {
        return post("/api/v1/transfers",
                Map.of("fromAccountId", from, "toAccountId", to, "amount", amount, "currency", currency),
                idempotencyKey);
    }

    public Response transfer(long from, long to, long amount, String currency) {
        return transfer(from, to, amount, currency, UUID.randomUUID().toString());
    }

    public JsonNode account(long id) {
        return get("/api/v1/accounts/" + id).expect(200).json();
    }

    public long balance(long accountId) {
        return account(accountId).get("balance").asLong();
    }

    public long available(long accountId) {
        return account(accountId).get("available").asLong();
    }

    /** Runs the reconciliation job on demand and returns the report. */
    public JsonNode reconcile() {
        return post("/api/v1/reconciliation/runs", "", null).expect(201).json();
    }

    public record Response(int status, String body, HttpHeaders headers) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public Response expect(int expectedStatus) {
            if (status != expectedStatus) {
                throw new AssertionError("expected HTTP " + expectedStatus + " but got " + status + ": " + body);
            }
            return this;
        }

        /** The problem "code" of an error response. */
        public String problemCode() {
            return json().get("code").asString();
        }
    }

    /** The connection failed or was cut: what a client sees when the server dies mid-request. */
    public static final class ConnectionFailed extends RuntimeException {
        ConnectionFailed(IOException cause) {
            super(cause);
        }
    }
}
