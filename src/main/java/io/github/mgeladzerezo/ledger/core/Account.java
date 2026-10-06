package io.github.mgeladzerezo.ledger.core;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.mgeladzerezo.ledger.domain.AccountStatus;
import io.github.mgeladzerezo.ledger.domain.AccountType;
import io.github.mgeladzerezo.ledger.domain.Side;

/**
 * An account together with its materialised balance row.
 *
 * @param balance     net of all posted entries, on the account's normal side
 * @param held        amount reserved by active holds; not spendable
 * @param nonNegative if set, {@code balance - held} may never drop below zero
 * @param system      created by the ledger itself (cash, clearing), not by a client
 * @param version     number of balance mutations so far
 */
public record Account(long id, String code, String name, AccountType type, Side normalSide, String currency,
                      AccountStatus status, boolean nonNegative, boolean system, Instant createdAt,
                      long balance, long held, long version, Instant balanceUpdatedAt) {

    /** What the owner can spend or have placed on hold right now. */
    @JsonProperty("available")
    public long available() {
        return balance - held;
    }
}
