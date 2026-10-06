package io.github.mgeladzerezo.ledger.consumer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.mgeladzerezo.ledger.outbox.WebhookSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A reference webhook consumer, deliberately free of Spring: a JDK {@link HttpServer}, plain JDBC
 * and one table of processed event ids. It shows the receiving half of at-least-once delivery.
 *
 * <p>For every delivery it
 * <ol>
 *   <li>verifies the HMAC signature and its timestamp;</li>
 *   <li>inserts the event id into {@code processed_events} and applies the event's effect (a
 *       running balance per account) <em>in the same local transaction</em>;</li>
 *   <li>if the id is already there, counts a duplicate, applies nothing, and still answers 200 so
 *       the sender stops retrying.</li>
 * </ol>
 * Step 2 is what turns at-least-once delivery into an exactly-once effect: the "seen it" marker
 * and the effect commit together, so a crash between them cannot happen.
 *
 * <p>The projection it builds mirrors the ledger's balances, which gives the tests a strong
 * assertion: after the outbox has drained, projection and ledger must agree to the cent.
 */
public final class SampleConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SampleConsumer.class);
    private static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);

    private final DataSource dataSource;
    private final String secret;
    private final double failureRate;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpServer server;
    private final AtomicLong rejectedSignatures = new AtomicLong();
    private final AtomicLong simulatedFailures = new AtomicLong();

    /**
     * @param port        port to listen on; 0 picks a free one
     * @param failureRate probability in [0, 1] of answering 503 before processing, to exercise the
     *                    sender's retries in the demo
     */
    public SampleConsumer(DataSource dataSource, String secret, int port, double failureRate) throws IOException {
        this.dataSource = dataSource;
        this.secret = secret;
        this.failureRate = failureRate;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/webhook", this::webhook);
        this.server.createContext("/stats", this::statsEndpoint);
        this.server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
    }

    public SampleConsumer start() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create schema if not exists sample_consumer");
            statement.execute("""
                    create table if not exists sample_consumer.processed_events (
                        event_id   uuid        primary key,
                        event_type text        not null,
                        sequence   bigint      not null,
                        deliveries int         not null default 1,
                        first_seen timestamptz not null default now()
                    )""");
            statement.execute("""
                    create table if not exists sample_consumer.account_projection (
                        account_id   bigint primary key,
                        currency     text   not null,
                        balance      bigint not null,
                        transactions bigint not null
                    )""");
        }
        server.start();
        log.info("sample consumer listening on port {}", port());
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void webhook(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            respond(exchange, 405, "{\"error\":\"POST only\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String signature = exchange.getRequestHeaders().getFirst(WebhookSignature.HEADER);
        if (!WebhookSignature.verify(secret, signature, body, Instant.now(), SIGNATURE_TOLERANCE)) {
            rejectedSignatures.incrementAndGet();
            respond(exchange, 401, "{\"error\":\"invalid signature\"}");
            return;
        }
        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            simulatedFailures.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"simulated outage\"}");
            return;
        }
        try {
            boolean applied = process(json.readTree(body));
            respond(exchange, 200, "{\"applied\":" + applied + "}");
        } catch (SQLException | RuntimeException e) {
            log.warn("sample consumer failed to process a delivery: {}", e.toString());
            respond(exchange, 500, "{\"error\":\"processing failed\"}");
        }
    }

    /**
     * @return {@code true} if the event was new and its effect was applied; {@code false} for a duplicate
     */
    boolean process(JsonNode event) throws SQLException {
        UUID eventId = UUID.fromString(event.get("id").asString());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean firstTime = markProcessed(connection, eventId, event);
                if (firstTime && event.get("type").asString().equals("transaction.posted")) {
                    applyBalanceDeltas(connection, event.get("data").get("balanceDeltas"));
                }
                connection.commit();
                return firstTime;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static boolean markProcessed(Connection connection, UUID eventId, JsonNode event) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into sample_consumer.processed_events (event_id, event_type, sequence)
                values (?, ?, ?)
                on conflict (event_id) do nothing""")) {
            insert.setObject(1, eventId);
            insert.setString(2, event.get("type").asString());
            insert.setLong(3, event.get("sequence").asLong());
            if (insert.executeUpdate() == 1) {
                return true;
            }
        }
        try (PreparedStatement duplicate = connection.prepareStatement(
                "update sample_consumer.processed_events set deliveries = deliveries + 1 where event_id = ?")) {
            duplicate.setObject(1, eventId);
            duplicate.executeUpdate();
        }
        return false;
    }

    private static void applyBalanceDeltas(Connection connection, JsonNode deltas) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement("""
                insert into sample_consumer.account_projection as p (account_id, currency, balance, transactions)
                values (?, ?, ?, 1)
                on conflict (account_id) do update
                    set balance = p.balance + excluded.balance, transactions = p.transactions + 1""")) {
            // ascending account id, the same lock order the ledger uses, so concurrent events cannot deadlock
            Map<Long, JsonNode> ordered = new TreeMap<>();
            deltas.forEach(delta -> ordered.put(delta.get("accountId").asLong(), delta));
            for (JsonNode delta : ordered.values()) {
                upsert.setLong(1, delta.get("accountId").asLong());
                upsert.setString(2, delta.get("currency").asString());
                upsert.setLong(3, delta.get("delta").asLong());
                upsert.executeUpdate();
            }
        }
    }

    public Stats stats() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select count(*), coalesce(sum(deliveries), 0) from sample_consumer.processed_events")) {
            rs.next();
            long unique = rs.getLong(1);
            long deliveries = rs.getLong(2);
            return new Stats(deliveries, unique, deliveries - unique, rejectedSignatures.get(), simulatedFailures.get());
        }
    }

    /** How many times the given event was delivered and accepted (0 if never). */
    public int deliveriesOf(UUID eventId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "select deliveries from sample_consumer.processed_events where event_id = ?")) {
            query.setObject(1, eventId);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** The consumer's view of every account balance it has heard about, keyed by account id. */
    public Map<Long, Long> projection() throws SQLException {
        Map<Long, Long> balances = new TreeMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select account_id, balance from sample_consumer.account_projection")) {
            while (rs.next()) {
                balances.put(rs.getLong(1), rs.getLong(2));
            }
        }
        return balances;
    }

    private void statsEndpoint(HttpExchange exchange) throws IOException {
        try {
            respond(exchange, 200, json.writeValueAsString(stats()));
        } catch (SQLException e) {
            respond(exchange, 500, "{\"error\":\"stats unavailable\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * @param deliveriesAccepted  signed deliveries received and answered 200, duplicates included
     * @param uniqueEvents        distinct event ids, each applied exactly once
     * @param duplicates          deliveries discarded because the event id had been processed already
     * @param rejectedSignatures  deliveries refused for a bad or stale signature
     * @param simulatedFailures   deliveries answered 503 on purpose (demo only)
     */
    public record Stats(long deliveriesAccepted, long uniqueEvents, long duplicates, long rejectedSignatures,
                        long simulatedFailures) {
    }
}
