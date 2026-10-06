package io.github.mgeladzerezo.ledger.core;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.config.LedgerProperties;
import io.github.mgeladzerezo.ledger.core.HoldRepository.LockedHold;
import io.github.mgeladzerezo.ledger.domain.AccountStatus;
import io.github.mgeladzerezo.ledger.domain.HoldStatus;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.domain.Posting;
import io.github.mgeladzerezo.ledger.domain.Side;
import io.github.mgeladzerezo.ledger.domain.TransactionKind;
import io.github.mgeladzerezo.ledger.outbox.OutboxWriter;
import org.springframework.stereotype.Service;

/**
 * Two-phase payments. A hold (authorisation) reserves funds without moving them: it raises
 * {@code account_balances.held}, which lowers the available balance, and writes no journal
 * entries. Capture posts the real transfer and drops the reservation in the same transaction;
 * release and expiry just drop the reservation.
 *
 * <p>Lock order is always hold row first, then account rows ascending, so two operations on the
 * same hold queue on the hold row and never interleave their account locks.
 */
@Service
public class HoldService {

    private final HoldRepository holds;
    private final AccountRepository accounts;
    private final LedgerPoster poster;
    private final OutboxWriter outbox;
    private final Duration defaultTtl;

    public HoldService(HoldRepository holds, AccountRepository accounts, LedgerPoster poster, OutboxWriter outbox,
                       LedgerProperties properties) {
        this.holds = holds;
        this.accounts = accounts;
        this.poster = poster;
        this.outbox = outbox;
        this.defaultTtl = properties.holds().defaultTtl();
    }

    /** @param ttl how long the reservation lasts if nobody captures or releases it; {@code null} for the default */
    public Hold place(long accountId, long amount, String currency, String description, Duration ttl, Caller caller) {
        if (amount <= 0) {
            throw new LedgerException.InvalidRequest("hold amount must be positive");
        }
        Account account = accounts.lockInOrder(new TreeSet<>(List.of(accountId))).get(accountId);
        if (account == null) {
            throw new LedgerException.NotFound("account", accountId);
        }
        if (account.status() != AccountStatus.OPEN) {
            throw new LedgerException.AccountNotOpen(accountId, account.status());
        }
        if (account.normalSide() != Side.CREDIT) {
            throw new LedgerException.InvalidRequest("holds are only supported on credit-normal accounts");
        }
        if (!account.currency().equals(currency)) {
            throw new LedgerException.CurrencyMismatch(accountId, account.currency(), currency);
        }
        if (account.nonNegative() && account.available() < amount) {
            throw new LedgerException.InsufficientFunds(accountId, account.available(), amount);
        }
        UUID id = UUID.randomUUID();
        accounts.applyDelta(accountId, 0, amount);
        holds.insert(id, accountId, currency, amount, description == null ? "" : description,
                ttl == null ? defaultTtl : ttl, caller.principal());
        Hold hold = holds.find(id).orElseThrow();
        outbox.append("hold.created", null, hold);
        return hold;
    }

    /**
     * Turns a hold into a real transfer to {@code toAccountId}. The amount may be lower than the
     * hold (partial capture); the remainder of the reservation is released, not kept.
     *
     * @param amount amount to capture, or {@code null} for the full hold
     */
    public Capture capture(UUID holdId, Long amount, long toAccountId, String description, Caller caller) {
        LockedHold locked = lockActive(holdId);
        if (locked.expired()) {
            throw new LedgerException.Conflict("hold-expired", "Hold has expired",
                    "hold " + holdId + " expired at " + locked.hold().expiresAt());
        }
        Hold hold = locked.hold();
        long captured = amount == null ? hold.amount() : amount;
        if (captured <= 0 || captured > hold.amount()) {
            throw new LedgerException.InvalidRequest(
                    "capture amount must be between 1 and the held amount " + hold.amount());
        }
        String text = description == null || description.isBlank() ? "Capture of hold " + holdId : description;
        JournalTransaction transaction = poster.post(
                Posting.simple(TransactionKind.CAPTURE, text, hold.accountId(), toAccountId, captured, hold.currency()),
                caller,
                Map.of(hold.accountId(), hold.amount()));
        holds.markCaptured(holdId, captured, transaction.id());
        Hold after = holds.find(holdId).orElseThrow();
        outbox.append("hold.captured", null, after);
        return new Capture(after, transaction);
    }

    public Hold release(UUID holdId) {
        return resolve(lockActive(holdId).hold(), HoldStatus.RELEASED);
    }

    /**
     * Releases holds whose expiry has passed.
     *
     * @return how many were expired in this transaction
     */
    public int expireDue(int limit) {
        List<Hold> due = holds.lockExpired(limit);
        due.forEach(hold -> resolve(hold, HoldStatus.EXPIRED));
        return due.size();
    }

    private LockedHold lockActive(UUID holdId) {
        LockedHold locked = holds.lock(holdId).orElseThrow(() -> new LedgerException.NotFound("hold", holdId));
        if (locked.hold().status() != HoldStatus.ACTIVE) {
            throw new LedgerException.Conflict("hold-not-active", "Hold is not active",
                    "hold " + holdId + " is " + locked.hold().status());
        }
        return locked;
    }

    private Hold resolve(Hold hold, HoldStatus status) {
        accounts.lockInOrder(new TreeSet<>(List.of(hold.accountId())));
        accounts.applyDelta(hold.accountId(), 0, -hold.amount());
        holds.markResolved(hold.id(), status);
        Hold after = holds.find(hold.id()).orElseThrow();
        outbox.append("hold." + status.name().toLowerCase(), null, after);
        return after;
    }

    /** Result of a capture: the resolved hold and the transaction that moved the money. */
    public record Capture(Hold hold, JournalTransaction transaction) {
    }
}
