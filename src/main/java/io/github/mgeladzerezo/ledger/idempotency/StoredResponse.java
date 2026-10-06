package io.github.mgeladzerezo.ledger.idempotency;

/**
 * The response recorded under an idempotency key.
 *
 * @param body     the JSON body exactly as first produced; replays return these same bytes
 * @param replayed {@code true} if this response was read back rather than produced by this request
 */
public record StoredResponse(int status, String body, boolean replayed) {
}
