package io.github.mgeladzerezo.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.mgeladzerezo.ledger.consumer.SampleConsumer;
import io.github.mgeladzerezo.ledger.core.Caller;
import io.github.mgeladzerezo.ledger.core.LedgerService;
import io.github.mgeladzerezo.ledger.support.Api.Response;
import io.github.mgeladzerezo.ledger.support.Concurrent;
import io.github.mgeladzerezo.ledger.support.IntegrationTest;
import io.github.mgeladzerezo.ledger.support.SharedPostgres;
import io.github.mgeladzerezo.ledger.support.TestDatabase;
import io.github.mgeladzerezo.ledger.support.WebhookReceiver;
import io.github.mgeladzerezo.ledger.support.WebhookReceiver.Delivery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The outbox end to end: events are written atomically with the ledger change, delivered as
 * signed webhooks at least once, retried with backoff, dead-lettered, and never double-sent by
 * competing relays. The background relay is off in tests; each test drives {@code runOnce()}.
 */
class OutboxRelayTest extends IntegrationTest {

    private static final String SECRET = "test-webhook-secret-0123456789";

    @Autowired
    OutboxRelay relay;

    @Autowired
    LedgerService ledger;

    @Autowired
    PlatformTransactionManager transactionManager;

    WebhookReceiver receiver;

    @BeforeEach
    void start() {
        TestDatabase.reset();
        receiver = new WebhookReceiver();
    }

    @AfterEach
    void stop() {
        receiver.close();
    }

