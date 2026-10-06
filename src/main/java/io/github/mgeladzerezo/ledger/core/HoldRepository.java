package io.github.mgeladzerezo.ledger.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.domain.HoldStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for authorisation holds. */
@Repository
public class HoldRepository {

    private static final String COLUMNS = """
            id, account_id, currency, amount, status, captured_amount, capture_transaction_id, description,
            created_at, expires_at, resolved_at
            """;

    private final JdbcClient jdbc;

    public HoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, long accountId, String currency, long amount, String description, Duration ttl,
                       String createdBy) {
        jdbc.sql("""
                        insert into holds (id, account_id, currency, amount, status, description, created_by, expires_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?, now() + (? * interval '1 millisecond'))
                        """)
                .params(id, accountId, currency, amount, description, createdBy, ttl.toMillis())
                .update();
    }

    public Optional<Hold> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from holds where id = ?").param(id).query(HoldRepository::map)
                .optional();
    }

    /** Locks the hold row. Always taken before any account lock, so hold operations cannot deadlock. */
    public Optional<LockedHold> lock(UUID id) {
        return jdbc.sql("select " + COLUMNS + ", (expires_at <= now()) as expired from holds where id = ? for update")
                .param(id)
                .query((rs, rowNum) -> new LockedHold(map(rs, rowNum), rs.getBoolean("expired")))
                .optional();
    }

    /**
     * Locks up to {@code limit} active holds whose expiry has passed. SKIP LOCKED lets several
     * instances run the expiry job without waiting on each other or on a concurrent capture.
     */
    public List<Hold> lockExpired(int limit) {
        return jdbc.sql("select " + COLUMNS + """
                         from holds
                        where status = 'ACTIVE' and expires_at <= now()
                        order by expires_at
                        limit ?
                        for update skip locked
                        """)
                .param(limit)
                .query(HoldRepository::map)
                .list();
    }

    public List<Hold> list(Long accountId, int limit) {
        if (accountId == null) {
            return jdbc.sql("select " + COLUMNS + " from holds order by created_at desc, id limit ?")
                    .param(limit).query(HoldRepository::map).list();
        }
        return jdbc.sql("select " + COLUMNS + " from holds where account_id = ? order by created_at desc, id limit ?")
                .params(accountId, limit).query(HoldRepository::map).list();
    }

    public void markCaptured(UUID id, long capturedAmount, UUID transactionId) {
        jdbc.sql("""
                        update holds
                        set status = 'CAPTURED', captured_amount = ?, capture_transaction_id = ?, resolved_at = now()
                        where id = ?
                        """)
                .params(capturedAmount, transactionId, id)
                .update();
    }

    public void markResolved(UUID id, HoldStatus status) {
        jdbc.sql("update holds set status = ?, resolved_at = now() where id = ?").params(status.name(), id).update();
    }

    /** Sum of the account's active holds: what {@code account_balances.held} must equal. */
    public long activeTotal(long accountId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from holds where account_id = ? and status = 'ACTIVE'")
                .param(accountId)
                .query(Long.class)
                .single();
    }

    private static Hold map(ResultSet rs, int rowNum) throws SQLException {
        return new Hold(
                Rows.uuid(rs, "id"),
                rs.getLong("account_id"),
                rs.getString("currency"),
                rs.getLong("amount"),
                HoldStatus.valueOf(rs.getString("status")),
                rs.getLong("captured_amount"),
                Rows.uuid(rs, "capture_transaction_id"),
                rs.getString("description"),
                Rows.instant(rs, "created_at"),
                Rows.instant(rs, "expires_at"),
                Rows.instant(rs, "resolved_at"));
    }

    /** A hold whose row lock is held by the current transaction. */
    public record LockedHold(Hold hold, boolean expired) {
    }
}
