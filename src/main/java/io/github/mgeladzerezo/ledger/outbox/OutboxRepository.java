package io.github.mgeladzerezo.ledger.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.core.Rows;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the relay's claim/mark cycle, for webhook subscriptions, and for the outbox views. */
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks up to {@code limit} deliveries that are due and returns them. {@code FOR UPDATE SKIP
     * LOCKED} is what makes several relay instances safe: each instance takes rows nobody else
     * holds and never waits for, or double-sends, a row another instance is working on. The
     * locks last until the caller's transaction ends, so a relay that dies releases its claim
     * without any lease or timeout bookkeeping.
     */
    public List<ClaimedDelivery> claimDue(int limit) {
        return jdbc.sql("""
                        select d.id, d.event_id, d.attempts, s.url, s.secret, e.event_type, e.seq,
                               e.payload::text as payload, e.created_at
                        from outbox_deliveries d
                        join outbox_events e on e.id = d.event_id
                        join webhook_subscriptions s on s.id = d.subscription_id
                        where d.status = 'PENDING' and d.next_attempt_at <= now() and s.active
                        order by d.next_attempt_at, d.id
                        limit ?
                        for update of d skip locked
                        """)
                .param(limit)
                .query((rs, rowNum) -> new ClaimedDelivery(rs.getLong("id"), Rows.uuid(rs, "event_id"),
                        rs.getInt("attempts"), rs.getString("url"), rs.getString("secret"),
                        rs.getString("event_type"), rs.getLong("seq"), rs.getString("payload"),
                        Rows.instant(rs, "created_at")))
                .list();
    }

    public void markSent(long deliveryId, int httpStatus) {
        jdbc.sql("""
                        update outbox_deliveries
                        set status = 'SENT', attempts = attempts + 1, sent_at = now(), last_status = ?, last_error = null
                        where id = ?
                        """)
                .params(httpStatus, deliveryId)
                .update();
    }

    /** Schedules another attempt after {@code retryIn}, or parks the delivery as DEAD if {@code retryIn} is null. */
    public void markFailed(long deliveryId, Integer httpStatus, String error, Duration retryIn) {
        jdbc.sql("""
                        update outbox_deliveries
                        set status = ?, attempts = attempts + 1, last_status = ?, last_error = ?,
                            next_attempt_at = now() + (? * interval '1 millisecond')
                        where id = ?
                        """)
                .params(retryIn == null ? "DEAD" : "PENDING", httpStatus, error,
                        retryIn == null ? 0L : retryIn.toMillis(), deliveryId)
                .update();
    }

    /** Puts a DEAD (or still pending) delivery back at the front of the queue with a fresh attempt budget. */
    public boolean requeue(long deliveryId) {
        return jdbc.sql("""
                        update outbox_deliveries
                        set status = 'PENDING', attempts = 0, next_attempt_at = now()
                        where id = ? and status <> 'SENT'
                        """)
                .param(deliveryId)
                .update() == 1;
    }

    public Subscription insertSubscription(String url, String secret) {
        long id = jdbc.sql("insert into webhook_subscriptions (url, secret) values (?, ?) returning id")
                .params(url, secret).query(Long.class).single();
        return listSubscriptions().stream().filter(s -> s.id() == id).findFirst().orElseThrow();
    }

    public List<Subscription> listSubscriptions() {
        return jdbc.sql("select id, url, active, created_at from webhook_subscriptions order by id")
                .query((rs, rowNum) -> new Subscription(rs.getLong("id"), rs.getString("url"),
                        rs.getBoolean("active"), Rows.instant(rs, "created_at")))
                .list();
    }

    public boolean deactivateSubscription(long id) {
        return jdbc.sql("update webhook_subscriptions set active = false where id = ?").param(id).update() == 1;
    }

    /** Newest events first, each with a roll-up of its deliveries. */
    public List<EventSummary> listEvents(long beforeSeq, int limit) {
        return jdbc.sql("""
                        select e.id, e.seq, e.event_type, e.transaction_id, e.created_at,
                               count(d.id) as deliveries,
                               count(d.id) filter (where d.status = 'SENT') as sent,
                               count(d.id) filter (where d.status = 'PENDING') as pending,
                               count(d.id) filter (where d.status = 'DEAD') as dead,
                               coalesce(sum(d.attempts), 0) as attempts
                        from outbox_events e
                        left join outbox_deliveries d on d.event_id = e.id
                        where e.seq < ?
                        group by e.id
                        order by e.seq desc
                        limit ?
                        """)
                .params(beforeSeq, limit)
                .query((rs, rowNum) -> new EventSummary(Rows.uuid(rs, "id"), rs.getLong("seq"),
                        rs.getString("event_type"), Rows.uuid(rs, "transaction_id"), Rows.instant(rs, "created_at"),
                        rs.getInt("deliveries"), rs.getInt("sent"), rs.getInt("pending"), rs.getInt("dead"),
                        rs.getInt("attempts")))
                .list();
    }

    /**
     * @param status restrict to one delivery status, or {@code null} for all
     */
    public List<DeliveryView> listDeliveries(String status, long beforeId, int limit) {
        return jdbc.sql("""
                        select d.id, d.event_id, e.event_type, e.seq, s.url, d.status, d.attempts, d.next_attempt_at,
                               d.last_status, d.last_error, d.created_at, d.sent_at
                        from outbox_deliveries d
                        join outbox_events e on e.id = d.event_id
                        join webhook_subscriptions s on s.id = d.subscription_id
                        where d.id < :before and (cast(:status as text) is null or d.status = :status)
                        order by d.id desc
                        limit :limit
                        """)
                .param("before", beforeId)
                .param("status", status)
                .param("limit", limit)
                .query((rs, rowNum) -> new DeliveryView(rs.getLong("id"), Rows.uuid(rs, "event_id"),
                        rs.getString("event_type"), rs.getLong("seq"), rs.getString("url"), rs.getString("status"),
                        rs.getInt("attempts"), Rows.instant(rs, "next_attempt_at"),
                        Rows.nullableInt(rs, "last_status"), rs.getString("last_error"),
                        Rows.instant(rs, "created_at"), Rows.instant(rs, "sent_at")))
                .list();
    }

    public OutboxStats stats() {
        Map<String, Long> byStatus = new TreeMap<>(Map.of("PENDING", 0L, "SENT", 0L, "DEAD", 0L));
        jdbc.sql("select status, count(*) as n from outbox_deliveries group by status")
                .query((rs, rowNum) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list()
                .forEach(entry -> byStatus.put(entry.getKey(), entry.getValue()));
        long events = jdbc.sql("select count(*) from outbox_events").query(Long.class).single();
        Double oldestPendingSeconds = jdbc.sql("""
                        select extract(epoch from now() - min(created_at))::float8
                        from outbox_deliveries where status = 'PENDING'
                        """)
                .query(Double.class)
                .optional()
                .orElse(null);
        return new OutboxStats(events, byStatus.get("PENDING"), byStatus.get("SENT"), byStatus.get("DEAD"),
                oldestPendingSeconds);
    }

    /** A delivery whose row lock the current transaction holds, with everything needed to send it. */
    public record ClaimedDelivery(long id, UUID eventId, int attempts, String url, String secret, String eventType,
                                  long sequence, String payload, Instant createdAt) {
    }

    public record Subscription(long id, String url, boolean active, Instant createdAt) {
    }

    public record EventSummary(UUID id, long sequence, String eventType, UUID transactionId, Instant createdAt,
                               int deliveries, int sent, int pending, int dead, int attempts) {
    }

    public record DeliveryView(long id, UUID eventId, String eventType, long sequence, String url, String status,
                               int attempts, Instant nextAttemptAt, Integer lastStatus, String lastError,
                               Instant createdAt, Instant sentAt) {
    }

    public record OutboxStats(long events, long pending, long sent, long dead, Double oldestPendingSeconds) {
    }
}