    @Test
    void eventCommitsWithThePostingAndRollsBackWithIt() {
        long alice = api.openCustomer("USD");
        long before = events("transaction.posted");

        String txId = api.deposit(alice, 1_000, "USD").expect(201).json().get("id").asString();
        assertThat(events("transaction.posted")).isEqualTo(before + 1);
        assertThat(jdbc.sql("select count(*) from outbox_events where transaction_id = ?::uuid").param(txId)
                .query(Long.class).single()).isEqualTo(1);

        // a rejected request writes neither a transaction nor an event
        api.post("/api/v1/withdrawals", Map.of("accountId", alice, "amount", 5_000, "currency", "USD")).expect(422);
        assertThat(events("transaction.posted")).isEqualTo(before + 1);

        // a posting whose surrounding transaction rolls back takes its event with it
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ledger.deposit(alice, 777, "USD", "doomed", Caller.system("test"));
            status.setRollbackOnly();
        });
        assertThat(events("transaction.posted")).isEqualTo(before + 1);
        assertThat(api.balance(alice)).isEqualTo(1_000);
    }

    @Test
    void relayDeliversASignedWebhookAndMarksItSent() {
        subscribe(receiver.url());
        long alice = api.openCustomer("USD");
        String txId = api.deposit(alice, 1_000, "USD").expect(201).json().get("id").asString();

        drain();

        Delivery posted = receiver.deliveries().stream()
                .filter(d -> d.eventType().equals("transaction.posted")).findFirst().orElseThrow();
        JsonNode event = posted.json();
        assertThat(event.get("id").asString()).isEqualTo(posted.eventId());
        assertThat(event.get("type").asString()).isEqualTo("transaction.posted");
        assertThat(event.get("sequence").asLong()).isPositive();
        assertThat(event.get("data").get("transaction").get("id").asString()).isEqualTo(txId);
        assertThat(event.get("data").get("balanceDeltas")).hasSize(2);
        assertThat(posted.attempt()).isEqualTo(1);
        assertThat(WebhookSignature.verify(SECRET, posted.signature(), posted.body(), Instant.now(),
                Duration.ofMinutes(1))).as("signature verifies with the shared secret").isTrue();
        assertThat(WebhookSignature.verify("wrong-secret", posted.signature(), posted.body(), Instant.now(),
                Duration.ofMinutes(1))).isFalse();
        assertThat(deliveries("SENT")).isEqualTo(receiver.deliveries().size());
        assertThat(deliveries("PENDING")).isZero();
        assertThat(relay.runOnce()).as("nothing left to send").isZero();
    }

    @Test
    void failedDeliveryIsRetriedWithBackoffUntilItSucceeds() throws InterruptedException {
        subscribe(receiver.url());
        receiver.respondWith(delivery -> delivery.attempt() < 3 ? 500 : 200);
        api.openCustomer("USD"); // one account.opened event

        assertThat(relay.runOnce()).isEqualTo(1);
        Map<String, Object> afterFirst = jdbc.sql("""
                select status, attempts, last_status, last_error, (next_attempt_at > now()) as postponed
                from outbox_deliveries""").query().singleRow();
        assertThat(afterFirst.get("status")).isEqualTo("PENDING");
        assertThat(afterFirst.get("attempts")).isEqualTo(1);
        assertThat(afterFirst.get("last_status")).isEqualTo(500);
        assertThat((String) afterFirst.get("last_error")).contains("HTTP 500");
        assertThat(afterFirst.get("postponed")).as("the retry is scheduled in the future").isEqualTo(true);
        assertThat(relay.runOnce()).as("backoff is respected: not due yet").isZero();

        awaitDrained();

        assertThat(receiver.deliveries()).extracting(Delivery::attempt).containsExactly(1, 2, 3);
        assertThat(receiver.countByEventId()).as("same event id on every attempt").hasSize(1);
        assertThat(jdbc.sql("select attempts from outbox_deliveries where status = 'SENT'").query(Integer.class)
                .single()).isEqualTo(3);
    }

    @Test
    void deliveryIsDeadLetteredAfterMaxAttemptsAndCanBeRequeued() throws InterruptedException {
        subscribe(receiver.url());
        receiver.respondWith(delivery -> 503);
        api.openCustomer("USD");

        long deadline = System.currentTimeMillis() + 10_000;
        while (deliveries("DEAD") == 0 && System.currentTimeMillis() < deadline) {
            relay.runOnce();
            Thread.sleep(25);
        }

        assertThat(deliveries("DEAD")).isEqualTo(1);
        assertThat(receiver.deliveries()).as("ledger.outbox.max-attempts=3 in tests").hasSize(3);
        assertThat(relay.runOnce()).as("dead deliveries are not retried on their own").isZero();
        JsonNode report = api.reconcile();
        assertThat(report.get("status").asString()).as("a dead letter is a warning, not broken books")
                .isEqualTo("WARNINGS");
        assertThat(report.get("findings").get(0).get("check").asString()).isEqualTo("DEAD_DELIVERIES");

        long deliveryId = jdbc.sql("select id from outbox_deliveries where status = 'DEAD'").query(Long.class).single();
        receiver.respondWith(delivery -> 204);
        api.post("/api/v1/outbox/deliveries/" + deliveryId + "/retry", "", null).expect(202);
        drain();

        assertThat(deliveries("SENT")).isEqualTo(1);
        assertThat(api.post("/api/v1/outbox/deliveries/" + deliveryId + "/retry", "", null).status()).isEqualTo(409);
    }

    @Test
    void unreachableSubscriberIsRecordedAsAFailedAttempt() {
        WebhookReceiver gone = new WebhookReceiver();
        String deadUrl = gone.url();
        gone.close();
        subscribe(deadUrl);
        api.openCustomer("USD");

        assertThat(relay.runOnce()).isEqualTo(1);

        Map<String, Object> row = jdbc.sql("select status, attempts, last_status, last_error from outbox_deliveries")
                .query().singleRow();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_status")).isNull();
        assertThat((String) row.get("last_error")).contains("Exception");
    }

    @Test
    void competingRelaysNeverSendTheSameDeliveryTwice() {
        subscribe(receiver.url());
        receiver.delayEachResponse(3); // keep batches in flight long enough to overlap
        long alice = api.openCustomer("USD");
        for (int i = 0; i < 80; i++) {
            api.deposit(alice, 10, "USD").expect(201);
        }
        long expected = deliveries("PENDING");
        assertThat(expected).isGreaterThanOrEqualTo(81);

        // six relay instances compete for the same queue with FOR UPDATE SKIP LOCKED
        List<Integer> sentPerRelay = Concurrent.run(6, thread -> {
            int sent = 0;
            int idleRounds = 0;
            while (idleRounds < 3) {
                int n = relay.runOnce();
                sent += n;
                idleRounds = n == 0 ? idleRounds + 1 : 0;
            }
            return sent;
        });

        assertThat(sentPerRelay.stream().mapToInt(Integer::intValue).sum()).isEqualTo(expected);
        assertThat(sentPerRelay.stream().filter(n -> n > 0).count()).as("the work was actually shared")
                .isGreaterThan(1);
        assertThat(receiver.deliveries()).hasSize((int) expected);
        assertThat(receiver.countByEventId().values()).as("every event delivered exactly once").containsOnly(1);
        assertThat(deliveries("SENT")).isEqualTo(expected);
    }

    @Test
    void sampleConsumerAppliesEachEventOnceHoweverOftenItIsDelivered() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(SharedPostgres.instance().getJdbcUrl());
        dataSource.setUser(SharedPostgres.instance().getUsername());
        dataSource.setPassword(SharedPostgres.instance().getPassword());
        try (SampleConsumer consumer = new SampleConsumer(dataSource, SECRET, 0, 0).start()) {
            TestDatabase.execute("truncate sample_consumer.processed_events, sample_consumer.account_projection");
            subscribe("http://localhost:" + consumer.port() + "/webhook");
            long alice = api.openCustomer("USD");
            long bob = api.openCustomer("USD");
            api.deposit(alice, 5_000, "USD").expect(201);
            api.transfer(alice, bob, 1_250, "USD").expect(201);
            drain();
            long events = events(null);
            assertThat(consumer.stats().uniqueEvents()).isEqualTo(events);
            assertThat(consumer.stats().duplicates()).isZero();

            // at-least-once in action: every event is delivered a second and a third time
            for (int round = 0; round < 2; round++) {
                jdbc.sql("update outbox_deliveries set status = 'PENDING', next_attempt_at = now()").update();
                drain();
            }

            SampleConsumer.Stats stats = consumer.stats();
            assertThat(stats.deliveriesAccepted()).isEqualTo(events * 3);
            assertThat(stats.uniqueEvents()).isEqualTo(events);
            assertThat(stats.duplicates()).isEqualTo(events * 2);
            // the effect was applied exactly once: the consumer's projection equals the ledger
            assertThat(consumer.projection().get(alice)).isEqualTo(3_750L).isEqualTo(api.balance(alice));
            assertThat(consumer.projection().get(bob)).isEqualTo(1_250L).isEqualTo(api.balance(bob));
        }
    }

    @Test
    void sampleConsumerRejectsDeliveriesSignedWithTheWrongSecret() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(SharedPostgres.instance().getJdbcUrl());
        dataSource.setUser(SharedPostgres.instance().getUsername());
        dataSource.setPassword(SharedPostgres.instance().getPassword());
        try (SampleConsumer consumer = new SampleConsumer(dataSource, "a-different-secret-entirely", 0, 0).start()) {
            TestDatabase.execute("truncate sample_consumer.processed_events, sample_consumer.account_projection");
            subscribe("http://localhost:" + consumer.port() + "/webhook");
            api.openCustomer("USD");

            relay.runOnce();

            assertThat(consumer.stats().rejectedSignatures()).isEqualTo(1);
            assertThat(consumer.stats().uniqueEvents()).isZero();
            assertThat(jdbc.sql("select last_status from outbox_deliveries").query(Integer.class).single()).isEqualTo(401);
        }
    }

    @Test
    void deactivatedSubscriptionReceivesNothingFurther() {
        long subscription = subscribe(receiver.url());
        api.openCustomer("USD");
        drain();
        int before = receiver.deliveries().size();

        api.delete("/api/v1/webhooks/subscriptions/" + subscription).expect(204);
        api.openCustomer("USD");
        drain();

        assertThat(receiver.deliveries()).hasSize(before);
        assertThat(api.get("/api/v1/webhooks/subscriptions").expect(200).json().get(0).get("active").asBoolean()).isFalse();
        assertThat(api.get("/api/v1/webhooks/subscriptions").body()).as("the secret is write-only").doesNotContain(SECRET);
    }

    @Test
    void outboxViewsExposeEventsDeliveriesAndStats() {
        subscribe(receiver.url());
        long alice = api.openCustomer("USD");
        api.deposit(alice, 100, "USD").expect(201);
        drain();

        JsonNode events = api.get("/api/v1/outbox/events?limit=1").expect(200).json();
        JsonNode deliveries = api.get("/api/v1/outbox/deliveries?status=SENT").expect(200).json();
        JsonNode stats = api.get("/api/v1/outbox/stats").expect(200).json();

        assertThat(events.get("items")).hasSize(1);
        assertThat(events.get("items").get(0).get("eventType").asString()).isEqualTo("transaction.posted");
        assertThat(events.get("items").get(0).get("sent").asInt()).isEqualTo(1);
        assertThat(events.get("nextCursor").isNull()).isFalse();
        assertThat(deliveries.get("items")).hasSize(2);
        assertThat(stats.get("sent").asLong()).isEqualTo(2);
        assertThat(stats.get("pending").asLong()).isZero();
        assertThat(api.get("/api/v1/outbox/deliveries?status=BOGUS").status()).isEqualTo(400);
        Response badUrl = api.post("/api/v1/webhooks/subscriptions", Map.of("url", "ftp://x", "secret", SECRET));
        assertThat(badUrl.status()).isEqualTo(400);
    }

    private long subscribe(String url) {
        return api.post("/api/v1/webhooks/subscriptions", Map.of("url", url, "secret", SECRET))
                .expect(201).json().get("id").asLong();
    }

    /** Runs relay cycles until nothing is due right now. */
    private void drain() {
        AtomicInteger guard = new AtomicInteger();
        while (relay.runOnce() > 0 && guard.incrementAndGet() < 1_000) {
            Thread.onSpinWait();
        }
    }

    /** Runs relay cycles until no delivery is pending, waiting out backoff delays. */
    private void awaitDrained() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (deliveries("PENDING") > 0) {
            assertThat(System.currentTimeMillis()).as("deliveries still pending after 10 s").isLessThan(deadline);
            relay.runOnce();
            Thread.sleep(20);
        }
    }

    private long deliveries(String status) {
        return jdbc.sql("select count(*) from outbox_deliveries where status = ?").param(status).query(Long.class).single();
    }

    private long events(String type) {
        return type == null
                ? jdbc.sql("select count(*) from outbox_events").query(Long.class).single()
                : jdbc.sql("select count(*) from outbox_events where event_type = ?").param(type).query(Long.class).single();
    }
}
