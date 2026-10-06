package io.github.mgeladzerezo.ledger.outbox;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write half of the transactional outbox. An event is a row inserted by the same database
 * transaction that changes the ledger, so "the money moved" and "the event exists" commit or
 * roll back together. Nothing is sent from here; the {@link OutboxRelay} does that later.
 */
@Component
public class OutboxWriter {

    public static final String TRANSACTION_POSTED = "transaction.posted";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Records an event and one pending delivery per active subscription. Fan-out happens at write
     * time, so a subscription created later does not receive earlier events.
     *
     * @param transactionId the journal transaction the event describes, or {@code null}
     * @return the event id, which consumers use to deduplicate
     */
    public UUID append(String eventType, UUID transactionId, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("outbox events must be written inside the business transaction");
        }
        UUID eventId = UUID.randomUUID();
        jdbc.sql("insert into outbox_events (id, event_type, transaction_id, payload) values (?, ?, ?, ?::jsonb)")
                .params(eventId, eventType, transactionId, json.writeValueAsString(payload))
                .update();
        jdbc.sql("""
                        insert into outbox_deliveries (event_id, subscription_id)
                        select ?, s.id from webhook_subscriptions s where s.active
                        """)
                .param(eventId)
                .update();
        return eventId;
    }
}
