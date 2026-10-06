package io.github.mgeladzerezo.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.mgeladzerezo.ledger.support.Api;
import io.github.mgeladzerezo.ledger.support.Api.Response;
import io.github.mgeladzerezo.ledger.support.Concurrent;
import io.github.mgeladzerezo.ledger.support.IntegrationTest;
import io.github.mgeladzerezo.ledger.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The idempotency contract: one key, one execution, one stored answer, under retries,
 * concurrency, key misuse and expiry.
 */
@TestPropertySource(properties = "ledger.posting.lock-timeout=3s")
class IdempotencyTest extends IntegrationTest {

    @Autowired
    IdempotencyKeyPurger purger;

    long alice;
    long bob;

    @BeforeEach
    void accounts() {
        alice = api.openCustomer("USD");
        bob = api.openCustomer("USD");
        api.deposit(alice, 10_000, "USD").expect(201);
    }

    @Test
    void sameKeyAndBodyReplaysTheStoredResponseWithoutMovingMoneyAgain() {
        String key = key();

        Response first = api.transfer(alice, bob, 1_000, "USD", key).expect(201);
        Response second = api.transfer(alice, bob, 1_000, "USD", key).expect(201);
        Response third = api.transfer(alice, bob, 1_000, "USD", key).expect(201);

        assertThat(first.header("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.body()).as("byte-identical body").isEqualTo(first.body());
        assertThat(third.body()).isEqualTo(first.body());
        assertThat(transactionsWithKey(key)).isEqualTo(1);
        assertThat(api.balance(alice)).isEqualTo(9_000);
        assertThat(api.balance(bob)).isEqualTo(1_000);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejectedWith422() {
        String key = key();
        api.transfer(alice, bob, 1_000, "USD", key).expect(201);

        Response differentAmount = api.transfer(alice, bob, 1_001, "USD", key);
        Response differentEndpoint = api.post("/api/v1/deposits",
                Map.of("accountId", alice, "amount", 1_000, "currency", "USD"), key);

        assertThat(differentAmount.status()).isEqualTo(422);
        assertThat(differentAmount.problemCode()).isEqualTo("idempotency-key-reused");
        assertThat(differentEndpoint.status()).isEqualTo(422);
        assertThat(api.balance(alice)).isEqualTo(9_000);
        // the original answer is still retrievable afterwards
        assertThat(api.transfer(alice, bob, 1_000, "USD", key).header("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void manyThreadsFiringTheSameKeyProduceExactlyOneTransfer() {
        String key = key();

        List<Response> responses = Concurrent.run(32, i -> api.transfer(alice, bob, 2_500, "USD", key));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(responses.stream().map(Response::body).distinct()).as("every caller sees the same transaction")
                .hasSize(1);
        assertThat(responses.stream().filter(r -> "false".equals(r.header("Idempotent-Replayed"))))
                .as("exactly one request executed, the rest replayed").hasSize(1);
        assertThat(transactionsWithKey(key)).isEqualTo(1);
        assertThat(api.balance(alice)).isEqualTo(7_500);
        assertThat(api.balance(bob)).isEqualTo(2_500);
    }

    @Test
    void businessRejectionIsStoredAndReplayedEvenAfterItWouldSucceed() {
        String key = key();

        Response rejected = api.transfer(alice, bob, 50_000, "USD", key);
        api.deposit(alice, 100_000, "USD").expect(201);
        Response retried = api.transfer(alice, bob, 50_000, "USD", key);

        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.problemCode()).isEqualTo("insufficient-funds");
        assertThat(retried.status()).isEqualTo(422);
        assertThat(retried.body()).isEqualTo(rejected.body());
        assertThat(retried.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retried.header("Content-Type")).startsWith("application/problem+json");
        assertThat(transactionsWithKey(key)).isZero();
        assertThat(api.balance(bob)).isZero();
    }

    @Test
    void rejectedRequestLeavesNoPartialLedgerWrites() {
        // The first deposit in a new currency creates the system cash account inside the same
        // transaction. When the posting is then rejected, the savepoint rollback must take that
        // account with it while the key and its stored rejection still commit.
        long frozen = api.openCustomer("CHF");
        api.post("/api/v1/accounts/" + frozen + "/status", Map.of("status", "FROZEN")).expect(200);
        long accountsBefore = TestDatabase.queryLong("select count(*) from accounts");
        String key = key();

        Response rejected = api.post("/api/v1/deposits", Map.of("accountId", frozen, "amount", 100, "currency", "CHF"), key);

        assertThat(rejected.problemCode()).isEqualTo("account-not-open");
        assertThat(TestDatabase.queryLong("select count(*) from accounts")).isEqualTo(accountsBefore);
        assertThat(TestDatabase.queryLong("select count(*) from idempotency_keys where key = '" + key
                + "' and status_code = 422")).isEqualTo(1);
    }

    @Test
    void duplicateWaitsForTheInFlightOriginalAndReturnsItsResponse() throws Exception {
        String key = key();
        try (Connection blocker = TestDatabase.connect(); Statement sql = blocker.createStatement()) {
            // Hold Alice's balance row lock so the original request stalls in the middle of posting.
            blocker.setAutoCommit(false);
            sql.execute("select 1 from account_balances where account_id = " + alice + " for update");

            CompletableFuture<Response> original = CompletableFuture.supplyAsync(
                    () -> api.transfer(alice, bob, 1_000, "USD", key));
            awaitKeyInFlight(key);
            CompletableFuture<Response> duplicate = CompletableFuture.supplyAsync(
                    () -> api.transfer(alice, bob, 1_000, "USD", key));
            Thread.sleep(300);
            assertThat(original).isNotDone();
            assertThat(duplicate).isNotDone();

            blocker.rollback();

            Response first = original.get(10, TimeUnit.SECONDS);
            Response second = duplicate.get(10, TimeUnit.SECONDS);
            assertThat(first.status()).isEqualTo(201);
            assertThat(second.status()).isEqualTo(201);
            assertThat(second.body()).isEqualTo(first.body());
            assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        }
        assertThat(transactionsWithKey(key)).isEqualTo(1);
    }

    @Test
    void duplicateGets409WithRetryAfterWhenTheOriginalOutlastsTheWaitBudget() throws Exception {
        String key = key();
        try (Connection inFlight = TestDatabase.connect(); Statement sql = inFlight.createStatement()) {
            // An uncommitted key row is exactly what an in-flight original looks like to others.
            inFlight.setAutoCommit(false);
            sql.execute("insert into idempotency_keys (scope, key, operation, request_hash, expires_at) values "
                    + "('test', '" + key + "', 'POST /api/v1/transfers', 'pending', now() + interval '1 day')");

            long started = System.nanoTime();
            Response response = api.transfer(alice, bob, 1_000, "USD", key);
            long waitedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(response.status()).isEqualTo(409);
            assertThat(response.problemCode()).isEqualTo("request-in-flight");
            assertThat(response.header("Retry-After")).isEqualTo("1");
            assertThat(waitedMillis).as("waited for the configured 2 s budget first").isBetween(1_800L, 8_000L);

            inFlight.rollback();
        }
        // the "original" never committed, so the key is free and the retry executes
        Response retry = api.transfer(alice, bob, 1_000, "USD", key).expect(201);
        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("false");
        assertThat(transactionsWithKey(key)).isEqualTo(1);
    }

    @Test
    void transientFailureStoresNothingSoTheRetryExecutes() throws Exception {
        String key = key();
        try (Connection blocker = TestDatabase.connect(); Statement sql = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            sql.execute("select 1 from account_balances where account_id = " + alice + " for update");

            // the posting cannot get Alice's row lock within ledger.posting.lock-timeout (3 s here)
            Response timedOut = api.transfer(alice, bob, 1_000, "USD", key);

            assertThat(timedOut.status()).isEqualTo(503);
            assertThat(timedOut.problemCode()).isEqualTo("temporarily-unavailable");
            assertThat(timedOut.header("Retry-After")).isEqualTo("1");
            blocker.rollback();
        }
        assertThat(TestDatabase.queryLong("select count(*) from idempotency_keys where key = '" + key + "'"))
                .as("the key rolled back together with the failed attempt").isZero();

        Response retry = api.transfer(alice, bob, 1_000, "USD", key).expect(201);

        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("false");
        assertThat(api.balance(bob)).isEqualTo(1_000);
    }

    @Test
    void keysAreScopedToThePrincipal() {
        String key = key();
        Api otherClient = client("other-key");

        api.transfer(alice, bob, 1_000, "USD", key).expect(201);
        Response other = otherClient.transfer(alice, bob, 700, "USD", key).expect(201);

        assertThat(other.header("Idempotent-Replayed")).isEqualTo("false");
        assertThat(api.balance(bob)).isEqualTo(1_700);
    }

    @Test
    void expiredKeyIsTreatedAsNewAndPurged() {
        String key = key();
        String stale = key();
        api.transfer(alice, bob, 1_000, "USD", key).expect(201);
        api.transfer(alice, bob, 1, "USD", stale).expect(201);
        jdbc.sql("update idempotency_keys set expires_at = now() - interval '1 second' where key in (?, ?)")
                .params(key, stale).update();

        // past retention the key no longer protects: this is the documented limit of the guarantee
        Response again = api.transfer(alice, bob, 1_000, "USD", key).expect(201);

        assertThat(again.header("Idempotent-Replayed")).isEqualTo("false");
        assertThat(transactionsWithKey(key)).isEqualTo(2);
        assertThat(api.transfer(alice, bob, 1_000, "USD", key).header("Idempotent-Replayed")).isEqualTo("true");

        purger.purge();

        assertThat(TestDatabase.queryLong("select count(*) from idempotency_keys where key = '" + stale + "'")).isZero();
        assertThat(TestDatabase.queryLong("select count(*) from idempotency_keys where key = '" + key + "'"))
                .as("the re-claimed key has a fresh retention period").isEqualTo(1);
    }

    @Test
    void everyMutatingLedgerEndpointHonoursTheKey() {
        String holdKey = key();
        Map<String, Object> holdBody = Map.of("accountId", alice, "amount", 500, "currency", "USD");
        Response hold = api.post("/api/v1/holds", holdBody, holdKey).expect(201);
        assertThat(api.post("/api/v1/holds", holdBody, holdKey).body()).isEqualTo(hold.body());
        assertThat(api.available(alice)).as("one hold, not two").isEqualTo(9_500);

        String captureKey = key();
        String capturePath = "/api/v1/holds/" + hold.json().get("id").asString() + "/capture";
        Response capture = api.post(capturePath, Map.of("toAccountId", bob), captureKey).expect(201);
        Response captureReplay = api.post(capturePath, Map.of("toAccountId", bob), captureKey).expect(201);
        assertThat(captureReplay.body()).isEqualTo(capture.body());
        assertThat(api.balance(bob)).isEqualTo(500);

        String reversalKey = key();
        String reversalPath = "/api/v1/transactions/" + capture.json().get("transaction").get("id").asString()
                + "/reversals";
        api.post(reversalPath, Map.of("reason", "test"), reversalKey).expect(201);
        api.post(reversalPath, Map.of("reason", "test"), reversalKey).expect(201);
        assertThat(api.balance(bob)).isZero();
        assertThat(api.balance(alice)).isEqualTo(10_000);

        String accountKey = key();
        Map<String, Object> accountBody = Map.of("code", "idem-" + accountKey.substring(0, 8), "name", "n",
                "type", "LIABILITY", "currency", "USD");
        Response created = api.post("/api/v1/accounts", accountBody, accountKey).expect(201);
        assertThat(api.post("/api/v1/accounts", accountBody, accountKey).expect(201).body()).isEqualTo(created.body());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private long transactionsWithKey(String key) {
        return jdbc.sql("select count(*) from journal_transactions where idempotency_key = ?").param(key)
                .query(Long.class).single();
    }

    /** Waits until some backend is blocked while holding the given key, i.e. the original is mid-flight. */
    private static void awaitKeyInFlight(String key) throws SQLException, InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            long waiting = TestDatabase.queryLong("select count(*) from pg_stat_activity "
                    + "where wait_event_type = 'Lock' and query like '%account_balances%for update%' "
                    + "and pid <> pg_backend_pid()");
            if (waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the original request never reached the account lock for key " + key);
    }
}
