package io.github.mgeladzerezo.ledger.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes keys past their retention. After that a request with the same key is treated as new,
 * which is the documented contract: a client must not retry a request for longer than the
 * retention period.
 */
@Component
public class IdempotencyKeyPurger {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurger.class);

    private final IdempotencyRepository keys;

    public IdempotencyKeyPurger(IdempotencyRepository keys) {
        this.keys = keys;
    }

    @Scheduled(fixedDelayString = "${ledger.idempotency.purge-interval:10m}",
            initialDelayString = "${ledger.idempotency.purge-interval:10m}")
    public void purge() {
        int deleted = keys.purgeExpired();
        if (deleted > 0) {
            log.info("purged {} expired idempotency keys", deleted);
        }
    }
}
