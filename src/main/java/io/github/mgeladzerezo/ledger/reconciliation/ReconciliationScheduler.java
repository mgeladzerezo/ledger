package io.github.mgeladzerezo.ledger.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the reconciliation job on a fixed delay and shouts in the log when the books are wrong. */
@Component
@ConditionalOnProperty(name = "ledger.reconciliation.scheduled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliation;

    public ReconciliationScheduler(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${ledger.reconciliation.interval:60s}",
            initialDelayString = "${ledger.reconciliation.initial-delay:15s}")
    public void reconcile() {
        reconciliation.runScheduled().ifPresent(run -> {
            if (run.status().equals("FAILED")) {
                log.error("RECONCILIATION FAILED: report {} has {} findings", run.id(), run.findingCount());
            } else {
                log.debug("reconciliation report {}: {}", run.id(), run.status());
            }
        });
    }
}
