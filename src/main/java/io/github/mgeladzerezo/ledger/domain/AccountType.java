package io.github.mgeladzerezo.ledger.domain;

/**
 * The five account classes of the accounting equation. The normal side is the side on which an
 * account of this type increases: assets and expenses grow with debits, the rest with credits.
 */
public enum AccountType {
    ASSET(Side.DEBIT),
    EXPENSE(Side.DEBIT),
    LIABILITY(Side.CREDIT),
    EQUITY(Side.CREDIT),
    REVENUE(Side.CREDIT);

    private final Side normalSide;

    AccountType(Side normalSide) {
        this.normalSide = normalSide;
    }

    public Side normalSide() {
        return normalSide;
    }
}
