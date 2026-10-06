package io.github.mgeladzerezo.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5));

    @Test
    void delayDoublesWithEveryFailedAttempt() {
        assertThat(policy.delay(1, 0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delay(2, 0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delay(3, 0)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delay(8, 0)).isEqualTo(Duration.ofSeconds(128));
    }

    @Test
    void delayIsCapped() {
        assertThat(policy.delay(10, 0)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.delay(1_000, 0)).as("no overflow for absurd attempt counts").isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void jitterShortensTheDelayByAtMostHalf() {
        assertThat(policy.delay(3, 0.5)).isEqualTo(Duration.ofSeconds(3));
        assertThat(policy.delay(3, 0.999)).isBetween(Duration.ofSeconds(2), Duration.ofMillis(2_010));
    }
}
