package io.github.mgeladzerezo.ledger.core;

import java.sql.SQLException;
import java.util.Optional;

/** PostgreSQL error codes the application reacts to, and a way to find one in an exception chain. */
public final class SqlStates {

    /** {@code lock_timeout} expired while waiting for a lock. */
    public static final String LOCK_NOT_AVAILABLE = "55P03";
    public static final String DEADLOCK_DETECTED = "40P01";
    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String QUERY_CANCELED = "57014";

    private SqlStates() {
    }

    public static Optional<String> of(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return Optional.of(sql.getSQLState());
            }
        }
        return Optional.empty();
    }

    public static boolean is(Throwable failure, String sqlState) {
        return of(failure).filter(sqlState::equals).isPresent();
    }

    /** Whether the failure is one a client can simply retry: nothing was committed. */
    public static boolean isTransient(Throwable failure) {
        return of(failure)
                .filter(state -> state.equals(LOCK_NOT_AVAILABLE) || state.equals(DEADLOCK_DETECTED)
                        || state.equals(SERIALIZATION_FAILURE) || state.equals(QUERY_CANCELED)
                        || state.startsWith("08"))
                .isPresent();
    }
}
