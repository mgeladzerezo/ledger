package io.github.mgeladzerezo.ledger.reconciliation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.mgeladzerezo.ledger.config.LedgerProperties;
import io.github.mgeladzerezo.ledger.core.Rows;
import io.github.mgeladzerezo.ledger.reconciliation.ReconciliationCheck.Severity;
import io.github.mgeladzerezo.ledger.reconciliation.ReconciliationRun.CheckResult;
import io.github.mgeladzerezo.ledger.reconciliation.ReconciliationRun.Finding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs every {@link ReconciliationCheck} and stores the outcome as a report.
 *
 * <p>All checks of a run execute in one {@code REPEATABLE READ, READ ONLY} transaction, so they
 * see a single snapshot of the database. That matters while traffic is flowing: under the
 * default READ COMMITTED each query would see a newer state than the previous one, and comparing
 * "sum of entries" from one instant with "balance row" from another would report differences that
 * never existed. With one snapshot, any finding is a real inconsistency in committed data.
 */
@Service
public class ReconciliationService {

    /** Arbitrary constant identifying the "scheduled reconciliation" advisory lock. */
    static final long SCHEDULER_LOCK = 0x4C45444745520001L;

    private static final TypeReference<List<CheckResult>> CHECK_RESULTS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate write;
    private final LedgerProperties.Reconciliation config;

    public ReconciliationService(JdbcClient jdbc, JsonMapper json, PlatformTransactionManager transactionManager,
                                 LedgerProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.write = new TransactionTemplate(transactionManager);
        this.config = properties.reconciliation();
    }

    /** Runs all checks now and stores the report. */
    public ReconciliationRun runNow() {
        return run("MANUAL", false).orElseThrow();
    }

    /**
     * The scheduled entry point. Returns empty without doing anything if another instance is
     * running the scheduled job at this moment (transaction-scoped advisory lock), so a cluster
     * produces one report per interval rather than one per node.
     */
    public Optional<ReconciliationRun> runScheduled() {
        return run("SCHEDULED", true);
    }

    private Optional<ReconciliationRun> run(String trigger, boolean exclusive) {
        Instant startedAt = Instant.now();
        List<Finding> findings = snapshot.execute(status -> {
            if (exclusive && !jdbc.sql("select pg_try_advisory_xact_lock(?)").param(SCHEDULER_LOCK)
                    .query(Boolean.class).single()) {
                return null;
            }
            List<Finding> found = new ArrayList<>();
            for (ReconciliationCheck check : ReconciliationCheck.values()) {
                var statement = jdbc.sql(check.sql());
                if (check.sql().contains(":stuckMillis")) {
                    statement = statement.param("stuckMillis", config.stuckDeliveryAge().toMillis());
                }
                found.addAll(statement
                        .query((rs, rowNum) -> new Finding(check.name(), check.severity(), rs.getString("subject"),
                                rs.getString("detail")))
                        .list());
            }
            return found;
        });
        if (findings == null) {
            return Optional.empty();
        }
        return Optional.of(store(trigger, startedAt, findings));
    }

    private ReconciliationRun store(String trigger, Instant startedAt, List<Finding> findings) {
        List<CheckResult> checks = new ArrayList<>();
        for (ReconciliationCheck check : ReconciliationCheck.values()) {
            int count = (int) findings.stream().filter(f -> f.check().equals(check.name())).count();
            checks.add(new CheckResult(check.name(), check.severity(), check.description(), count));
        }
        String status = findings.isEmpty() ? "CLEAN"
                : findings.stream().anyMatch(f -> f.severity() == Severity.CRITICAL) ? "FAILED" : "WARNINGS";

        Long runId = write.execute(tx -> {
            long id = jdbc.sql("""
                            insert into reconciliation_runs (trigger, status, started_at, finished_at, checks_run,
                                                             finding_count, summary)
                            values (?, ?, ?, now(), ?, ?, ?::jsonb)
                            returning id
                            """)
                    .params(trigger, status, java.sql.Timestamp.from(startedAt), checks.size(), findings.size(),
                            json.writeValueAsString(checks))
                    .query(Long.class)
                    .single();
            for (Finding finding : findings) {
                jdbc.sql("""
                                insert into reconciliation_findings (run_id, check_name, severity, subject, detail)
                                values (?, ?, ?, ?, ?)
                                """)
                        .params(id, finding.check(), finding.severity().name(), finding.subject(), finding.detail())
                        .update();
            }
            jdbc.sql("""
                            delete from reconciliation_runs
                            where id <= (select id from reconciliation_runs order by id desc offset ? limit 1)
                            """)
                    .param(config.keepRuns())
                    .update();
            return id;
        });
        return find(runId).orElseThrow();
    }

    public Optional<ReconciliationRun> find(long runId) {
        List<Finding> findings = jdbc.sql("""
                        select check_name, severity, subject, detail
                        from reconciliation_findings
                        where run_id = ?
                        order by id
                        """)
                .param(runId)
                .query((rs, rowNum) -> new Finding(rs.getString("check_name"),
                        Severity.valueOf(rs.getString("severity")), rs.getString("subject"), rs.getString("detail")))
                .list();
        return jdbc.sql(SELECT_RUN + " where id = ?").param(runId)
                .query((rs, rowNum) -> mapRun(rs, findings))
                .optional();
    }

    /** Most recent reports first, without their findings. */
    public List<ReconciliationRun> list(int limit) {
        return jdbc.sql(SELECT_RUN + " order by id desc limit ?").param(limit)
                .query((rs, rowNum) -> mapRun(rs, List.of()))
                .list();
    }

    private static final String SELECT_RUN = """
            select id, trigger, status, started_at, finished_at, finding_count, summary::text as summary
            from reconciliation_runs
            """;

    private ReconciliationRun mapRun(java.sql.ResultSet rs, List<Finding> findings) throws java.sql.SQLException {
        return new ReconciliationRun(rs.getLong("id"), rs.getString("trigger"), rs.getString("status"),
                Rows.instant(rs, "started_at"), Rows.instant(rs, "finished_at"), rs.getInt("finding_count"),
                json.readValue(rs.getString("summary"), CHECK_RESULTS), findings);
    }
}
