package io.github.mgeladzerezo.ledger.domain;

/** The two columns of double-entry bookkeeping. */
public enum Side {
    DEBIT,
    CREDIT;

    public Side opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
