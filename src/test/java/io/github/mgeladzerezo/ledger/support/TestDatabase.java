package io.github.mgeladzerezo.ledger.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** Direct, out-of-band SQL access for tests: the "somebody with psql" of the failure scenarios. */
public final class TestDatabase {

    private TestDatabase() {
    }

    public static Connection connect() throws SQLException {
        PostgreSQLContainer postgres = SharedPostgres.instance();
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * Empties every ledger table. The journal's append-only triggers forbid this in normal
     * operation, so the session switches to {@code session_replication_role = replica}, which
     * disables ordinary triggers for a superuser session. Tests that must bypass the triggers to
     * simulate corruption use {@link #withoutTriggers} the same way.
     */
    public static void reset() {
        withoutTriggers("""
                truncate reconciliation_findings, reconciliation_runs, outbox_deliveries, outbox_events,
                         webhook_subscriptions, idempotency_keys, holds, journal_entries, journal_transactions,
                         account_balances, accounts restart identity cascade
                """);
    }

    /** Runs statements with triggers (and therefore the ledger's database-level guards) switched off. */
    public static void withoutTriggers(String... statements) {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = replica");
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void execute(String sql) {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long queryLong(String sql) {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
