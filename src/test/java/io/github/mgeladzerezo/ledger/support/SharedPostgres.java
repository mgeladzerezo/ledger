package io.github.mgeladzerezo.ledger.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL 16 container per test JVM, started on first use and removed by Testcontainers'
 * reaper when the JVM exits. Every test class shares it; tests isolate themselves by creating
 * their own accounts, or by calling {@link TestDatabase#reset}.
 */
public final class SharedPostgres {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ledger")
            .withUsername("ledger")
            .withPassword("ledger");

    private SharedPostgres() {
    }

    public static synchronized PostgreSQLContainer instance() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }
}
