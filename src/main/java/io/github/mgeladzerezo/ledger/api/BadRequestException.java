package io.github.mgeladzerezo.ledger.api;

/**
 * The request is malformed (missing field, wrong type, bad cursor). Raised before the
 * idempotency layer runs, so it is never stored under a key: fixing the request and resending it
 * with the same key works.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new BadRequestException(message);
        }
    }
}
