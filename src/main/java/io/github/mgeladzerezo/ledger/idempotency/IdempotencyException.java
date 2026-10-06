package io.github.mgeladzerezo.ledger.idempotency;

/** The idempotency layer refused the request before any ledger work ran. Never stored under the key. */
public abstract sealed class IdempotencyException extends RuntimeException {

    protected IdempotencyException(String message) {
        super(message);
    }

    /** The key was already used for a different request: a client bug, answered with 422. */
    public static final class KeyReused extends IdempotencyException {
        public KeyReused(String key) {
            super("idempotency key '" + key + "' was already used with a different request");
        }
    }

    /**
     * The original request with this key is still running and did not finish within the wait
     * budget: answered with 409 and {@code Retry-After}.
     */
    public static final class InFlight extends IdempotencyException {
        private final int retryAfterSeconds;

        public InFlight(String key, int retryAfterSeconds) {
            super("a request with idempotency key '" + key + "' is still in progress");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
