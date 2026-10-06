package io.github.mgeladzerezo.ledger.domain;

/** Life cycle of an authorisation hold: {@code ACTIVE} is the only non-terminal state. */
public enum HoldStatus {
    ACTIVE,
    CAPTURED,
    RELEASED,
    EXPIRED
}
