package io.github.mgeladzerezo.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.mgeladzerezo.ledger.support.Concurrent;
import io.github.mgeladzerezo.ledger.support.IntegrationTest;
import io.github.mgeladzerezo.ledger.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Layer three. Each test damages the database in a way the first two layers cannot see or were
 * bypassed for, and shows the reconciliation job reporting exactly that damage.
 */
class ReconciliationTest extends IntegrationTest {

    @Autowired
    ReconciliationService reconciliation;

    long alice;
    long bob;

    @BeforeEach
    void seed() {
        TestDatabase.reset();
        alice = api.openCustomer("USD");
        bob = api.openCustomer("USD");
        api.deposit(alice, 10_000, "USD").expect(201);
        api.transfer(alice, bob, 2_500, "USD").expect(201);
    }

    @Test
    void healthyLedgerWithEveryKindOfActivityReconcilesClean() {
        long eur = api.openCustomer("EUR");
        String hold = api.post("/api/v1/holds", Map.of("accountId", alice, "amount", 1_000, "currency", "USD"))
                .expect(201).json().get("id").asString();
        api.post("/api/v1/holds", Map.of("accountId", alice, "amount", 300, "currency", "USD")).expect(201);
        api.post("/api/v1/holds/" + hold + "/capture", Map.of("toAccountId", bob, "amount", 800)).expect(201);
        String transfer = api.transfer(bob, alice, 100, "USD").expect(201).json().get("id").asString();
        api.post("/api/v1/transactions/" + transfer + "/reversals", Map.of()).expect(201);
        api.post("/api/v1/fx-transfers", Map.of("fromAccountId", alice, "toAccountId", eur, "sourceAmount", 1_000,
                "sourceCurrency", "USD", "targetAmount", 920, "targetCurrency", "EUR")).expect(201);
        api.transfer(alice, bob, 999_999, "USD").expect(422);

        JsonNode report = api.reconcile();

        assertThat(report.get("status").asString()).isEqualTo("CLEAN");
        assertThat(report.get("findingCount").asInt()).isZero();
        assertThat(report.get("trigger").asString()).isEqualTo("MANUAL");
        assertThat(report.get("checks")).hasSize(ReconciliationCheck.values().length);
        assertThat(report.get("checks")).allSatisfy(check -> assertThat(check.get("findings").asInt()).isZero());

        // the report is stored and can be read back
        JsonNode stored = api.get("/api/v1/reconciliation/runs/" + report.get("id").asLong()).expect(200).json();
        assertThat(stored.get("status").asString()).isEqualTo("CLEAN");
        assertThat(api.get("/api/v1/reconciliation/runs").expect(200).json().get(0).get("id").asLong())
                .isEqualTo(report.get("id").asLong());
    }

    @Test
    void balanceRowCorruptedWithDirectSqlIsCaughtAndRepairedByRebuild() {
        assertThat(api.reconcile().get("status").asString()).isEqualTo("CLEAN");

        // somebody "fixes" a balance by hand; no entry backs the extra cent
        TestDatabase.execute("update account_balances set balance = balance + 1 where account_id = " + alice);

        JsonNode report = api.reconcile();
        assertThat(report.get("status").asString()).isEqualTo("FAILED");
        assertThat(report.get("findings")).hasSize(1);
        JsonNode finding = report.get("findings").get(0);
        assertThat(finding.get("check").asString()).isEqualTo("BALANCE_MATCHES_ENTRIES");
        assertThat(finding.get("severity").asString()).isEqualTo("CRITICAL");
        assertThat(finding.get("subject").asString()).startsWith("account " + alice + " ");
        assertThat(finding.get("detail").asString())
                .isEqualTo("materialised balance 7501 <> 7500 computed from entries");

        // the journal is the source of truth: recompute the balance from it
        api.post("/api/v1/accounts/" + alice + "/rebuild-balance", "", null).expect(200);

        assertThat(api.balance(alice)).isEqualTo(7_500);
        assertThat(api.reconcile().get("status").asString()).isEqualTo("CLEAN");
    }

