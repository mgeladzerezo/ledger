package io.github.mgeladzerezo.ledger.failpoint;

import java.util.Arrays;
import java.util.Optional;

/**
 * Named places in the money path and in the outbox relay where the process can be made to die
 * abruptly. Each constant documents what a crash at that point leaves behind; the crash test
 * matrix ({@code FailpointCrashIT}) proves each statement.
 */
public enum Failpoint {

    AFTER_IDEMPOTENCY_KEY("after-idempotency-key",
            "The idempotency key row is inserted but not committed; no ledger work has started.",
            "Nothing was committed, so the key is unknown after restart. The retry executes the transfer."),

    AFTER_DEBIT_ENTRY("after-debit-entry",
            "The transaction header and the debit entry are written, the credit entry is not.",
            "The open database transaction dies with the connection. No half transfer is visible; the retry executes."),

    AFTER_ENTRIES("after-entries-before-balances",
            "Both entries are written, the materialised balances are not yet updated.",
            "Entries and balances share one database transaction, so both are rolled back together."),

    AFTER_BALANCE_UPDATE("after-balance-update",
            "Entries and balances are written, the outbox event is not.",
            "Rolled back as a unit: there is never a posted transaction without its event."),

    AFTER_OUTBOX_INSERT("after-outbox-insert",
            "Entries, balances and the outbox event are written, the response is not stored under the key.",
            "Rolled back as a unit; the retry executes and emits exactly one event."),

    BEFORE_COMMIT("before-commit",
            "Everything, including the stored response, is written. COMMIT has not been sent.",
            "PostgreSQL discards the transaction when the connection drops. The retry executes."),

    AFTER_COMMIT_BEFORE_RESPONSE("after-commit-before-response",
            "COMMIT succeeded, the HTTP response was never written. The client cannot tell success from failure.",
            "The key and its response committed atomically with the money. The retry replays the stored 201."),

    RELAY_BEFORE_SEND("relay-before-send",
            "The relay has claimed a batch of deliveries (row locks held) and dies before any HTTP call.",
            "The row locks vanish with the connection. Deliveries stay PENDING and are sent after restart."),

    RELAY_AFTER_SEND_BEFORE_MARK("relay-after-send-before-mark",
            "The webhook was delivered and acknowledged, the delivery is still marked PENDING.",
            "The delivery is sent again after restart (at-least-once). The consumer discards the duplicate by event id."),

    RELAY_AFTER_MARK_BEFORE_COMMIT("relay-after-mark-before-commit",
            "The delivery was sent and marked SENT inside a transaction that never committed.",
            "Same as above: the mark is rolled back, the event is redelivered and deduplicated.");

    private final String id;
    private final String state;
    private final String recovery;

    Failpoint(String id, String state, String recovery) {
        this.id = id;
        this.state = state;
        this.recovery = recovery;
    }

    /** Name used in configuration and in the chaos API. */
    public String id() {
        return id;
    }

    /** What exists, uncommitted or committed, when the process dies here. */
    public String state() {
        return state;
    }

    /** Why the system is still correct afterwards. */
    public String recovery() {
        return recovery;
    }

    public static Optional<Failpoint> byId(String id) {
        return Arrays.stream(values()).filter(f -> f.id.equals(id)).findFirst();
    }
}
