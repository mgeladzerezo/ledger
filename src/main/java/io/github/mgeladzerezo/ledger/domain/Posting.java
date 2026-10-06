package io.github.mgeladzerezo.ledger.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A journal transaction that has not been written yet. This is layer one of the "debits equal
 * credits" rule: an unbalanced {@code Posting} cannot be constructed, so no code path can even
 * ask the database to store one. (Layer two is the deferred constraint trigger in
 * {@code V1__ledger_core.sql}; layer three is the reconciliation job.)
 *
 * @param reversesId the transaction this one cancels, or {@code null}
 * @param metadata   free-form string attributes stored with the transaction (for example an FX rate)
 */
public record Posting(TransactionKind kind, String description, List<EntryLine> lines, UUID reversesId,
                      Map<String, String> metadata) {

    public Posting {
        Objects.requireNonNull(kind, "kind");
        description = description == null ? "" : description;
        lines = List.copyOf(lines);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (lines.size() < 2) {
            throw new LedgerException.InvalidPosting("a transaction needs at least two entries, got " + lines.size());
        }
        Map<String, Long> net = new HashMap<>();
        for (EntryLine line : lines) {
            long signed = line.side() == Side.DEBIT ? line.amount() : -line.amount();
            try {
                net.merge(line.currency(), signed, Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new LedgerException.InvalidPosting("entry amounts overflow a 64-bit total");
            }
        }
        net.forEach((currency, difference) -> {
            if (difference != 0) {
                throw new LedgerException.InvalidPosting(
                        "debits and credits differ by " + Math.abs(difference) + " in " + currency);
            }
        });
    }

    public Posting(TransactionKind kind, String description, List<EntryLine> lines) {
        this(kind, description, lines, null, Map.of());
    }

    /** The classic two-line posting: debit one account, credit another, same amount and currency. */
    public static Posting simple(TransactionKind kind, String description, long debitAccountId,
                                 long creditAccountId, long amount, String currency) {
        if (debitAccountId == creditAccountId) {
            throw new LedgerException.InvalidPosting("source and destination accounts must differ");
        }
        return new Posting(kind, description,
                List.of(EntryLine.debit(debitAccountId, amount, currency),
                        EntryLine.credit(creditAccountId, amount, currency)));
    }
}
