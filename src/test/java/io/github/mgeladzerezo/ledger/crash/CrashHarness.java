package io.github.mgeladzerezo.ledger.crash;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import io.github.mgeladzerezo.ledger.consumer.SampleConsumer;
import io.github.mgeladzerezo.ledger.support.Api;
import io.github.mgeladzerezo.ledger.support.SharedPostgres;
import io.github.mgeladzerezo.ledger.support.TestDatabase;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the crash tests share: the application process (replaced on every restart), and the
 * shipped {@link SampleConsumer} running inside the test JVM so that it outlives every crash of
 * the application and keeps counting what it receives.
 */
final class CrashHarness implements AutoCloseable {

    static final String WEBHOOK_SECRET = "crash-test-webhook-secret";

    private final SampleConsumer consumer;
    private volatile LedgerProcess process;

    CrashHarness() throws Exception {
        PostgreSQLContainer postgres = SharedPostgres.instance();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        consumer = new SampleConsumer(dataSource, WEBHOOK_SECRET, 0, 0).start();
        process = LedgerProcess.start();
        api().post("/api/v1/webhooks/subscriptions",
                Map.of("url", "http://localhost:" + consumer.port() + "/webhook", "secret", WEBHOOK_SECRET)).expect(201);
    }

    Api api() {
        return process.api();
    }

    LedgerProcess process() {
        return process;
    }

    SampleConsumer consumer() {
        return consumer;
    }

    /** Starts a fresh process in place of the dead one. */
    void restart(String... extraArgs) {
        process.kill();
        process = LedgerProcess.start(extraArgs);
    }

    /** Blocks until the relay has nothing left to deliver. */
    void awaitOutboxDrained(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long pending;
        while ((pending = TestDatabase.queryLong("select count(*) from outbox_deliveries where status = 'PENDING'")) > 0) {
            assertThat(System.nanoTime()).as("%d deliveries still pending after %s", pending, timeout).isLessThan(deadline);
            LedgerProcess.sleep(100);
        }
    }

    long consumerBalance(long accountId) throws SQLException {
        return consumer.projection().getOrDefault(accountId, 0L);
    }

    @Override
    public void close() {
        process.kill();
        consumer.close();
    }
}
