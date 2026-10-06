package io.github.mgeladzerezo.ledger.outbox;

import java.time.Duration;

/**
 * Exponential backoff with jitter for failed webhook deliveries: the delay doubles with every
 * failed attempt up to a cap, and a random part of it is shaved off so that deliveries that
 * failed together do not all retry in the same instant.
 */
public record BackoffPolicy(Duration base, Duration max) {

    /**
     * @param failedAttempts number of attempts that have failed so far, at least 1
     * @param jitter         a random number in [0, 1); 0 gives the full exponential delay
     * @return the wait before the next attempt, between half of and the whole exponential delay
     */
    public Duration delay(int failedAttempts, double jitter) {
        int doublings = Math.min(Math.max(failedAttempts, 1) - 1, 30);
        long exponential = Math.min(max.toMillis(), base.toMillis() * (1L << doublings));
        if (exponential <= 0) {
            exponential = max.toMillis();
        }
        return Duration.ofMillis(Math.round(exponential * (1.0 - jitter / 2.0)));
    }
}
