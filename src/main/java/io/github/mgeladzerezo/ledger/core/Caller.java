package io.github.mgeladzerezo.ledger.core;

/**
 * Who is asking, and under which idempotency key. Recorded on every journal transaction so a
 * posting can be traced back to the request that caused it.
 */
public record Caller(String principal, String idempotencyKey) {

    /** For work the ledger starts itself (hold expiry, demo traffic without a key). */
    public static Caller system(String name) {
        return new Caller(name, null);
    }
}
