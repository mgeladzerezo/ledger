package io.github.mgeladzerezo.ledger.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.ToIntFunction;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;

/**
 * A scriptable webhook subscriber for tests: records every delivery it receives and answers with
 * whatever status the test tells it to.
 */
public final class WebhookReceiver implements AutoCloseable {

    private final HttpServer server;
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> countByEventId = new ConcurrentHashMap<>();
    private volatile ToIntFunction<Delivery> behaviour = delivery -> 200;
    private volatile long delayMillis;

    public WebhookReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Delivery delivery = new Delivery(
                    exchange.getRequestHeaders().getFirst("X-Ledger-Event-Id"),
                    exchange.getRequestHeaders().getFirst("X-Ledger-Event-Type"),
                    exchange.getRequestHeaders().getFirst("X-Ledger-Signature"),
                    Integer.parseInt(exchange.getRequestHeaders().getFirst("X-Ledger-Delivery-Attempt")),
                    body);
            deliveries.add(delivery);
            countByEventId.merge(delivery.eventId(), 1, Integer::sum);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(behaviour.applyAsInt(delivery), -1);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    /** Decides the HTTP status for each delivery from now on. */
    public void respondWith(ToIntFunction<Delivery> behaviour) {
        this.behaviour = behaviour;
    }

    public void delayEachResponse(long millis) {
        this.delayMillis = millis;
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    /** Number of times each event id was received. */
    public Map<String, Integer> countByEventId() {
        return Map.copyOf(countByEventId);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public record Delivery(String eventId, String eventType, String signature, int attempt, String body) {
        public JsonNode json() {
            return Api.JSON.readTree(body);
        }
    }
}
