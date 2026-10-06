package io.github.mgeladzerezo.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.support.IntegrationTest;
import io.github.mgeladzerezo.ledger.support.TestDatabase;
import org.junit.jupiter.api.Test;

/**
 * Layer two: the database refuses bad data even when the application is bypassed. Every test
 * here talks to PostgreSQL with raw SQL, as a buggy code path or an operator with psql would.
 */
class SchemaConstraintsTest extends IntegrationTest {

    @Test
    void unbalancedTransactionFailsAtCommit() throws SQLException {
        long a = api.openCustomer("USD");
        long b = api.openCustomer("USD");
        UUID tx = UUID.randomUUID();

        try (Connection connection = TestDatabase.connect(); Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.execute(insertTransaction(tx));
            // each statement on its own is accepted: the trigger is deferred to COMMIT
            sql.execute(insertEntry(tx, 1, a, "DEBIT", 500));
            sql.execute(insertEntry(tx, 2, b, "CREDIT", 400));

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("unbalanced in USD: debits 500 <> credits 400")
                    .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23514");
        }
        assertThat(TestDatabase.queryLong("select count(*) from journal_transactions where id = '" + tx + "'")).isZero();
        assertThat(TestDatabase.queryLong("select count(*) from journal_entries where transaction_id = '" + tx + "'"))
                .isZero();
    }

    @Test
    void balancedTransactionCommits() throws SQLException {
        long a = api.openAccount("LIABILITY", "USD", false);
        long b = api.openAccount("LIABILITY", "USD", false);
        UUID tx = UUID.randomUUID();

        try (Connection connection = TestDatabase.connect(); Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.execute(insertTransaction(tx));
            sql.execute(insertEntry(tx, 1, a, "DEBIT", 500));
            sql.execute(insertEntry(tx, 2, b, "CREDIT", 500));
            // do by hand what LedgerPoster does, so the shared database stays reconcilable
            sql.execute("update account_balances set balance = -500 where account_id = " + a);
            sql.execute("update account_balances set balance = 500 where account_id = " + b);
            sql.execute("insert into outbox_events (id, event_type, transaction_id, payload) values ('"
                    + UUID.randomUUID() + "', 'transaction.posted', '" + tx + "', '{}')");
            connection.commit();
        }
        assertThat(TestDatabase.queryLong("select count(*) from journal_entries where transaction_id = '" + tx + "'"))
                .isEqualTo(2);
    }

    @Test
    void transactionWithoutEntriesOrWithOneEntryFailsAtCommit() throws SQLException {
        long a = api.openCustomer("USD");

        try (Connection connection = TestDatabase.connect(); Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.execute(insertTransaction(UUID.randomUUID()));
            assertThatThrownBy(connection::commit).hasMessageContaining("has 0 entries, at least 2 are required");

            UUID single = UUID.randomUUID();
            sql.execute(insertTransaction(single));
            sql.execute(insertEntry(single, 1, a, "DEBIT", 100));
            assertThatThrownBy(connection::commit).hasMessageContaining("has 1 entries, at least 2 are required");
        }
    }

    @Test
    void journalRowsCannotBeUpdatedDeletedOrTruncated() {
        long a = api.openCustomer("USD");
        api.deposit(a, 1_000, "USD").expect(201);

        assertForbidden("update journal_entries set amount = amount + 1 where account_id = " + a);
        assertForbidden("delete from journal_entries where account_id = " + a);
        assertForbidden("update journal_transactions set description = 'edited'");
        assertForbidden("delete from journal_transactions");
        assertForbidden("truncate journal_entries");
        assertForbidden("truncate journal_transactions cascade");

        assertThat(api.balance(a)).isEqualTo(1_000);
    }

    @Test
    void checkConstraintStopsAnOverdraftTheApplicationMissed() {
        long a = api.openCustomer("USD");
        api.deposit(a, 1_000, "USD").expect(201);

        assertThatThrownBy(() -> TestDatabase.execute(
                "update account_balances set balance = balance - 1001 where account_id = " + a))
                .hasMessageContaining("account_balances_no_overdraft");
        assertThatThrownBy(() -> TestDatabase.execute(
                "update account_balances set held = 1001 where account_id = " + a))
                .hasMessageContaining("account_balances_no_overdraft");
    }

    @Test
    void entryCurrencyMustMatchItsAccount() throws SQLException {
        long usd = api.openCustomer("USD");
        long other = api.openCustomer("USD");
        UUID tx = UUID.randomUUID();

        try (Connection connection = TestDatabase.connect(); Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.execute(insertTransaction(tx));
            assertThatThrownBy(() -> sql.execute("insert into journal_entries (transaction_id, line_no, account_id, "
                    + "side, amount, currency) values ('" + tx + "', 1, " + usd + ", 'DEBIT', 100, 'EUR')"))
                    .hasMessageContaining("foreign key");
            connection.rollback();
        }
        assertThat(api.balance(other)).isZero();
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        long a = api.openCustomer("USD");
        UUID tx = UUID.randomUUID();

        assertThatThrownBy(() -> TestDatabase.execute(insertTransaction(tx) + "; " + insertEntry(tx, 1, a, "DEBIT", 0)))
                .hasMessageContaining("journal_entries_amount_check");
    }

    private static void assertForbidden(String sql) {
        assertThatThrownBy(() -> TestDatabase.execute(sql)).hasMessageContaining("the journal is append-only");
    }

    private static String insertTransaction(UUID id) {
        return "insert into journal_transactions (id, kind, created_by) values ('" + id + "', 'MULTI_LEG', 'raw-sql')";
    }

    private static String insertEntry(UUID tx, int line, long account, String side, long amount) {
        return "insert into journal_entries (transaction_id, line_no, account_id, side, amount, currency) values ('"
                + tx + "', " + line + ", " + account + ", '" + side + "', " + amount + ", 'USD')";
    }
}
