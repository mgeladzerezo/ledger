package io.github.mgeladzerezo.ledger.idempotency;

import java.time.Duration;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the {@code idempotency_keys} table. */
@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tries to become the owner of the key within the current transaction.
     *
     * <p>If another transaction has inserted the same key and not yet finished, this statement
     * blocks on the primary key until that transaction commits (then this returns {@code false})
     * or rolls back (then this insert succeeds). That wait is how concurrent duplicates are
     * serialised; it is bounded by the session's {@code lock_timeout}.
     *
     * <p>A key whose retention has run out but has not been purged yet is taken over in place.
     *
     * @return {@code true} if the caller now owns the key and must execute the request
     */
    public boolean tryClaim(String scope, String key, String operation, String requestHash, Duration retention) {
        return jdbc.sql("""
                        insert into idempotency_keys as k (scope, key, operation, request_hash, expires_at)
                        values (?, ?, ?, ?, now() + (? * interval '1 millisecond'))
                        on conflict (scope, key) do update
                            set operation = excluded.operation,
                                request_hash = excluded.request_hash,
                                status_code = null,
                                response_body = null,
                                created_at = now(),
                                expires_at = excluded.expires_at
                            where k.expires_at <= now()
                        returning k.key
                        """)
                .params(scope, key, operation, requestHash, retention.toMillis())
                .query(String.class)
                .optional()
                .isPresent();
    }

    public Optional<Recorded> find(String scope, String key) {
        return jdbc.sql("select request_hash, status_code, response_body from idempotency_keys where scope = ? and key = ?")
                .params(scope, key)
                .query((rs, rowNum) -> new Recorded(rs.getString("request_hash"), rs.getInt("status_code"),
                        rs.getString("response_body")))
                .optional();
    }

    public void complete(String scope, String key, int status, String body) {
        jdbc.sql("update idempotency_keys set status_code = ?, response_body = ? where scope = ? and key = ?")
                .params(status, body, scope, key)
                .update();
    }

    public int purgeExpired() {
        return jdbc.sql("delete from idempotency_keys where expires_at <= now()").update();
    }

    /** A committed key record. {@code body} is null only if the row was corrupted out of band. */
    public record Recorded(String requestHash, int status, String body) {
    }
}
