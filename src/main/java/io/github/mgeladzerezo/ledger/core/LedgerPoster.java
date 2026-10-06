package io.github.mgeladzerezo.ledger.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.domain.AccountStatus;
import io.github.mgeladzerezo.ledger.domain.EntryLine;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.domain.Posting;
import io.github.mgeladzerezo.ledger.failpoint.Failpoint;
import io.github.mgeladzerezo.ledger.failpoint.Failpoints;
import io.github.mgeladzerezo.ledger.outbox.OutboxWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The single code path that writes journal entries. Every money movement (deposit, transfer,
 * capture, reversal, FX) is expressed as a {@link Posting} and goes through {@link #post}.
 *
 * <p>Within the caller's database transaction it does, in this order:
 * <ol>
 *   <li>locks the balance rows of all accounts involved, ascending by id;</li>
 *   <li>validates status and currency and checks overdrafts against the locked balances;</li>
 *   <li>inserts the transaction header and its entries;</li>
 *   <li>updates the materialised balances;</li>
 *   <li>writes the outbox event.</li>
 * </ol>
 * Either all of that commits or none of it does.
 *
 * <p><b>Why the overdraft check is correct under concurrency.</b> The check reads the balance
 * only after taking the row lock, and the lock is held until commit. Two withdrawals from the
 * same account therefore run one after the other; the second sees the first one's result. There
 * is no read-then-write window in which both could see the old balance. The CHECK constraint on
 * {@code account_balances} would reject the commit even if this code were wrong.
 */
@Component
public class LedgerPoster {

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final OutboxWriter outbox;
    private final Failpoints failpoints;

    public LedgerPoster(AccountRepository accounts, JournalRepository journal, OutboxWriter outbox,
                        Failpoints failpoints) {
        this.accounts = accounts;
        this.journal = journal;
        this.outbox = outbox;
        this.failpoints = failpoints;
    }

    public JournalTransaction post(Posting posting, Caller caller) {
        return post(posting, caller, Map.of());
    }

    /**
     * @param holdRelease per account, an amount of held funds released by this same posting (hold
     *                    capture); the reservation is swapped for the real debit atomically
     */
    public JournalTransaction post(Posting posting, Caller caller, Map<Long, Long> holdRelease) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("postings must run inside a database transaction");
        }

        SortedSet<Long> accountIds = new TreeSet<>();
        posting.lines().forEach(line -> accountIds.add(line.accountId()));
        Map<Long, Account> locked = accounts.lockInOrder(accountIds);

        Map<Long, Long> deltas = new TreeMap<>();
        for (EntryLine line : posting.lines()) {
            Account account = locked.get(line.accountId());
            if (account == null) {
                throw new LedgerException.NotFound("account", line.accountId());
            }
            if (account.status() != AccountStatus.OPEN) {
                throw new LedgerException.AccountNotOpen(account.id(), account.status());
            }
            if (!account.currency().equals(line.currency())) {
                throw new LedgerException.CurrencyMismatch(account.id(), account.currency(), line.currency());
            }
            deltas.merge(account.id(), line.balanceDelta(account.normalSide()), Math::addExact);
        }

        // Checked after the locks: two concurrent reversals of the same transaction touch the same
        // accounts, so the second one waits above and then sees the first one's committed row here.
        if (posting.reversesId() != null && journal.isReversed(posting.reversesId())) {
            throw new LedgerException.Conflict("already-reversed", "Already reversed",
                    "transaction " + posting.reversesId() + " has already been reversed");
        }

        deltas.forEach((accountId, delta) -> {
            Account account = locked.get(accountId);
            long released = holdRelease.getOrDefault(accountId, 0L);
            long availableAfter = Math.addExact(account.balance(), delta) - (account.held() - released);
            if (account.nonNegative() && delta < 0 && availableAfter < 0) {
                throw new LedgerException.InsufficientFunds(accountId, account.available() + released, -delta);
            }
        });

        UUID transactionId = UUID.randomUUID();
        journal.insertTransaction(transactionId, posting, caller);
        int lineNo = 0;
        for (EntryLine line : posting.lines()) {
            journal.insertEntry(transactionId, ++lineNo, line);
            if (lineNo == 1) {
                failpoints.hit(Failpoint.AFTER_DEBIT_ENTRY);
            }
        }
        failpoints.hit(Failpoint.AFTER_ENTRIES);

        deltas.forEach((accountId, delta) ->
                accounts.applyDelta(accountId, delta, -holdRelease.getOrDefault(accountId, 0L)));
        failpoints.hit(Failpoint.AFTER_BALANCE_UPDATE);

        JournalTransaction posted = journal.find(transactionId).orElseThrow();
        outbox.append(OutboxWriter.TRANSACTION_POSTED, transactionId, new TransactionPosted(posted, balanceDeltas(deltas, locked)));
        failpoints.hit(Failpoint.AFTER_OUTBOX_INSERT);
        return posted;
    }

    private static List<BalanceDelta> balanceDeltas(Map<Long, Long> deltas, Map<Long, Account> accounts) {
        List<BalanceDelta> result = new ArrayList<>();
        deltas.forEach((accountId, delta) -> result.add(
                new BalanceDelta(accountId, accounts.get(accountId).code(), accounts.get(accountId).currency(), delta)));
        return result;
    }

    /** Payload of the {@code transaction.posted} event. */
    public record TransactionPosted(JournalTransaction transaction, List<BalanceDelta> balanceDeltas) {
    }

    /** Net effect of one transaction on one account's balance, so consumers need not know normal sides. */
    public record BalanceDelta(long accountId, String accountCode, String currency, long delta) {
    }
}
