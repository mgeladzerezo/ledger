package io.github.mgeladzerezo.ledger.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.reconciliation.ReconciliationRun;
import io.github.mgeladzerezo.ledger.reconciliation.ReconciliationService;
import io.github.mgeladzerezo.ledger.reporting.ReportService;
import io.github.mgeladzerezo.ledger.reporting.ReportService.TrialBalance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reconciliation reports and the trial balance. */
@RestController
@RequestMapping("/api/v1")
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final ReportService reports;

    public ReconciliationController(ReconciliationService reconciliation, ReportService reports) {
        this.reconciliation = reconciliation;
        this.reports = reports;
    }

    /**
     * Runs every check now and returns the stored report. No idempotency key: a run only reads
     * the ledger and appends a report, so a duplicate request costs one extra report.
     */
    @PostMapping("/reconciliation/runs")
    @ResponseStatus(HttpStatus.CREATED)
    ReconciliationRun run() {
        return reconciliation.runNow();
    }

    @GetMapping("/reconciliation/runs")
    List<ReconciliationRun> list(@RequestParam(required = false) Integer limit) {
        return reconciliation.list(Page.limit(limit));
    }

    @GetMapping("/reconciliation/runs/{id}")
    ReconciliationRun get(@PathVariable long id) {
        return reconciliation.find(id).orElseThrow(() -> new LedgerException.NotFound("reconciliation run", id));
    }

    /** @param asOf ISO-8601 instant; entries created at or after it are excluded. Defaults to now. */
    @GetMapping("/reports/trial-balance")
    TrialBalance trialBalance(@RequestParam(required = false) String asOf) {
        try {
            return reports.trialBalance(asOf == null ? Instant.now() : Instant.parse(asOf));
        } catch (DateTimeParseException e) {
            throw new BadRequestException("asOf must be an ISO-8601 instant such as 2026-01-31T00:00:00Z");
        }
    }
}
