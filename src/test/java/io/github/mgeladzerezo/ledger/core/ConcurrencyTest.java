package io.github.mgeladzerezo.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import io.github.mgeladzerezo.ledger.support.Api.Response;
import io.github.mgeladzerezo.ledger.support.Concurrent;
import io.github.mgeladzerezo.ledger.support.IntegrationTest;
import io.github.mgeladzerezo.ledger.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Many threads, few accounts. Proves that lock ordering keeps concurrent transfers free of
 * deadlocks, that the overdraft check cannot be raced, and that money is conserved throughout.
 */
class ConcurrencyTest extends IntegrationTest {

    @BeforeEach
    void emptyDatabase() {
        TestDatabase.reset();
    }

    @Test
    void randomTransfersAmongFewAccountsConserveMoneyAndNeverDeadlock() {
        int accounts = 5;
        long opening = 10_000;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            long id = api.openCustomer("USD");
            api.deposit(id, opening, "USD").expect(201);
            ids.add(id);
        }
        long deadlocksBefore = deadlocks();

        // Amounts up to 6,000 against balances around 10,000: a good share must be rejected.
        List<int[]> outcomes = Concurrent.run(24, thread -> {
            int ok = 0;
            int insufficient = 0;
            int other = 0;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 80; i++) {
                int from = random.nextInt(accounts);
                int to = (from + 1 + random.nextInt(accounts - 1)) % accounts;
                Response response = api.transfer(ids.get(from), ids.get(to), random.nextLong(1, 6_000), "USD");
                if (response.status() == 201) {
                    ok++;
                } else if (response.status() == 422 && response.problemCode().equals("insufficient-funds")) {
                    insufficient++;
                } else {
                    other++;
                }
            }
            return new int[] {ok, insufficient, other};
        });

        int ok = outcomes.stream().mapToInt(o -> o[0]).sum();
        int insufficient = outcomes.stream().mapToInt(o -> o[1]).sum();
        int other = outcomes.stream().mapToInt(o -> o[2]).sum();
        assertThat(other).as("no deadlock, lock timeout or server error reached a caller").isZero();
        assertThat(ok).as("transfers that succeeded").isGreaterThan(300);
        assertThat(insufficient).as("the overdraft rule was actually exercised").isGreaterThan(0);

        long total = 0;
        for (long id : ids) {
            JsonNode account = api.account(id);
            assertThat(account.get("balance").asLong()).as("account %d may not go negative", id).isGreaterThanOrEqualTo(0);
            total += account.get("balance").asLong();
        }
        assertThat(total).as("money is conserved").isEqualTo(accounts * opening);
        assertThat(jdbc.sql("select count(*) from journal_transactions where kind = 'TRANSFER'").query(Long.class).single())
                .as("one journal transaction per acknowledged transfer").isEqualTo(ok);
        assertThat(deadlocks()).as("PostgreSQL's own deadlock counter did not move").isEqualTo(deadlocksBefore);
        assertThat(api.reconcile().get("status").asString()).isEqualTo("CLEAN");
    }

    @Test
    void oppositeDirectionTransfersBetweenTwoAccountsDoNotDeadlock() {
        long a = api.openCustomer("USD");
        long b = api.openCustomer("USD");
        api.deposit(a, 1_000_000, "USD").expect(201);
        api.deposit(b, 1_000_000, "USD").expect(201);

        // Even threads send A to B, odd threads B to A: the textbook deadlock if locks were taken
        // in "from, then to" order.
        List<Integer> failures = Concurrent.run(16, thread -> {
            int failed = 0;
            for (int i = 0; i < 40; i++) {
                Response response = thread % 2 == 0
                        ? api.transfer(a, b, 7, "USD")
                        : api.transfer(b, a, 7, "USD");
                if (response.status() != 201) {
                    failed++;
                }
            }
            return failed;
        });

        assertThat(failures).containsOnly(0);
        assertThat(api.balance(a) + api.balance(b)).isEqualTo(2_000_000);
        assertThat(api.balance(a)).as("equal traffic both ways nets to zero").isEqualTo(1_000_000);
    }

    /**
     * The control experiment for the two tests above: the same pair of row locks taken in
     * opposite orders by two sessions does deadlock, and PostgreSQL kills one of them. This is
     * what the ledger would do without {@link AccountRepository#lockInOrder}.
     */
    @Test
    void lockingInInconsistentOrderDoesDeadlock() throws Exception {
        long a = api.openCustomer("USD");
        long b = api.openCustomer("USD");
        CyclicBarrier bothHoldFirstLock = new CyclicBarrier(2);

        List<String> sqlStates = Concurrent.run(2, thread -> {
            long first = thread == 0 ? a : b;
            long second = thread == 0 ? b : a;
            try (Connection connection = TestDatabase.connect(); Statement sql = connection.createStatement()) {
                connection.setAutoCommit(false);
                sql.execute("select 1 from account_balances where account_id = " + first + " for update");
                bothHoldFirstLock.await(10, TimeUnit.SECONDS);
                sql.execute("select 1 from account_balances where account_id = " + second + " for update");
                connection.commit();
                return "ok";
            } catch (SQLException e) {
                return e.getSQLState();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(sqlStates).containsExactlyInAnyOrder("ok", SqlStates.DEADLOCK_DETECTED);
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawAnAccount() {
        long account = api.openCustomer("USD");
        api.deposit(account, 1_000, "USD").expect(201);

        // 20 simultaneous withdrawals of 100 from a balance of 1,000: exactly 10 can succeed.
        List<Integer> statuses = Concurrent.run(20, thread -> api.post("/api/v1/withdrawals",
                Map.of("accountId", account, "amount", 100, "currency", "USD")).status());

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(10);
        assertThat(statuses.stream().filter(s -> s == 422)).hasSize(10);
        assertThat(api.balance(account)).isZero();
    }

    @Test
    void concurrentHoldsCannotReserveMoreThanTheBalance() {
        long account = api.openCustomer("USD");
        api.deposit(account, 1_000, "USD").expect(201);

        List<Integer> statuses = Concurrent.run(20, thread -> api.post("/api/v1/holds",
                Map.of("accountId", account, "amount", 100, "currency", "USD")).status());

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(10);
        assertThat(api.available(account)).isZero();
        assertThat(api.balance(account)).isEqualTo(1_000);
    }

    @Test
    void aHoldCanBeCapturedOnlyOnceUnderConcurrency() {
        long payer = api.openCustomer("USD");
        long shop = api.openCustomer("USD");
        api.deposit(payer, 1_000, "USD").expect(201);
        String hold = api.post("/api/v1/holds", Map.of("accountId", payer, "amount", 400, "currency", "USD"))
                .expect(201).json().get("id").asString();

        // different idempotency keys, so only the hold's own state can stop the double capture;
        // half the threads try to release instead
        List<Integer> statuses = Concurrent.run(12, thread -> thread % 2 == 0
                ? api.post("/api/v1/holds/" + hold + "/capture", Map.of("toAccountId", shop)).status()
                : api.post("/api/v1/holds/" + hold + "/release", "").status());

        assertThat(statuses.stream().filter(s -> s == 201 || s == 200)).as("exactly one resolution wins").hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(11);
        assertThat(api.balance(payer) + api.balance(shop)).isEqualTo(1_000);
        assertThat(api.available(payer)).isEqualTo(api.balance(payer));
        assertThat(api.reconcile().get("status").asString()).isEqualTo("CLEAN");
    }

    @Test
    void aTransactionCanBeReversedOnlyOnceUnderConcurrency() {
        long a = api.openCustomer("USD");
        long b = api.openCustomer("USD");
        api.deposit(a, 1_000, "USD").expect(201);
        String transfer = api.transfer(a, b, 600, "USD").expect(201).json().get("id").asString();

        List<Integer> statuses = Concurrent.run(10, thread ->
                api.post("/api/v1/transactions/" + transfer + "/reversals", Map.of("reason", "t" + thread)).status());

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(9);
        assertThat(api.balance(a)).isEqualTo(1_000);
        assertThat(api.balance(b)).isZero();
    }

    private long deadlocks() {
        return jdbc.sql("select deadlocks from pg_stat_database where datname = current_database()")
                .query(Long.class).single();
    }
}
