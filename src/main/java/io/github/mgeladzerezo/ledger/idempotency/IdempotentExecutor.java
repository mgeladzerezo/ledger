package io.github.mgeladzerezo.ledger.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

import io.github.mgeladzerezo.ledger.api.Problem;
import io.github.mgeladzerezo.ledger.config.LedgerProperties;
import io.github.mgeladzerezo.ledger.core.Caller;
import io.github.mgeladzerezo.ledger.core.SqlStates;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.failpoint.Failpoint;
import io.github.mgeladzerezo.ledger.failpoint.Failpoints;
import io.github.mgeladzerezo.ledger.idempotency.IdempotencyRepository.Recorded;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a mutating request at most once per idempotency key, and owns the database transaction in
 * which that happens.
 *
 * <p>One transaction contains: the key row, whatever the action writes (entries, balances,
 * outbox event), and the response stored back onto the key row. They commit together. So there
 * are only two durable outcomes, and a crash at any instant lands in one of them:
 * <ul>
 *   <li>nothing committed: the key is unknown, a retry executes the request;</li>
 *   <li>everything committed: a retry finds the key and replays the stored response.</li>
 * </ul>
 * "Money moved but the key is unknown" and "key recorded but the money did not move" are not
 * reachable states.
 *
 * <p><b>Concurrent duplicates wait, then replay.</b> The first statement of the transaction
 * inserts the key. A duplicate arriving while the original is in flight blocks inside PostgreSQL
 * on that insert and wakes when the original commits, at which point it returns the original's
 * response. Waiting gives the client the real answer without a retry loop. The wait is capped by
 * {@code lock_timeout}; past the cap the duplicate gets 409 with {@code Retry-After}, so a stuck
 * original cannot pin request threads indefinitely.
 *
 * <p><b>Business rejections are stored, failures are not.</b> A {@link LedgerException}
 * (insufficient funds, unknown account) is a final answer: the ledger work is rolled back to a
 * savepoint and the 4xx is committed under the key. Anything else (lock timeout, lost connection)
 * rolls back the whole transaction including the key, so the retry runs from scratch.
 */
@Component
public class IdempotentExecutor {

    private final IdempotencyRepository keys;
    private final TransactionTemplate transaction;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Failpoints failpoints;
    private final Duration retention;
    private final Duration waitTimeout;
    private final Duration lockTimeout;

    public IdempotentExecutor(IdempotencyRepository keys, PlatformTransactionManager transactionManager,
                              JdbcClient jdbc, JsonMapper json, Failpoints failpoints, LedgerProperties properties) {
        this.keys = keys;
        this.transaction = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.json = json;
        this.failpoints = failpoints;
        this.retention = properties.idempotency().retention();
        this.waitTimeout = properties.idempotency().waitTimeout();
        this.lockTimeout = properties.posting().lockTimeout();
    }

    /**
     * @param caller        principal (the key's scope) and the idempotency key
     * @param operation     what is being asked, e.g. {@code "POST /api/v1/transfers"}; part of the fingerprint
     * @param request       the parsed request body; serialised canonically into the fingerprint
     * @param successStatus HTTP status to record when the action returns normally
     * @param action        the work; must only write through the current transaction
     * @throws IdempotencyException.KeyReused if the key is known with a different fingerprint
     * @throws IdempotencyException.InFlight  if the original is still running after the wait budget
     */
    public StoredResponse execute(Caller caller, String operation, Object request, int successStatus,
                                  Supplier<?> action) {
        String scope = caller.principal();
        String key = caller.idempotencyKey();
        String fingerprint = fingerprint(operation, request);

        StoredResponse response = transaction.execute(status -> {
            if (!claim(scope, key, operation, fingerprint)) {
                return replay(scope, key, fingerprint);
            }
            failpoints.hit(Failpoint.AFTER_IDEMPOTENCY_KEY);

            setLockTimeout(lockTimeout);
            Object savepoint = status.createSavepoint();
            StoredResponse outcome;
            try {
                outcome = new StoredResponse(successStatus, json.writeValueAsString(action.get()), false);
            } catch (LedgerException rejection) {
                status.rollbackToSavepoint(savepoint);
                outcome = new StoredResponse(rejection.status(), json.writeValueAsString(Problem.of(rejection)), false);
            }
            status.releaseSavepoint(savepoint);
            keys.complete(scope, key, outcome.status(), outcome.body());
            failpoints.hit(Failpoint.BEFORE_COMMIT);
            return outcome;
        });

        failpoints.hit(Failpoint.AFTER_COMMIT_BEFORE_RESPONSE);
        return response;
    }

    private boolean claim(String scope, String key, String operation, String fingerprint) {
        setLockTimeout(waitTimeout);
        try {
            return keys.tryClaim(scope, key, operation, fingerprint, retention);
        } catch (DataAccessException e) {
            if (SqlStates.is(e, SqlStates.LOCK_NOT_AVAILABLE)) {
                throw new IdempotencyException.InFlight(key, 1);
            }
            throw e;
        }
    }

    private StoredResponse replay(String scope, String key, String fingerprint) {
        // The row can only be missing if the purge job deleted it between the two statements.
        Recorded recorded = keys.find(scope, key).orElseThrow(() -> new IdempotencyException.InFlight(key, 1));
        if (!recorded.requestHash().equals(fingerprint)) {
            throw new IdempotencyException.KeyReused(key);
        }
        return new StoredResponse(recorded.status(), recorded.body(), true);
    }

    /** {@code SET LOCAL lock_timeout}: applies to this transaction only and resets at commit or rollback. */
    private void setLockTimeout(Duration timeout) {
        jdbc.sql("select set_config('lock_timeout', ?, true)").param(timeout.toMillis() + "ms").query(String.class)
                .single();
    }

    private String fingerprint(String operation, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operation.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(json.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
