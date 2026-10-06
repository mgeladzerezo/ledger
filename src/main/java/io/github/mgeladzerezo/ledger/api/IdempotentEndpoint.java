package io.github.mgeladzerezo.ledger.api;

import java.util.function.Function;

import io.github.mgeladzerezo.ledger.core.Caller;
import io.github.mgeladzerezo.ledger.idempotency.IdempotentExecutor;
import io.github.mgeladzerezo.ledger.idempotency.StoredResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Glue between a controller method and {@link IdempotentExecutor}: reads and validates the
 * {@code Idempotency-Key} header, runs the action under it, and turns the stored response into
 * the HTTP response. First execution and replay go through the same code and emit the same
 * stored bytes; a replay is only distinguishable by the {@code Idempotent-Replayed: true} header.
 */
@Component
public class IdempotentEndpoint {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 128;

    private final IdempotentExecutor executor;

    public IdempotentEndpoint(IdempotentExecutor executor) {
        this.executor = executor;
    }

    /**
     * @param body          the parsed request body, or an empty string for body-less operations
     * @param successStatus status to answer (and store) when the action succeeds
     * @param action        receives the caller and performs the ledger work inside the transaction
     */
    public ResponseEntity<String> run(HttpServletRequest request, Object body, int successStatus,
                                      Function<Caller, ?> action) {
        String key = request.getHeader(KEY_HEADER);
        BadRequestException.require(key != null && !key.isBlank(),
                "the " + KEY_HEADER + " header is required on mutating requests");
        BadRequestException.require(key.length() <= MAX_KEY_LENGTH && key.chars().allMatch(c -> c > 0x20 && c < 0x7f),
                KEY_HEADER + " must be 1 to " + MAX_KEY_LENGTH + " printable ASCII characters without spaces");

        Caller caller = new Caller(principal(request), key);
        String operation = request.getMethod() + " " + request.getRequestURI();
        StoredResponse stored = executor.execute(caller, operation, body, successStatus, () -> action.apply(caller));

        return ResponseEntity.status(stored.status())
                .contentType(stored.status() >= 400
                        ? MediaType.APPLICATION_PROBLEM_JSON
                        : MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, Boolean.toString(stored.replayed()))
                .body(stored.body());
    }

    static String principal(HttpServletRequest request) {
        return (String) request.getAttribute(ApiKeyFilter.PRINCIPAL_ATTRIBUTE);
    }
}
