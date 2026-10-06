package io.github.mgeladzerezo.ledger.domain;

/**
 * A business-rule rejection. These are deterministic answers ("insufficient funds", "already
 * reversed"), not failures: the API maps them to 4xx problem details and the idempotency layer
 * stores them, so a retry with the same key gets the same rejection back.
 */
public abstract sealed class LedgerException extends RuntimeException {

    private final int status;
    private final String code;
    private final String title;

    protected LedgerException(int status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    /** HTTP status this rejection maps to. */
    public int status() {
        return status;
    }

    /** Stable machine-readable identifier, used as the suffix of the problem "type" URI. */
    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public static final class InvalidPosting extends LedgerException {
        public InvalidPosting(String detail) {
            super(422, "invalid-posting", "Invalid posting", detail);
        }
    }

    public static final class NotFound extends LedgerException {
        public NotFound(String what, Object id) {
            super(404, "not-found", "Not found", what + " " + id + " does not exist");
        }
    }

    public static final class AccountNotOpen extends LedgerException {
        public AccountNotOpen(long accountId, AccountStatus status) {
            super(422, "account-not-open", "Account is not open", "account " + accountId + " is " + status);
        }
    }

    public static final class CurrencyMismatch extends LedgerException {
        public CurrencyMismatch(long accountId, String accountCurrency, String entryCurrency) {
            super(422, "currency-mismatch", "Currency mismatch",
                    "account " + accountId + " is held in " + accountCurrency + ", not " + entryCurrency);
        }
    }

    public static final class InsufficientFunds extends LedgerException {
        public InsufficientFunds(long accountId, long available, long required) {
            super(422, "insufficient-funds", "Insufficient funds",
                    "account " + accountId + " has " + available + " available, " + required + " required");
        }
    }

    public static final class Conflict extends LedgerException {
        public Conflict(String code, String title, String detail) {
            super(409, code, title, detail);
        }
    }

    public static final class InvalidRequest extends LedgerException {
        public InvalidRequest(String detail) {
            super(422, "invalid-request", "Invalid request", detail);
        }
    }
}
