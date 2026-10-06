package io.github.mgeladzerezo.ledger.reporting;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.core.Account;
import io.github.mgeladzerezo.ledger.core.AccountRepository;
import io.github.mgeladzerezo.ledger.core.Rows;
import io.github.mgeladzerezo.ledger.domain.AccountType;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.domain.Side;
import io.github.mgeladzerezo.ledger.domain.TransactionKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read-only accounting reports, computed from the journal (never from the materialised balances)
 * inside a single REPEATABLE READ snapshot so the figures of one report belong to one instant.
 */
@Service
public class ReportService {

    private final JdbcClient jdbc;
    private final AccountRepository accounts;
    private final TransactionTemplate snapshot;

    public ReportService(JdbcClient jdbc, AccountRepository accounts, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
    }

    /**
     * The trial balance: every account's net debit or credit balance from entries created before
     * {@code asOf}, with per-currency totals. In a sound ledger the two totals of each currency
     * are equal.
     */
    public TrialBalance trialBalance(Instant asOf) {
        List<TrialBalance.Line> lines = snapshot.execute(status -> jdbc.sql("""
                        select a.id, a.code, a.name, a.type, a.currency,
                               coalesce(sum(e.amount) filter (where e.side = 'DEBIT'), 0) as debits,
                               coalesce(sum(e.amount) filter (where e.side = 'CREDIT'), 0) as credits
                        from accounts a
                        left join journal_entries e on e.account_id = a.id and e.created_at < ?
                        group by a.id
                        order by a.currency, a.type, a.id
                        """)
                .param(Timestamp.from(asOf))
                .query((rs, rowNum) -> {
                    long net = rs.getLong("debits") - rs.getLong("credits");
                    return new TrialBalance.Line(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                            AccountType.valueOf(rs.getString("type")), rs.getString("currency"),
                            rs.getLong("debits"), rs.getLong("credits"), Math.max(net, 0), Math.max(-net, 0));
                })
                .list());

        Map<String, long[]> sums = new TreeMap<>();
        for (TrialBalance.Line line : lines) {
            long[] sum = sums.computeIfAbsent(line.currency(), c -> new long[2]);
            sum[0] += line.debitBalance();
            sum[1] += line.creditBalance();
        }
        List<TrialBalance.Total> totals = new ArrayList<>();
        sums.forEach((currency, sum) -> totals.add(new TrialBalance.Total(currency, sum[0], sum[1], sum[0] == sum[1])));
        return new TrialBalance(asOf, lines, totals);
    }

    /** One account's activity for one UTC calendar day, with opening, running and closing balances. */
    public Statement statement(long accountId, LocalDate date) {
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return snapshot.execute(status -> {
            Account account = accounts.findById(accountId)
                    .orElseThrow(() -> new LedgerException.NotFound("account", accountId));
            long opening = jdbc.sql("""
                            select coalesce(sum(case when side = ? then amount else -amount end), 0)
                            from journal_entries
                            where account_id = ? and created_at < ?
                            """)
                    .params(account.normalSide().name(), accountId, Timestamp.from(from))
                    .query(Long.class)
                    .single();
            long[] running = {opening};
            List<Statement.Line> lines = jdbc.sql("""
                            select e.id, e.transaction_id, e.side, e.amount, e.created_at, t.kind, t.description
                            from journal_entries e
                            join journal_transactions t on t.id = e.transaction_id
                            where e.account_id = ? and e.created_at >= ? and e.created_at < ?
                            order by e.id
                            """)
                    .params(accountId, Timestamp.from(from), Timestamp.from(to))
                    .query((rs, rowNum) -> {
                        Side side = Side.valueOf(rs.getString("side"));
                        long amount = rs.getLong("amount");
                        running[0] += side == account.normalSide() ? amount : -amount;
                        return new Statement.Line(rs.getLong("id"), Rows.uuid(rs, "transaction_id"),
                                TransactionKind.valueOf(rs.getString("kind")), rs.getString("description"), side,
                                amount, running[0], Rows.instant(rs, "created_at"));
                    })
                    .list();
            return new Statement(accountId, account.code(), account.currency(), date, opening, running[0], lines);
        });
    }

    /** @param asOf entries created at or after this instant are excluded */
    public record TrialBalance(Instant asOf, List<Line> lines, List<Total> totals) {

        /** One account. Exactly one of {@code debitBalance} and {@code creditBalance} is non-zero, or both are zero. */
        public record Line(long accountId, String code, String name, AccountType type, String currency,
                           long debits, long credits, long debitBalance, long creditBalance) {
        }

        public record Total(String currency, long debitBalance, long creditBalance, boolean balanced) {
        }
    }

    /** @param date the UTC day covered */
    public record Statement(long accountId, String code, String currency, LocalDate date, long openingBalance,
                            long closingBalance, List<Line> lines) {

        public record Line(long entryId, UUID transactionId, TransactionKind kind, String description, Side side,
                           long amount, long balanceAfter, Instant createdAt) {
        }
    }
}
