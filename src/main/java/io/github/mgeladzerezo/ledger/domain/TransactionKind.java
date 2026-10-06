package io.github.mgeladzerezo.ledger.domain;

/** Why a journal transaction exists. Informational: the posting rules are the same for all kinds. */
public enum TransactionKind {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    CAPTURE,
    REVERSAL,
    MULTI_LEG,
    FX_TRANSFER
}
