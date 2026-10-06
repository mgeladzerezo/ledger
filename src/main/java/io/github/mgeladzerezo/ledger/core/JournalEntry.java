package io.github.mgeladzerezo.ledger.core;

import java.time.Instant;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.domain.Side;

/** A persisted, immutable journal line. */
public record JournalEntry(long id, UUID transactionId, int lineNo, long accountId, Side side, long amount,
                           String currency, Instant createdAt) {
}
