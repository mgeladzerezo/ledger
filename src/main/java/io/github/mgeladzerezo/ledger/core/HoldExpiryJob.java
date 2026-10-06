package io.github.mgeladzerezo.ledger.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Gives reserved funds back when nobody captured or released a hold before its expiry. */
@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);
    private static final int BATCH = 100;

    private final HoldService holds;
    private final TransactionTemplate transaction;

    public HoldExpiryJob(HoldService holds, PlatformTransactionManager transactionManager) {
        this.holds = holds;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${ledger.holds.expiry-interval:30s}",
            initialDelayString = "${ledger.holds.expiry-interval:30s}")
    public void expire() {
        int expired = expireBatch();
        if (expired > 0) {
            log.info("expired {} holds", expired);
        }
    }

    /** One batch in one transaction. Returns how many holds were expired. */
    public int expireBatch() {
        Integer expired = transaction.execute(status -> holds.expireDue(BATCH));
        return expired == null ? 0 : expired;
    }
}
