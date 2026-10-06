package io.github.mgeladzerezo.ledger.core;

import java.time.Instant;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.domain.HoldStatus;

/** An authorisation: {@code amount} of the account's available balance reserved until captured, released or expired. */
public record Hold(UUID id, long accountId, String currency, long amount, HoldStatus status, long capturedAmount,
                   UUID captureTransactionId, String description, Instant createdAt, Instant expiresAt,
                   Instant resolvedAt) {
}
