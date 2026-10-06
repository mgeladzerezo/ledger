package io.github.mgeladzerezo.ledger.crash;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.failpoint.Failpoint;
import io.github.mgeladzerezo.ledger.failpoint.Failpoints;
import io.github.mgeladzerezo.ledger.support.Api;
import io.github.mgeladzerezo.ledger.support.Api.Response;
import io.github.mgeladzerezo.ledger.support.TestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The headline claim: kill the application at any point of a transfer and the money still
 * balances.
 *
 * <p>For every {@link Failpoint} the test arms it in a real application process, sends a
 * transfer, watches the process die with the failpoint's exit status, starts a new process,
 * retries the same request with the same idempotency key, and then checks, against the database
 * and through the API:
 * <ul>
 *   <li>the crash left no half-written transfer behind (pre-commit failpoints leave nothing at all);</li>
 *   <li>after the retry the transfer exists exactly once and money is conserved;</li>
 *   <li>the outbox event reached the consumer at least once and was applied exactly once;</li>
 *   <li>the reconciliation job finds nothing.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailpointCrashIT {

    /** Failpoints reached before COMMIT: the crash must leave no trace of the transfer. */
    private static final Set<Failpoint> BEFORE_COMMIT = EnumSet.of(Failpoint.AFTER_IDEMPOTENCY_KEY,
            Failpoint.AFTER_DEBIT_ENTRY, Failpoint.AFTER_ENTRIES, Failpoint.AFTER_BALANCE_UPDATE,
            Failpoint.AFTER_OUTBOX_INSERT, Failpoint.BEFORE_COMMIT);

    /** Relay failpoints that fire after the subscriber already received the event: a duplicate is certain. */
    private static final Set<Failpoint> REDELIVERS = EnumSet.of(Failpoint.RELAY_AFTER_SEND_BEFORE_MARK,
            Failpoint.RELAY_AFTER_MARK_BEFORE_COMMIT);

    private static final long OPENING = 10_000;
    private static final long AMOUNT = 2_500;

    private CrashHarness harness;

    @BeforeAll
    void start() throws Exception {
        harness = new CrashHarness();
    }

    @AfterAll
    void stop() {
        harness.close();
    }

    @ParameterizedTest(name = "crash at {0}")
    @EnumSource(Failpoint.class)
    void transferHappensExactlyOnceDespiteACrashAt(Failpoint failpoint) throws Exception {
        Api api = harness.api();
        long alice = api.openCustomer("USD");
        long bob = api.openCustomer("USD");
        api.deposit(alice, OPENING, "USD").expect(201);
        harness.awaitOutboxDrained(Duration.ofSeconds(30)); // so a relay failpoint can only fire on our transfer
        String key = "crash-" + failpoint.id() + "-" + UUID.randomUUID();

        // --- arm, fire, die -----------------------------------------------------------------
        api.post("/api/v1/chaos/failpoints/" + failpoint.id() + "/arm", "", null).expect(200);
        Response beforeCrash = attempt(api, alice, bob, key);
        int exit = harness.process().awaitExit(Duration.ofSeconds(30));
        assertThat(exit).as("the process was killed by the failpoint").isEqualTo(Failpoints.EXIT_CODE);

        // --- what the crash left behind, read straight from the database ---------------------
        if (BEFORE_COMMIT.contains(failpoint)) {
            assertThat(beforeCrash).as("the client got no response").isNull();
            assertThat(transactions(key)).as("no transaction survived the crash").isZero();
            assertThat(TestDatabase.queryLong("select count(*) from idempotency_keys where key = '" + key + "'"))
                    .as("the key is unknown: it shared the rolled-back transaction").isZero();
            assertThat(balance(alice)).isEqualTo(OPENING);
            assertThat(balance(bob)).isZero();
        } else {
            assertThat(transactions(key)).as("the transfer had committed before the crash").isEqualTo(1);
            assertThat(TestDatabase.queryLong("select count(*) from idempotency_keys where key = '" + key
                    + "' and status_code = 201")).as("and so had its key and stored response").isEqualTo(1);
            if (failpoint == Failpoint.AFTER_COMMIT_BEFORE_RESPONSE) {
                assertThat(beforeCrash).as("the client never learned that it succeeded").isNull();
            }
        }

        // --- restart and retry the same request with the same key -----------------------------
        harness.restart();
        api = harness.api();
        Response retry = api.transfer(alice, bob, AMOUNT, "USD", key).expect(201);

        assertThat(retry.header("Idempotent-Replayed")).as("executed fresh or replayed, as the crash point dictates")
                .isEqualTo(Boolean.toString(!BEFORE_COMMIT.contains(failpoint)));
        if (beforeCrash != null) {
            assertThat(retry.body()).as("the replay is the response the client already saw").isEqualTo(beforeCrash.body());
        }
        assertThat(api.transfer(alice, bob, AMOUNT, "USD", key).expect(201).body()).isEqualTo(retry.body());

        // --- exactly once, money conserved ---------------------------------------------------
        assertThat(transactions(key)).as("exactly one transfer for the key").isEqualTo(1);
        assertThat(api.balance(alice)).isEqualTo(OPENING - AMOUNT);
        assertThat(api.balance(bob)).isEqualTo(AMOUNT);
        assertThat(api.balance(alice) + api.balance(bob)).as("money is conserved").isEqualTo(OPENING);

        // --- outbox: delivered at least once, consumed once -----------------------------------
        harness.awaitOutboxDrained(Duration.ofSeconds(30));
        String transactionId = retry.json().get("id").asString();
        assertThat(TestDatabase.queryLong("select count(*) from outbox_events where transaction_id = '"
                + transactionId + "'")).as("one event for the one transfer").isEqualTo(1);
        UUID eventId = UUID.fromString(queryString(
                "select id::text from outbox_events where transaction_id = '" + transactionId + "'"));
        int deliveries = harness.consumer().deliveriesOf(eventId);
        if (REDELIVERS.contains(failpoint)) {
            assertThat(deliveries).as("the crash caused a duplicate delivery").isGreaterThanOrEqualTo(2);
        } else {
            assertThat(deliveries).as("delivered at least once").isGreaterThanOrEqualTo(1);
        }
        assertThat(harness.consumerBalance(alice)).as("the consumer applied the transfer exactly once")
                .isEqualTo(OPENING - AMOUNT);
        assertThat(harness.consumerBalance(bob)).isEqualTo(AMOUNT);

        // --- and the books are provably sound -------------------------------------------------
        assertThat(api.reconcile().get("status").asString()).isEqualTo("CLEAN");
    }

    /**
     * The same guarantee with the failpoint armed purely from configuration at start-up, the way
     * an operator would reproduce a production incident.
     */
    @Test
    void failpointArmedFromConfigurationAtStartupKillsTheFirstTransfer() {
        Api api = harness.api();
        long alice = api.openCustomer("USD");
        long bob = api.openCustomer("USD");
        api.deposit(alice, OPENING, "USD").expect(201);
        String key = "crash-config-" + UUID.randomUUID();

        harness.restart("--ledger.failpoints.armed=before-commit");
        Response beforeCrash = attempt(harness.api(), alice, bob, key);
        assertThat(beforeCrash).isNull();
        assertThat(harness.process().awaitExit(Duration.ofSeconds(30))).isEqualTo(Failpoints.EXIT_CODE);
        assertThat(transactions(key)).isZero();

        harness.restart();
        Response retry = harness.api().transfer(alice, bob, AMOUNT, "USD", key).expect(201);

        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("false");
        assertThat(transactions(key)).isEqualTo(1);
        assertThat(harness.api().balance(alice) + harness.api().balance(bob)).isEqualTo(OPENING);
        assertThat(harness.api().reconcile().get("status").asString()).isEqualTo("CLEAN");
    }

    /** @return the response, or {@code null} if the connection died before one arrived */
    private static Response attempt(Api api, long from, long to, String key) {
        try {
            return api.transfer(from, to, AMOUNT, "USD", key).expect(201);
        } catch (Api.ConnectionFailed dead) {
            return null;
        }
    }

    private static long transactions(String key) {
        return TestDatabase.queryLong("select count(*) from journal_transactions where idempotency_key = '" + key + "'");
    }

    private static long balance(long accountId) {
        return TestDatabase.queryLong("select balance from account_balances where account_id = " + accountId);
    }

    private static String queryString(String sql) throws Exception {
        try (var connection = TestDatabase.connect();
             var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
