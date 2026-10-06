package io.github.mgeladzerezo.ledger.domain;

/** Only {@link #OPEN} accounts accept postings and holds. */
public enum AccountStatus {
    OPEN,
    FROZEN,
    CLOSED
}
