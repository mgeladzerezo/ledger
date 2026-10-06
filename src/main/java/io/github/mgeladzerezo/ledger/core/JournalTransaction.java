package io.github.mgeladzerezo.ledger.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.domain.TransactionKind;

/**
 * A persisted journal transaction with its entries.
 *
 * @param seq        monotonically assigned sequence; the journal's pagination order
 * @param reversesId the transaction this one cancels, if it is a reversal
 * @param reversedBy the reversal that cancels this transaction, if one exists
 */
public record JournalTransaction(UUID id, long seq, TransactionKind kind, String description, UUID reversesId,
                                 UUID reversedBy, String idempotencyKey, String createdBy,
                                 Map<String, String> metadata, Instant createdAt, List<JournalEntry> entries) {
}
