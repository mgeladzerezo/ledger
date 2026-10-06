package io.github.mgeladzerezo.ledger.core;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.domain.AccountStatus;
import io.github.mgeladzerezo.ledger.domain.AccountType;
import io.github.mgeladzerezo.ledger.domain.EntryLine;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.domain.Posting;
import io.github.mgeladzerezo.ledger.domain.Side;
import io.github.mgeladzerezo.ledger.domain.TransactionKind;
import io.github.mgeladzerezo.ledger.outbox.OutboxWriter;
import org.springframework.stereotype.Service;

/**
 * The ledger's operations, each translated into a balanced {@link Posting} and handed to
 * {@link LedgerPoster}.
 *
 * <p>None of these methods opens a transaction, and {@link LedgerPoster} and {@link OutboxWriter}
 * refuse to write without one. The transaction is opened by the idempotency layer
 * ({@code IdempotentExecutor}), which is what guarantees that the idempotency key, the entries,
 * the balances and the outbox event share one commit. Declarative {@code @Transactional} is
 * avoided on purpose: a business rejection thrown through a transactional proxy would mark the
 * whole transaction rollback-only, and the rejection could then not be stored under the key.
 *
 * <p>The convenience operations (deposit, withdrawal, transfer, FX) are defined for
 * credit-normal accounts, the usual shape of customer money in a payments ledger: the platform
 * owes the customer, so the customer's balance is a liability and grows with credits. Anything
 * else can be expressed through {@link #postEntries}.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final HoldRepository holds;
    private final LedgerPoster poster;
    private final OutboxWriter outbox;

    public LedgerService(AccountRepository accounts, JournalRepository journal, HoldRepository holds,
                         LedgerPoster poster, OutboxWriter outbox) {
        this.accounts = accounts;
        this.journal = journal;
        this.holds = holds;
        this.poster = poster;
        this.outbox = outbox;
    }

    public Account openAccount(String code, String name, AccountType type, String currency, boolean nonNegative) {
        long id = accounts.insert(code, name, type, currency, nonNegative, false)
                .orElseThrow(() -> new LedgerException.Conflict("account-code-taken", "Account code already in use",
                        "an account with code '" + code + "' already exists"));
        Account account = accounts.findById(id).orElseThrow();
        outbox.append("account.opened", null, account);
        return account;
    }

    /**
     * The ledger's own account for {@code role} in {@code currency}, created on first use. Safe
     * to race: the unique code makes the second creator wait and then read the winner's row.
     */
    public Account systemAccount(SystemAccount role, String currency) {
        String code = role.code(currency);
        return accounts.findByCode(code).orElseGet(() -> {
            accounts.insert(code, role.label() + " " + currency, AccountType.ASSET, currency, false, true);
            return accounts.findByCode(code).orElseThrow();
        });
    }

    /** Money enters the ledger: debit the cash ("world") account, credit the customer. */
    public JournalTransaction deposit(long accountId, long amount, String currency, String description,
                                      Caller caller) {
        requireCreditNormal(accountId);
        Account cash = systemAccount(SystemAccount.CASH, currency);
        return poster.post(Posting.simple(TransactionKind.DEPOSIT, description, cash.id(), accountId, amount,
                currency), caller);
    }

    /** Money leaves the ledger: debit the customer, credit the cash ("world") account. */
    public JournalTransaction withdraw(long accountId, long amount, String currency, String description,
                                       Caller caller) {
        requireCreditNormal(accountId);
        Account cash = systemAccount(SystemAccount.CASH, currency);
        return poster.post(Posting.simple(TransactionKind.WITHDRAWAL, description, accountId, cash.id(), amount,
                currency), caller);
    }

    public JournalTransaction transfer(long fromAccountId, long toAccountId, long amount, String currency,
                                       String description, Caller caller) {
        requireCreditNormal(fromAccountId, toAccountId);
        return poster.post(Posting.simple(TransactionKind.TRANSFER, description, fromAccountId, toAccountId,
                amount, currency), caller);
    }

    /** An arbitrary balanced transaction: any number of lines, any account types, several currencies. */
    public JournalTransaction postEntries(String description, List<EntryLine> lines, Caller caller) {
        return poster.post(new Posting(TransactionKind.MULTI_LEG, description, lines), caller);
    }

    /**
     * Cancels a transaction by posting its mirror image. The original is left untouched; the two
     * are linked through {@code reversesId}. A transaction can be reversed once, and a reversal
     * cannot itself be reversed. The reversal is subject to the same overdraft rule as any other
     * posting: refunding money the recipient has already spent is rejected, not forced.
     */
    public JournalTransaction reverse(UUID transactionId, String reason, Caller caller) {
        JournalTransaction original = journal.find(transactionId)
                .orElseThrow(() -> new LedgerException.NotFound("transaction", transactionId));
        if (original.kind() == TransactionKind.REVERSAL) {
            throw new LedgerException.InvalidRequest("transaction " + transactionId + " is itself a reversal");
        }
        List<EntryLine> mirrored = original.entries().stream()
                .map(e -> new EntryLine(e.accountId(), e.side().opposite(), e.amount(), e.currency()))
                .toList();
        String description = "Reversal of " + transactionId + (reason == null || reason.isBlank() ? "" : ": " + reason);
        return poster.post(new Posting(TransactionKind.REVERSAL, description, mirrored, transactionId, Map.of()),
                caller);
    }

    /**
     * Currency exchange through per-currency clearing accounts, so each currency still balances
     * on its own: the source currency moves customer to clearing, the target currency moves
     * clearing to customer. The clearing balances are the platform's open FX position.
     */
    public JournalTransaction fxTransfer(long fromAccountId, long toAccountId, long sourceAmount,
                                         String sourceCurrency, long targetAmount, String targetCurrency,
                                         String description, Caller caller) {
        if (sourceCurrency.equals(targetCurrency)) {
            throw new LedgerException.InvalidRequest("an FX transfer needs two different currencies");
        }
        requireCreditNormal(fromAccountId, toAccountId);
        Account sourceClearing = systemAccount(SystemAccount.FX_CLEARING, sourceCurrency);
        Account targetClearing = systemAccount(SystemAccount.FX_CLEARING, targetCurrency);
        List<EntryLine> lines = List.of(
                EntryLine.debit(fromAccountId, sourceAmount, sourceCurrency),
                EntryLine.credit(sourceClearing.id(), sourceAmount, sourceCurrency),
                EntryLine.debit(targetClearing.id(), targetAmount, targetCurrency),
                EntryLine.credit(toAccountId, targetAmount, targetCurrency));
        Map<String, String> metadata = Map.of(
                "sourceAmount", Long.toString(sourceAmount), "sourceCurrency", sourceCurrency,
                "targetAmount", Long.toString(targetAmount), "targetCurrency", targetCurrency);
        return poster.post(new Posting(TransactionKind.FX_TRANSFER, description, lines, null, metadata), caller);
    }

    /** Freezes, unfreezes or closes an account. Closing requires a zero balance and no holds. */
    public Account changeStatus(long accountId, AccountStatus target) {
        Account account = lock(accountId);
        if (account.status() == AccountStatus.CLOSED) {
            throw new LedgerException.Conflict("account-closed", "Account is closed",
                    "account " + accountId + " is closed and cannot change status");
        }
        if (target == AccountStatus.CLOSED && (account.balance() != 0 || account.held() != 0)) {
            throw new LedgerException.Conflict("account-not-empty", "Account is not empty",
                    "account " + accountId + " still has a balance or active holds");
        }
        accounts.updateStatus(accountId, target);
        return accounts.findById(accountId).orElseThrow();
    }

    /**
     * Throws away the materialised balance and recomputes it from the journal and the active
     * holds, under the account's row lock. The journal is the source of truth; the balance row is
     * a cache that this method can always rebuild.
     */
    public Account rebuildBalance(long accountId) {
        lock(accountId);
        accounts.overwriteBalance(accountId, accounts.balanceFromEntries(accountId), holds.activeTotal(accountId));
        return accounts.findById(accountId).orElseThrow();
    }

    private Account lock(long accountId) {
        Account account = accounts.lockInOrder(new TreeSet<>(List.of(accountId))).get(accountId);
        if (account == null) {
            throw new LedgerException.NotFound("account", accountId);
        }
        return account;
    }

    private void requireCreditNormal(long... accountIds) {
        for (long accountId : accountIds) {
            Account account = accounts.findById(accountId)
                    .orElseThrow(() -> new LedgerException.NotFound("account", accountId));
            if (account.normalSide() != Side.CREDIT) {
                throw new LedgerException.InvalidRequest("account " + accountId + " is a debit-normal "
                        + account.type() + " account; use the journal endpoint to post to it explicitly");
            }
        }
    }

    /** Accounts the ledger owns. */
    public enum SystemAccount {
        /** The outside world: cash held at the bank on behalf of customers. */
        CASH("system:cash:", "Cash at bank"),
        /** Open FX position per currency. */
        FX_CLEARING("system:fx-clearing:", "FX clearing");

        private final String prefix;
        private final String label;

        SystemAccount(String prefix, String label) {
            this.prefix = prefix;
            this.label = label;
        }

        public String code(String currency) {
            return prefix + currency;
        }

        String label() {
            return label;
        }
    }
}