    @Test
    void unbalancedTransactionSmuggledPastTheTriggersIsCaught() {
        UUID tx = UUID.randomUUID();
        TestDatabase.withoutTriggers(
                "insert into journal_transactions (id, kind, created_by) values ('" + tx + "', 'MULTI_LEG', 'psql')",
                "insert into journal_entries (transaction_id, line_no, account_id, side, amount, currency) values "
                        + "('" + tx + "', 1, " + alice + ", 'DEBIT', 500, 'USD'), "
                        + "('" + tx + "', 2, " + bob + ", 'CREDIT', 400, 'USD')");

        JsonNode report = api.reconcile();

        assertThat(report.get("status").asString()).isEqualTo("FAILED");
        assertThat(findings(report, "TRANSACTION_BALANCED")).singleElement().satisfies(f -> {
            assertThat(f.get("subject").asString()).isEqualTo("transaction " + tx);
            assertThat(f.get("detail").asString()).isEqualTo("USD: debits 500 <> credits 400");
        });
        assertThat(findings(report, "GLOBAL_BALANCE")).singleElement()
                .satisfies(f -> assertThat(f.get("subject").asString()).isEqualTo("currency USD"));
        assertThat(findings(report, "BALANCE_MATCHES_ENTRIES")).as("both accounts' balances no longer match").hasSize(2);
        assertThat(findings(report, "OUTBOX_COVERAGE")).hasSize(1);
    }

    @Test
    void orphanEntryAndSingleEntryTransactionAreCaught() {
        UUID missing = UUID.randomUUID();
        UUID lonely = UUID.randomUUID();
        TestDatabase.withoutTriggers(
                "insert into journal_entries (transaction_id, line_no, account_id, side, amount, currency) values "
                        + "('" + missing + "', 1, " + alice + ", 'DEBIT', 5, 'USD')",
                "insert into journal_transactions (id, kind, created_by) values ('" + lonely + "', 'MULTI_LEG', 'psql')",
                "insert into journal_entries (transaction_id, line_no, account_id, side, amount, currency) values "
                        + "('" + lonely + "', 1, " + bob + ", 'CREDIT', 5, 'USD')");

        JsonNode report = api.reconcile();

        assertThat(findings(report, "ORPHAN_ENTRIES")).singleElement().satisfies(
                f -> assertThat(f.get("detail").asString()).isEqualTo("references missing transaction " + missing));
        assertThat(findings(report, "TRANSACTION_HAS_ENTRIES")).singleElement().satisfies(
                f -> assertThat(f.get("subject").asString()).isEqualTo("transaction " + lonely));
    }

    @Test
    void transactionWithoutItsOutboxEventIsCaught() {
        String tx = api.transfer(alice, bob, 10, "USD").expect(201).json().get("id").asString();
        TestDatabase.execute("delete from outbox_deliveries where event_id in "
                + "(select id from outbox_events where transaction_id = '" + tx + "')");
        TestDatabase.execute("delete from outbox_events where transaction_id = '" + tx + "'");

        JsonNode report = api.reconcile();

        assertThat(report.get("status").asString()).isEqualTo("FAILED");
        assertThat(findings(report, "OUTBOX_COVERAGE")).singleElement()
                .satisfies(f -> assertThat(f.get("subject").asString()).isEqualTo("transaction " + tx));
    }

    @Test
    void heldAmountThatNoActiveHoldExplainsIsCaught() {
        api.post("/api/v1/holds", Map.of("accountId", alice, "amount", 1_000, "currency", "USD")).expect(201);
        assertThat(api.reconcile().get("status").asString()).isEqualTo("CLEAN");

        TestDatabase.execute("update account_balances set held = held + 50 where account_id = " + alice);

        JsonNode report = api.reconcile();
        assertThat(findings(report, "HELD_MATCHES_HOLDS")).singleElement().satisfies(
                f -> assertThat(f.get("detail").asString()).isEqualTo("held 1050 <> 1000 sum of active holds"));
    }

