package io.github.mgeladzerezo.ledger.crash;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.mgeladzerezo.ledger.support.Api;
import io.github.mgeladzerezo.ledger.support.Api.Response;
import io.github.mgeladzerezo.ledger.support.TestDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Randomised soak: client threads fire random transfers among a few accounts while the
 * application process is killed with {@code destroyForcibly()} at random moments and restarted,
 * several times. Clients behave like real ones: on a dropped connection or a 5xx they retry the
 * same request with the same idempotency key until they get a definitive answer.
 *
 * <p>At the end: total money is unchanged, no balance is negative, every acknowledged transfer
 * exists exactly once, no key produced two transfers, the consumer's projection equals the
 * ledger, and reconciliation is clean.
 *
 * <p>Runs only with {@code -Psoak}. Tunable with {@code -Dsoak.kills}, {@code -Dsoak.threads},
 * {@code -Dsoak.seed}.
 */
@Tag("soak")
class CrashSoakIT {

    private static final int ACCOUNTS = 6;
    private static final long OPENING = 50_000;

    private final int kills = Integer.getInteger("soak.kills", 8);
    private final int threads = Integer.getInteger("soak.threads", 12);
    private final long seed = Long.getLong("soak.seed", System.nanoTime());

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Set<String> acknowledged = ConcurrentHashMap.newKeySet();
    private final Set<String> rejected = ConcurrentHashMap.newKeySet();
    private final AtomicLong retriesAfterFailure = new AtomicLong();

    @Test
    void moneyIsConservedAcrossRandomKillsUnderLoad() throws Exception {
        System.out.printf("soak: seed=%d kills=%d threads=%d%n", seed, kills, threads);
        Random killer = new Random(seed);
        try (CrashHarness harness = new CrashHarness()) {
            List<Long> accounts = new ArrayList<>();
            for (int i = 0; i < ACCOUNTS; i++) {
                long id = harness.api().openCustomer("USD");
                harness.api().deposit(id, OPENING, "USD").expect(201);
                accounts.add(id);
            }

            try (ExecutorService clients = Executors.newFixedThreadPool(threads)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    long clientSeed = seed + t;
                    futures.add(clients.submit(() -> client(harness, accounts, new Random(clientSeed))));
                }

                for (int kill = 1; kill <= kills; kill++) {
                    LedgerProcess.sleep(1_500 + killer.nextInt(2_500));
                    harness.process().kill();
                    harness.restart();
                    System.out.printf("soak: kill %d/%d done, %d transfers acknowledged so far%n", kill, kills,
                            acknowledged.size());
                }
                LedgerProcess.sleep(2_000);
                running.set(false);
                for (Future<?> future : futures) {
                    future.get(120, TimeUnit.SECONDS);
                }
            }

            // ---- the ledger ----
            Api api = harness.api();
            long total = 0;
            for (long id : accounts) {
                long balance = api.balance(id);
                assertThat(balance).as("account %d may not go negative", id).isGreaterThanOrEqualTo(0);
                total += balance;
            }
            assertThat(total).as("money is conserved across %d kills", kills).isEqualTo(ACCOUNTS * OPENING);
            assertThat(acknowledged).as("the soak did real work").hasSizeGreaterThan(100);

            assertThat(TestDatabase.queryLong("""
                    select count(*) from (select idempotency_key from journal_transactions
                                          where idempotency_key is not null
                                          group by created_by, idempotency_key having count(*) > 1) duplicates"""))
                    .as("no idempotency key produced more than one transaction").isZero();
            assertThat(TestDatabase.queryLong("select count(*) from journal_transactions where kind = 'TRANSFER'"))
                    .as("every acknowledged transfer exists exactly once, and nothing else does")
                    .isEqualTo(acknowledged.size());
            for (String key : rejected) {
                assertThat(TestDatabase.queryLong(
                        "select count(*) from journal_transactions where idempotency_key = '" + key + "'"))
                        .as("rejected request %s moved no money", key).isZero();
            }

            // ---- the outbox and its consumer ----
            harness.awaitOutboxDrained(Duration.ofSeconds(120));
            assertThat(harness.consumer().stats().uniqueEvents())
                    .as("the consumer has seen every event")
                    .isEqualTo(TestDatabase.queryLong("select count(*) from outbox_events"));
            for (long id : accounts) {
                assertThat(harness.consumerBalance(id)).as("consumer projection of account %d", id)
                        .isEqualTo(api.balance(id));
            }

            // ---- reconciliation ----
            assertThat(api.reconcile().get("status").asString()).isEqualTo("CLEAN");

            System.out.printf("soak: %d transfers acknowledged, %d rejected for insufficient funds, "
                            + "%d client retries after a failure, %d duplicate deliveries discarded by the consumer%n",
                    acknowledged.size(), rejected.size(), retriesAfterFailure.get(),
                    harness.consumer().stats().duplicates());
        }
    }

    private void client(CrashHarness harness, List<Long> accounts, Random random) {
        while (running.get()) {
            int from = random.nextInt(accounts.size());
            int to = (from + 1 + random.nextInt(accounts.size() - 1)) % accounts.size();
            long amount = 1 + random.nextInt(4_000);
            String key = UUID.randomUUID().toString();
            Map<String, Object> body = Map.of("fromAccountId", accounts.get(from), "toAccountId", accounts.get(to),
                    "amount", amount, "currency", "USD");

            // Retry the same request with the same key until the answer is definitive.
            while (true) {
                Integer status = null;
                try {
                    Response response = harness.api().post("/api/v1/transfers", body, key);
                    status = response.status();
                } catch (Api.ConnectionFailed down) {
                    // the process is dead or restarting
                }
                if (status != null && status == 201) {
                    acknowledged.add(key);
                    break;
                }
                if (status != null && status == 422) {
                    rejected.add(key);
                    break;
                }
                if (status != null && status != 409 && status < 500) {
                    throw new AssertionError("unexpected HTTP " + status + " for a transfer");
                }
                retriesAfterFailure.incrementAndGet();
                LedgerProcess.sleep(50);
            }
        }
    }
}
