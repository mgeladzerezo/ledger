package io.github.mgeladzerezo.ledger.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that run the whole application in-process on a random port against the
 * shared Testcontainers PostgreSQL. Background jobs that would make assertions racy (the relay
 * loop, scheduled reconciliation) are off; tests drive them explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ledger.security.api-keys.test=test-key",
        "ledger.security.api-keys.other=other-key",
        "ledger.outbox.relay-enabled=false",
        "ledger.outbox.max-attempts=3",
        "ledger.outbox.backoff-base=50ms",
        "ledger.outbox.backoff-max=200ms",
        "ledger.outbox.http-timeout=2s",
        "ledger.reconciliation.scheduled=false",
        "ledger.idempotency.wait-timeout=2s",
        "spring.datasource.hikari.maximum-pool-size=24"
})
public abstract class IntegrationTest {

    protected static final String API_KEY = "test-key";

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    protected Api api;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSQLContainer postgres = SharedPostgres.instance();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void createClient() {
        api = new Api("http://localhost:" + port, API_KEY);
    }
}
