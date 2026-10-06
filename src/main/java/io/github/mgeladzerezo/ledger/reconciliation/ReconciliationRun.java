package io.github.mgeladzerezo.ledger.reconciliation;

import java.time.Instant;
import java.util.List;

import io.github.mgeladzerezo.ledger.reconciliation.ReconciliationCheck.Severity;

/**
 * A stored reconciliation report.
 *
 * @param status   CLEAN (no findings), WARNINGS (only warnings) or FAILED (at least one critical finding)
 * @param checks   one line per check that ran, with its number of findings
 * @param findings the violations; empty when the report is loaded as part of a list
 */
public record ReconciliationRun(long id, String trigger, String status, Instant startedAt, Instant finishedAt,
                                int findingCount, List<CheckResult> checks, List<Finding> findings) {

    public record CheckResult(String name, Severity severity, String description, int findings) {
    }

    public record Finding(String check, Severity severity, String subject, String detail) {
    }
}
