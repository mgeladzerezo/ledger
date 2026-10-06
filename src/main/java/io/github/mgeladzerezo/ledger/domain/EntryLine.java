package io.github.mgeladzerezo.ledger.domain;

import java.util.Objects;

/**
 * One debit or credit against one account. Amounts are integer minor units (cents) and always
 * positive; direction is carried by {@link #side()}, never by sign.
 */
public record EntryLine(long accountId, Side side, long amount, String currency) {

    public EntryLine {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(currency, "currency");
        if (amount <= 0) {
            throw new LedgerException.InvalidPosting("entry amount must be positive, got " + amount);
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new LedgerException.InvalidPosting(
                    "currency must be a three-letter upper-case code, got '" + currency + "'");
        }
    }

    public static EntryLine debit(long accountId, long amount, String currency) {
        return new EntryLine(accountId, Side.DEBIT, amount, currency);
    }

    public static EntryLine credit(long accountId, long amount, String currency) {
        return new EntryLine(accountId, Side.CREDIT, amount, currency);
    }

    /** The same line on the other side, as used by a reversing transaction. */
    public EntryLine reversed() {
        return new EntryLine(accountId, side.opposite(), amount, currency);
    }

    /** Effect of this line on an account whose balance grows on {@code normalSide}. */
    public long balanceDelta(Side normalSide) {
        return side == normalSide ? amount : -amount;
    }
}