    @Test
    void negativeAvailableBalanceOnAnAccountThatForbidsItIsCaught() {
        long overdrawn = api.openAccount("LIABILITY", "USD", false);
        api.transfer(overdrawn, bob, 400, "USD").expect(201);
        // the account is re-flagged behind the application's back; the balance row's own copy of the
        // flag (which backs the CHECK constraint) is left stale
        TestDatabase.execute("update accounts set non_negative = true where id = " + overdrawn);

        JsonNode report = api.reconcile();

        assertThat(findings(report, "HOLDS_WITHIN_BALANCE")).singleElement().satisfies(
                f -> assertThat(f.get("detail").asString()).isEqualTo("balance -400 minus held 0 is below zero"));
    }

    @Test
    void reportsStayCleanWhileTransfersAreInFlight() {
        // Every check of a run reads one REPEATABLE READ snapshot. If the checks read at different
        // instants, a transfer committing between "sum the entries" and "read the balance rows"
        // would show up as a mismatch that never existed.
        AtomicBoolean running = new AtomicBoolean(true);
        List<String> statuses = new ArrayList<>();

        Concurrent.run(9, thread -> {
            if (thread == 0) {
                for (int i = 0; i < 25; i++) {
                    statuses.add(api.reconcile().get("status").asString());
                }
                running.set(false);
            } else {
                while (running.get()) {
                    api.transfer(thread % 2 == 0 ? alice : bob, thread % 2 == 0 ? bob : alice, 3, "USD");
                }
            }
            return null;
        });

        assertThat(statuses).hasSize(25).containsOnly("CLEAN");
        assertThat(api.balance(alice) + api.balance(bob)).isEqualTo(10_000);
    }

    @Test
    void scheduledRunIsSkippedWhileAnotherInstanceHoldsTheLock() throws Exception {
        try (Connection other = TestDatabase.connect(); Statement sql = other.createStatement()) {
            sql.execute("select pg_advisory_lock(" + ReconciliationService.SCHEDULER_LOCK + ")");

            assertThat(reconciliation.runScheduled()).as("another instance is reconciling").isEmpty();

            sql.execute("select pg_advisory_unlock(" + ReconciliationService.SCHEDULER_LOCK + ")");
        }
        assertThat(reconciliation.runScheduled()).hasValueSatisfying(run -> {
            assertThat(run.trigger()).isEqualTo("SCHEDULED");
            assertThat(run.status()).isEqualTo("CLEAN");
        });
    }

    @Test
    void trialBalanceTotalsAgreePerCurrency() {
        JsonNode trialBalance = api.get("/api/v1/reports/trial-balance").expect(200).json();

        JsonNode usd = trialBalance.get("totals").get(0);
        assertThat(usd.get("currency").asString()).isEqualTo("USD");
        assertThat(usd.get("debitBalance").asLong()).isEqualTo(10_000);
        assertThat(usd.get("creditBalance").asLong()).isEqualTo(10_000);
        assertThat(usd.get("balanced").asBoolean()).isTrue();
        assertThat(trialBalance.get("lines")).hasSize(3); // cash, alice, bob
        assertThat(api.get("/api/v1/reports/trial-balance?asOf=2000-01-01T00:00:00Z").expect(200).json()
                .get("totals").get(0).get("debitBalance").asLong()).isZero();
        assertThat(api.get("/api/v1/reports/trial-balance?asOf=nonsense").status()).isEqualTo(400);
    }

    private static List<JsonNode> findings(JsonNode report, String check) {
        List<JsonNode> matching = new ArrayList<>();
        report.get("findings").forEach(finding -> {
            if (finding.get("check").asString().equals(check)) {
                matching.add(finding);
            }
        });
        return matching;
    }
}
